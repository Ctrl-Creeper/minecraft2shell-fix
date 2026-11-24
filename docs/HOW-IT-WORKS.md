# How it works (technical)

This is the technical companion to the README. The README is written for players; everything a mod
author or anyone auditing the mitigation needs is here.

> **Status: not verified against a live Minecraft client.** See [Verification](#verification) for
> exactly what was run.

## 1. The bug

Minecraft 26.3 renders `ttf` fonts through FreeType **2.14.3**, bundled in LWJGL **3.4.3**. A
resource pack font with a nested composite glyph can make the `SHZ` instruction move points past the
physically allocated point buffer; in `src/truetype/ttinterp.c` the limit is taken from the zone's
last contour instead of the allocation, so `Move_Zp2_Point` writes out of bounds. The font's own
bytecode then uses `IDEF` (redefining an existing opcode, `SHZ`/`0x36`) to regain control after
FreeType's error dispatch, derives the module base from a live callback pointer, and pivots into a ROP
chain on the TrueType VM stack.

Published proof of concept: [`bikini/minecraft2shell`](https://github.com/bikini/minecraft2shell)
(Windows x64, Minecraft Java 26.3, that specific FreeType build).

Upstream FreeType fixed the `Ins_SHZ` range limit in April 2026 (Mozilla bug 2032043) and shipped it
in **2.15.0** (2026-10-12). Minecraft still bundles 2.14.3, so this project is a stopgap.

## 2. Where the mitigation hooks

Native code cannot be patched from Java, so the chain is cut one layer above, at the two Java entry
points Minecraft actually uses (verified with `javap` on the Mojang-signed 26.3 client jar):

| Minecraft call site | LWJGL method | what the hook does |
| --- | --- | --- |
| `net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition.load(ResourceManager)` | `FreeType.FT_New_Memory_Face(long, ByteBuffer, long, PointerBuffer)` | lint the font bytes once, remember the verdict for that `FT_Face` address |
| `com.mojang.blaze3d.font.TrueTypeGlyphProvider.loadGlyph(int, FT_Face, int)` | `FreeType.FT_Load_Glyph(FT_Face, int, int)` | add `FT_LOAD_NO_HINTING` (0x2) **only for flagged faces** |

Every glyph-program execution path in `ttgload.c` is gated by `IS_HINTED(load_flags)` — the simple
glyph path, the composite path, the recursive component path. A face loaded with `FT_LOAD_NO_HINTING`
therefore never runs the interpreter, so there is no `SHZ` execution and no `IDEF` recovery program.
Unflagged faces keep the caller's flags untouched: their rendering is bit-for-bit identical.

### 2.1 The agent

`FixTransformer` is a plain `ClassFileTransformer` with a small ASM visitor. It splices exactly two
straight-line sequences, so existing stack-map frames stay valid and only `maxStack`/`maxLocals` are
recomputed:

```
FT_Load_Glyph(face, index, flags):            // method entry
    flags = AgentBridge.adjustFlags(face, flags)

FT_New_Memory_Face(lib, file, size, aface):   // before every IRETURN
    return AgentBridge.afterNewFace(file, size, aface, result)
```

`AgentBridge` is the only class that touches `FaceGuard`; it resolves the face address and the
`PointerBuffer` entry by reflection, so the injected code never links against LWJGL types. Any
failure inside the bridge returns the caller's original value: a broken guard must never break a
client.

ByteBuddy `Advice` was tried first and abandoned: in 1.14.19 it silently skips methods that have a
`ByteBuffer` parameter (no weaving, no error), and `@Advice.OnMethodEnter(skipOn = …)` silently does
nothing. See `docs/IMPLEMENTATION-NOTES.md` for the bisect.

### 2.2 The mod

`FreeTypeTargetMixin` uses `@Mixin(targets = "org.lwjgl.util.freetype.FreeType", remap = false)`:
`@Inject(at = @At("RETURN"))` on `FT_New_Memory_Face` and `@ModifyVariable(index = 2)` on
`FT_Load_Glyph`. Because the target is a library class there is nothing to remap, so no refmap and no
Minecraft compile dependency; the same jar carries `fabric.mod.json`, `META-INF/mods.toml` and the
`MixinConfigs` manifest attribute.

### 2.3 A trap worth knowing

LWJGL's four-argument `FT_New_Memory_Face(long, ByteBuffer, long, PointerBuffer)` does **not** treat
its `long` as a size. Its bytecode passes `buffer.remaining()` as the size and the declared parameter
as `face_index` (`nFT_New_Memory_Face(lib, base, remaining, declared, aface)`). Passing
`bytes.length` there yields `FT_Err_Invalid_Argument (6)`, and any scanner reading the size from that
slot would inspect zero bytes and never flag anything. Both the agent and the mod derive the length
from `ByteBuffer.remaining()`.

## 3. The scanner

`core/TtfScanner` is a defensive sfnt parser with no dependencies:

- sfnt versions `0x00010000`, `true`, `OTTO`, `typ1`, plus `ttcf` collections (every face is scanned).
- Reads the table directory, then walks the instruction streams of `fpgm`, `prep` and every glyph
  (`loca`/`head`/`maxp`/`glyf`, both `indexToLocFormat` widths, simple and composite glyphs).
- The instruction decoder handles the variable-length pushes (`PUSHB`/`PUSHW`/`NPUSHB`/`NPUSHW`) and
  treats `FDEF`/`IDEF` as 2-byte instructions, exactly like FreeType's `Ins_FDEF`. Payload bytes are
  never interpreted as opcodes — there is a regression fixture for that (`pushb-decoy.ttf`).
- Verdict: any `IDEF` ⇒ `SUSPICIOUS`; no `IDEF` ⇒ `CLEAN` (with an `SHZ` count for diagnostics);
  unusable structure ⇒ `UNPARSABLE` (treated as suspicious by default).

`PayloadHint` extracts printable ASCII runs that look like a command (`.exe`, `powershell`,
`/bin/sh`, `http://`, …). It exists only to fill the `{command}` line in the warning dialog. The
published exploit builds its command in bytecode and stores no plaintext copy, so for that sample the
dialog says the command is not recoverable — the block still applies.

## 4. The warning dialog

`core/Notify` shows the vanilla `net.minecraft.client.gui.screens.ConfirmScreen`
(`(BooleanConsumer, Component title, Component message, Component yesButton, Component noButton)`):

```
檢測到伺服器的資源包疑似包含惡意內容（minecraft2shell），會在你的電腦執行指令：
{command}
已經攔截，指令沒有被執行。

This server's resource pack looks malicious (minecraft2shell). The command above was blocked and never ran.

[我了解，繼續遊玩 / Continue]   [中斷連線 / Disconnect]
```

- "Continue" → callback `accept(true)`, nothing happens.
- "Disconnect" → callback `accept(false)` → `Minecraft.disconnectWithSavingScreen()`.
- Everything is reflection (`Minecraft.getInstance()`, `Component.literal`, a `Proxy` for
  `it.unimi.dsi.fastutil.booleans.BooleanConsumer`, `setScreenAndShow`), because `core` must not link
  against Minecraft: the agent cannot, and the mod should not need a Minecraft dependency to compile.
- The screen is installed through `Minecraft.execute(Runnable)`, since font loading happens on a
  resource-reload worker thread.
- Degradation: no Minecraft classes → the same text is printed to the log. One dialog per game
  session, one warning per font hash; `-Dm2sfix.notify=false` disables it.
- Signatures used here were checked with `javap` on the 26.3 client jar; the dialog itself has **not**
  been seen in a real client.

## 5. Configuration

System properties (`-D...` on the JVM command line):

| property | default | meaning |
| --- | --- | --- |
| `m2sfix.enabled` | `true` | master switch |
| `m2sfix.policy` | `hintless` | `hintless` = keep the font, drop hinting · `block` = reject the face (`FT_Err_Invalid_File_Format`) · `off` = do nothing |
| `m2sfix.unparsable` | `hintless` | treatment of fonts the scanner cannot parse (`hintless` / `allow`) |
| `m2sfix.notify` | `true` | in-game warning dialog |
| `m2sfix.log` | `true` | one stderr line per flagged font |
| `m2sfix.sha256.allow` | *(empty)* | comma-separated SHA-256 of fonts to always treat as clean |

## 6. Verification

Run on Linux, JDK 26, against LWJGL 3.4.3 — the exact library build and FreeType version Minecraft
26.3 bundles.

```
mvn test                # 40 tests: 32 core (scanner, policy, guard, notify), 8 agent
tools/verify-poc-pack.sh
java -cp core/target/m2sfix-core-1.0.0.jar dev.duckducktw.m2sfix.core.cli.ScanFonts /usr/share/fonts
```

- **40 tests green.** The agent tests install the real transformer into a running JVM against stub
  classes with the exact LWJGL descriptors and assert: flagged font `flags 0 → 2`, clean font keeps
  `flags 0`, `block` rejects the face, `off` changes nothing, null/short buffers and failed face
  creation are ignored. The notify tests build the dialog against Minecraft stand-ins and check both
  buttons, the disconnect callback, the once-per-font rule and the `m2sfix.notify=false` switch.
- **Real proof-of-concept fonts:** the published `resource-pack.zip` (SHA-1
  `f2e2859318152e32fc331d2291c2ec40ffb3c372`) — **144 / 144 flagged** (`IDEF instruction in fpgm`).
- **False positives:** every font installed on this machine — **460 files, 0 flagged**.
- **Real FreeType 2.14.3, real PoC font, real agent jar:** the guard flagged the font, the rewritten
  flags were observed (`0 → 2`), glyphs rendered, process exited cleanly. The same harness without the
  agent also completes, i.e. it does not reproduce the Windows-only ROP chain — the exploit was never
  executed on purpose.

**Not verified:** a live Minecraft 26.3 client on Windows, the dialog on screen, the mod jar inside a
real Fabric/Forge loader, and the counterfactual ("without the fix it is exploited").

## 7. Limits

- Signature-based: it flags `IDEF`. A variant that reaches the same out-of-bounds write without
  `IDEF` would not be flagged; `m2sfix.policy=block` is the stricter stance.
- The real fix is upstream: FreeType ≥ 2.15.0, i.e. the Minecraft version that picks it up.
- Untested on macOS/ARM (same FreeType build, same treatment).
