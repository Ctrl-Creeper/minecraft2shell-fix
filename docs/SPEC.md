# minecraft2shell-fix — implementation spec (for dsh)

Repo root: `/home/user/Data/Dev/minecraft2shell-fix`

Goal: a Java multi-module project that **cuts the Minecraft "font resource-pack RCE" attack chain
(bikini/minecraft2shell) without changing how legitimate fonts render**.

The vulnerability is native (FreeType 2.14.3, `Ins_SHZ` in `ttinterp.c` reached via the TrueType
bytecode interpreter during glyph hinting). We cannot patch C from Java, so we cut the chain at the
Java→native boundary: a font that carries the exploit primitive gets loaded **without hinting**
(bytecode interpreter never runs). Legitimate fonts keep full hinting ⇒ pixel-identical rendering.

## Verified facts (do NOT change these hook targets / descriptors)

From `javap` on the Mojang-signed Minecraft **26.3** client jar:

- `net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition.load(ResourceManager)`
  calls `FreeType.FT_New_Memory_Face(JLjava/nio/ByteBuffer;JLorg/lwjgl/PointerBuffer;)I`
- `com.mojang.blaze3d.font.TrueTypeGlyphProvider.loadGlyph(int, FT_Face, int)`
  calls `FreeType.FT_Load_Glyph(Lorg/lwjgl/util/freetype/FT_Face;II)I`

So both vanilla and modded clients go through `org.lwjgl.util.freetype.FreeType` static methods.
Hook exactly these (LWJGL = normal class loading, hookable from a java agent and from Mixin):

- `public static int FT_New_Memory_Face(long library, ByteBuffer file, long file_size, PointerBuffer aface)`
- `public static int FT_Load_Glyph(FT_Face face, int glyph_index, int load_flags)`

`FT_LOAD_NO_HINTING = 0x2`. In FreeType `ttgload.c` every glyph-program execution path is gated by
`IS_HINTED(load_flags)`, so OR-ing 0x2 kills the interpreter for that font only.

## Repo layout (create exactly this)

```
pom.xml                      (parent, packaging=pom, modules: core, agent, mod)
core/pom.xml                 artifact m2sfix-core      (no runtime deps)
agent/pom.xml                artifact m2sfix-agent     (byte-buddy shaded)
mod/pom.xml                  artifact m2sfix-mod       (mixin, produces ONE universal mod jar)
tools/make_fixture_fonts.py  (fontTools script that generates the test fonts)
docs/SPEC.md                 (this file)
README.md                    (written by the maintainer, do NOT touch)
.gitignore
```

Java release target: **21** (Minecraft 26.3 runtime). Maven, no Gradle.

## Module `core` — package `dev.duckducktw.m2sfix.core`

Pure Java, zero external deps, fully unit-testable, never throws to callers.

1. `ScanResult` — `record ScanResult(Verdict verdict, String reason)` with
   `enum Verdict { CLEAN, SUSPICIOUS, UNPARSABLE }` and a static `ScanResult.clean()`.
2. `TtfScanner`:
   - `public static ScanResult scan(byte[] font)` — never throws; any internal exception ⇒
     `ScanResult(Verdict.UNPARSABLE, msg)`.
   - Accept sfnt versions `0x00010000`, `'true'`, `'OTTO'`, `'typ1'`, and `'ttcf'` (scan **every**
     face in a collection; suspicious if any face is suspicious).
   - Parse the table directory; locate `fpgm`, `prep`, `glyf`, `loca`, `head`, `maxp`, `cvt `.
   - Decode TrueType instruction streams **properly** and look for the `IDEF` opcode `0x89`.
     Rules: instruction = 1 byte; `FDEF (0x2C)` and `IDEF (0x89)` each consume 1 extra operand byte
     (the definition number, read from `code[IP+1]`, `IP += 2` — same as FreeType `Ins_FDEF`);
     `PUSHB[0..7] (0xB0-0xB7)` consume 1..8 data bytes, `PUSH[0..7]`… use: `PUSHB` 0xB0-0xB7 ⇒ skip
     1+n, `PUSHW` 0xB8-0xBF ⇒ skip 1+2n, `NPUSHB (0x40)` ⇒ next byte = count, skip 1+count,
     `NPUSHW (0x41)` ⇒ next byte = count, skip 1+2*count. **Push payload bytes must never be
     interpreted as opcodes** (there is a unit test for this false positive).
   - Scan targets: `fpgm`, `prep`, and the instruction stream of every glyph in `glyf`
     (walk simple glyphs: `numberOfContours >= 0`, read `endPtsOfContours[last]+1` points, skip the
     flag/coordinate arrays to reach `instructionLength` + instructions; composite glyphs:
     `numberOfContours < 0`, walk component records until the `MORE_COMPONENTS` bit clears, then
     read `instructionLength`; respect `INDEX_TO_LOC_FORMAT` from `head` for 16/32-bit `loca`).
     Malformed glyph data ⇒ stop scanning glyphs, keep whatever verdict was already found, and do
     not throw.
   - Verdict: any `IDEF` found ⇒ `SUSPICIOUS` with reason `"IDEF instruction in <table/glyph>"`
     (mention how many occurrences). No `IDEF` ⇒ `CLEAN`. Also count `SHZ (0x36)` occurrences and
     put the count in the CLEAN reason (diagnostics only, does not change the verdict).
   - If there is no `glyf` table (CFF/OTF): still scan `fpgm`/`prep`, then `CLEAN`/`SUSPICIOUS`.
   - Missing/other broken tables ⇒ still usable: scan what exists; only unrecoverable structures
     (bad sfnt header, truncated directory) ⇒ `UNPARSABLE`.
