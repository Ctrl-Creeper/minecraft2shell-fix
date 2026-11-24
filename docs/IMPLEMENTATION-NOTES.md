# Implementation notes

Written after the implementation, so it records what actually happened rather than what was planned.

## What is implemented

| module | contents |
| --- | --- |
| `core` | `TtfScanner` (sfnt/ttcf parser + TrueType instruction decoder looking for `IDEF`), `ScanResult`, `FixPolicy` (system properties), `FaceGuard` (per-face verdict cache + flag/block decisions), `PayloadHint` (command hint for the dialog), `Notify` (reflective `ConfirmScreen` warning), `cli.ScanFonts` (standalone scanner) |
| `agent` | `FixAgent` (premain), `FixTransformer` (raw ASM class-file transformer), `AgentBridge` (static entry points called from injected code). Produces a shaded jar with ASM relocated to `dev.duckducktw.m2sfix.shaded.asm` |
| `mod` | `FreeTypeTargetMixin` (Mixin, `@Inject` on `FT_New_Memory_Face`, `@ModifyVariable` on `FT_Load_Glyph`), `m2sfix.mixins.json`, `fabric.mod.json`, `META-INF/mods.toml`. Shaded with `core` |

## Deviations from the original plan and why

1. **The agent does not use ByteBuddy.** The first implementation used ByteBuddy `Advice`, and it
   silently failed to weave `FT_New_Memory_Face`. Bisecting with a standalone harness showed the
   pattern: any method with a `java.nio.ByteBuffer` parameter was skipped (a parameterless advice on
   a method with a `ByteBuffer` parameter produced an unchanged class file, verified with
   `-Dnet.bytebuddy.dump`), while methods without `ByteBuffer` wove fine. `Advice.OnMethodEnter`
   `skipOn` also silently did nothing. The agent therefore splices the two calls with raw ASM, which
   is deterministic: at most three instructions of straight-line code per method, no new branches,
   so existing stack-map frames stay valid and only `maxStack`/`maxLocals` are recomputed.
2. **`block` policy moved from `FT_Load_Glyph` to `FT_New_Memory_Face`.** Skipping the glyph call
   needed `skipOn`, which did not work (see above). Blocking is now done where it is both simpler
   and stronger: the face gets `FT_Err_Invalid_File_Format` and Minecraft cannot use it at all.
3. **The `file_size` argument of LWJGL's four-argument `FT_New_Memory_Face` is not a size.** Its
   bytecode passes `buffer.remaining()` as the size and the *declared* `file_size` parameter as
   `face_index` (see `javap -c` on `org.lwjgl.util.freetype.FreeType`). Feeding the declared value
   into the scanner would have meant scanning zero bytes and never flagging anything. The bridge and
   the mixin now derive the length from `ByteBuffer.remaining()`.
4. **One jar for Fabric and Forge** instead of two artifacts: the hook targets are library classes,
   so nothing needs remapping, and the two metadata sets coexist in one jar.

## Assumptions

- Minecraft 26.3 opens every font resource pack font through `FT_New_Memory_Face` and renders it
  through `FT_Load_Glyph`. Verified by `javap` on the Mojang-signed 26.3 client
  (`TrueTypeGlyphProviderDefinition.load` / `TrueTypeGlyphProvider.loadGlyph`).
- `FT_LOAD_NO_HINTING` (0x2) prevents glyph-program execution. Verified against FreeType's
  `ttgload.c`: every interpreter entry point is guarded by `IS_HINTED(load_flags)`.
- The exploit family depends on `IDEF` (it must redefine an existing opcode, here `SHZ`/0x36).
  Legitimate fonts do not use `IDEF`.

## The warning dialog

`Notify` builds the vanilla `ConfirmScreen` reflectively
(`(BooleanConsumer, Component, Component, Component, Component)`, plus a `Proxy` for the fastutil
`BooleanConsumer`, `Component.literal`, `Minecraft.getInstance()`, `setScreenAndShow`, scheduled via
`Minecraft.execute`). Two decisions worth recording:

- **No Minecraft dependency in `core`.** The agent cannot link Minecraft classes and the mod should not
  need them to compile, so the dialog is 100% reflection with a chat/log fallback.
- **Only one dialog per session, one warning per font hash.** A hostile pack ships 144 fonts; without
  this the player would get a modal dialog storm. `FaceGuard.seenOnce()` gates it, and the tests cover
  the "same font twice ⇒ no second dialog" case against Minecraft stand-ins.

The dialog does **not** display a command. An earlier version had a `PayloadHint` heuristic (printable
ASCII runs looking like a command) plus a `{command}` line; it was removed after checking the artifact
properly: `calc.exe` and `WinExec` appear in none of the 144 PoC fonts as ASCII, UTF-16LE, or as two
4-byte little-endian dwords, and `PayloadHint` found 0 of 144. The payload is assembled by the bytecode,
so any suggestion we printed would have been a guess. Showing nothing is the honest option.

## Variant testing

`tools/make_fixture_fonts.py` now generates the whole hostile/benign matrix (IDEF in `fpgm` / `prep` /
simple glyph / composite glyph / after a decoy push payload / 32-bit `loca` / tiny `maxp` / second face
of a `.ttc` / truncated, plus a hinting-heavy but clean font and push-payload-only decoys), and
`VariantDetectionTest` asserts both directions, including a scan of every font installed on the build
machine.

The matrix found two real problems:

1. The "decoy + real IDEF" fixture was mis-encoded — the second `IDEF` landed inside the `PUSHB`
   payload, so the scanner's CLEAN verdict was correct and the fixture was wrong. Fixed the fixture (and
   the test now proves the scanner skips payloads *and* still finds a later real `IDEF`).
2. The 64 MiB scan cap was a hard-coded constant that no test could reach. It is now
   `m2sfix.maxscan` (MiB), so the fail-closed path is exercised by shrinking the limit instead of
   allocating 64 MiB.

## What is NOT verified

- No live Minecraft client was launched (no Windows environment here); neither the agent nor the
  mod jar has been loaded by a real game or a real Fabric/Forge loader.
- The counterfactual ("without the fix the crafted font hijacks the client") was **not** run: that
  would mean executing an untrusted exploit locally.
- The Forge metadata (`mods.toml` + `MixinConfigs`) is untested; if a loader rejects a mod without
  a `@Mod` class, the javaagent path is the fallback.
- macOS/ARM behaviour is untested.

## Exact verification commands

```bash
mvn -Dmaven.repo.local=$PWD/.m2repo clean package
# -> core 32 tests, agent 8 tests, BUILD SUCCESS
# -> agent/target/m2sfix-agent-1.0.0.jar (160 KB, Premain-Class, no net.bytebuddy left, 40 shaded ASM classes)
# -> mod/target/m2sfix-mod-1.0.0.jar (30 KB, fabric.mod.json + mods.toml + m2sfix.mixins.json + mixin + core)

tools/verify-poc-pack.sh
# -> sha1 f2e2859318152e32fc331d2291c2ec40ffb3c372
# -> scanned=144 flagged=144 {SUSPICIOUS=144}

java -cp core/target/m2sfix-core-1.0.0.jar dev.duckducktw.m2sfix.core.cli.ScanFonts /usr/share/fonts
# -> scanned=460 flagged=0 {CLEAN=460}
```

Real-bindings smoke test (ad-hoc harness, not committed): LWJGL 3.4.3 + `lwjgl-freetype` 3.4.3
natives, `FT_Init_FreeType` → `FT_New_Memory_Face` → `FT_Load_Glyph` on

- `DejaVuSans.ttf` without the agent: glyph bitmaps `4x23`, `9x9`, `23x23`, `16x30`;
- the same font **with** the agent: identical bitmaps (no behaviour change for clean fonts);
- `deferred-000.ttf` from the PoC pack **with** the agent:
  `[m2sfix] flagged font 11500c6f… (SUSPICIOUS): IDEF instruction in fpgm (1 occurrence)`, then
  `FT_Load_Glyph` observed with `flags 0 → 2` and glyphs rendered without a crash.

Note: LWJGL's four-argument `FT_New_Memory_Face` takes the **face index** in its `file_size`
parameter — the harness must pass `0L` there. That is the same trap documented in "Deviations" #3.