3. `FixPolicy` — reads system properties once, with sane defaults:
   - `m2sfix.enabled` (default `true`)
   - `m2sfix.policy` = `hintless` (default) | `block` | `off`
     - `hintless`: suspicious/unparsable font is loaded with `FT_LOAD_NO_HINTING`
     - `block`: suspicious/unparsable font's glyphs are refused (`FT_Load_Glyph` returns
       `FT_Err_Invalid_File_Format = 0x02` without calling native, glyph renders as missing)
     - `off`: do nothing
   - `m2sfix.unparsable` = `hintless` (default) | `allow`  (what to do with `UNPARSABLE`)
   - `m2sfix.log` (default `true`) — one line per flagged font, no spam (log once per face hash)
   - `m2sfix.sha256.allow` — comma-separated sha256 hex of font files to always treat as CLEAN
4. `FaceGuard` — the shared "policy brain" used by BOTH the agent and the mod (so the semantics are
   tested once):
   - `public static boolean inspectNewFace(long faceAddress, ByteBuffer fontData, long size)`
     - reads `min(size, remaining)` bytes into a `byte[]`, honours the sha256 allowlist, runs
       `TtfScanner.scan`, stores the verdict, returns `true` if this face must lose hinting;
       always returns `false` when `m2sfix.enabled=false` / policy `off`.
   - `public static int adjustLoadFlags(long faceAddress, int flags)` — returns `flags | 0x2` for
     flagged faces, otherwise unchanged.
   - `public static int beforeLoadGlyph(long faceAddress)` — returns `0` to continue, or an FT error
     code to return early (policy `block`).
   - Backed by a small bounded map (LRU, max 4096 entries) keyed by face address, thread-safe
     (`ConcurrentHashMap` + a size guard is fine). Needs a `clearForTests()` method.
   - Must tolerate `fontData == null`, `size <= 0`, and absurd sizes (read at most 64 MiB).

## Module `agent` — package `dev.duckducktw.m2sfix.agent`

ByteBuddy `1.14.x` (shade + relocate `net.bytebuddy` → `dev.duckducktw.m2sfix.shaded.bytebuddy`,
`Premain-Class` manifest). Java 21 target.

- `FixAgent.premain(String args, Instrumentation inst)`:
  - `AgentBuilder.Default` with `.disableClassFormatChanges()`
    `.with(RedefinitionStrategy.RETRANSFORMATION)`
    `.with(InitializationStrategy.NoOp.INSTANCE)`
    `.ignore(...)` **everything except** `org.lwjgl.util.freetype.FreeType` — use
    `.ignore(not(nameStartsWith("org.lwjgl.util.freetype.")))` (this keeps ByteBuddy from touching
    the other ~thousand Minecraft classes; without it the log explodes).
  - `.type(named("org.lwjgl.util.freetype.FreeType"))` with two `Advice` transforms:
    - `FT_New_Memory_Face` → `NewFaceAdvice` (OnMethodExit; read args, then
      `FaceGuard.inspectNewFace(faceAddr, file, size)`; `faceAddr` read from `PointerBuffer` when
      non-null; skip when the return value != 0)
    - `FT_Load_Glyph` → `LoadGlyphAdvice`:
      - `@Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)`: resolve the face address
        via reflection (`face.getClass().getMethod("address")`), call
        `FaceGuard.beforeLoadGlyph(addr)`; for policy `block` return the non-zero error code so the
        native call never happens; otherwise write the adjusted flags back with
        `@Advice.Argument(value = 2, readOnly = false, typing = Assigner.Typing.DYNAMIC)`.
      - Advice bodies must **not** reference any `org.lwjgl` / Minecraft type in their signatures
        (use `Object` + reflection) so advice loading can never fail on a classloader that lacks
        those types.
  - Wrap everything in try/catch: **the agent must never break the game**; on any internal error,
    log once and switch itself off.
- `src/main/resources/META-INF/MANIFEST.MF` fragments via `maven-jar-plugin`
  (`Premain-Class`, `Can-Retransform-Classes: true`).

## Module `mod` — package `dev.duckducktw.m2sfix.mod`

ONE jar, loader-agnostic, containing `core` + these classes (shaded/flattened) + BOTH metadata sets:

- `FreeTypeTargetMixin` — `@Mixin(targets = "org.lwjgl.util.freetype.FreeType")`,
  `remap = false`, `compatibilityLevel = JAVA_21`:
  - `@Inject(method = "FT_New_Memory_Face", at = @At("RETURN"))` static handler: when
    `ci.getReturnValueI() == 0` and `aface != null`, call `FaceGuard.inspectNewFace(...)`.
  - `@ModifyVariable(method = "FT_Load_Glyph", at = @At("HEAD"), argsOnly = true, index = 2)`:
    return `FaceGuard.adjustLoadFlags(faceAddress(face), flags)`.
  - Both handlers must be `private static` and tolerant of nulls; must not use `@Shadow`.
- `m2sfix.mixins.json` — `{"required": true, "minVersion": "0.8", "package": "dev.duckducktw.m2sfix.mod",
  "compatibilityLevel": "JAVA_21", "mixins": ["FreeTypeTargetMixin"], "injectors": {"defaultRequire": 1}}`
  (no `refmap` key — targets are non-Minecraft classes, nothing to remap).
- `fabric.mod.json` — `{"schemaVersion": 1, "id": "m2sfix", "version": "<ver>", "name":
  "minecraft2shell-fix", "environment": "client", "mixins": ["m2sfix.mixins.json"],
  "depends": {"minecraft": ">=26.3"}}`
- `META-INF/mods.toml` (Forge) + jar manifest attribute `MixinConfigs: m2sfix.mixins.json`
  (Forge/NeoForge read this attribute).
- Compile deps: `org.spongepowered:mixin:0.8.7` (provided) and
  `org.lwjgl:lwjgl-freetype:3.4.3` (provided) from Maven Central — **no Minecraft jar needed**, we
  only touch LWJGL. Do not use Loom/Gradle.

## Tests (this is how the project is "verified" without Windows/Minecraft)

`core` unit tests (JUnit 5):
- Fixtures in `core/src/test/resources/fonts/`, generated by `tools/make_fixture_fonts.py` and
  **committed**: `clean.ttf`, `malicious-idef.ttf`, `pushb-decoy.ttf` (byte 0x89 hidden inside a
  `PUSHB` payload — must be CLEAN), `nested-composite.ttf` (IDEF only inside a composite glyph).
- Also scan every `*.ttf`/`*.otf` under `/usr/share/fonts` when present ⇒ assert **0 suspicious**
  (real-world false-positive check; skip gracefully when the directory is absent).
- `FaceGuard` policy tests: enabled/off/block/hintless/unparsable/allowlist/`adjustLoadFlags`
  (0 → 2, already-hinted flags preserved), `clearForTests()`.

`agent` integration test (the "Java simulation of a live hook"):
- test-scope stubs with the **exact** descriptors:
  `org/lwjgl/util/freetype/FreeType` with `FT_New_Memory_Face(long, ByteBuffer, long,
  org/lwjgl/PointerBuffer)` + `FT_Load_Glyph(org/lwjgl/util/freetype/FT_Face, int, int)`, and
  `FT_Face` with `long address()`, `PointerBuffer` with `long get(int)`.
  Both stubs record the last call arguments in public static fields.
- test: install the agent on the running test JVM (byte-buddy-agent, `-javaagent` in surefire
  `argLine` is fine), then:
  1. malicious font ⇒ `FT_New_Memory_Face` then `FT_Load_Glyph(face, 0, 0)` ⇒ assert the stub saw
     `load_flags == 2`.
  2. clean font ⇒ assert `load_flags == 0` (unchanged).
  3. policy `block` ⇒ assert `FT_Load_Glyph` never reached the stub body and returned `0x02`.
  4. policy `off` ⇒ assert nothing changes.
- Tests must be runnable with `mvn -q test` on Linux, JDK 21+, no network beyond Maven Central.

## Deliverables / definition of done

1. `mvn -q clean package` succeeds and produces
   `agent/target/m2sfix-agent-<ver>.jar` (shaded, `Premain-Class` present) and
   `mod/target/m2sfix-mod-<ver>.jar` (contains `fabric.mod.json`, `META-INF/mods.toml`,
   `m2sfix.mixins.json`, and the mixin class).
2. `mvn -q test` green, including the agent stub simulation.
3. No `.class` file in either jar references `net.bytebuddy` unrelocated (agent) and the mod jar has
   no Minecraft classes inside.
4. `git status` clean of stray files (no `target/`, no temp dirs) — respect `.gitignore`.
5. Do NOT write/replace `README.md` (maintainer owns it). Write `docs/IMPLEMENTATION-NOTES.md`
   instead: what was implemented, what was assumed, what was NOT verified (no live Minecraft, no
   Windows), and the exact commands used to verify.

Rules: no reflection-free assumptions about Minecraft internals beyond the two verified descriptors;
never let the guard throw; keep every file small and readable; no TODO placeholders.
