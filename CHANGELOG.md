# Changelog

## 2.1.0 — 2026-11-24

**Version coverage.** Checked every Minecraft version against Piston meta and the real native
libraries. 26.3/26.4 snapshots bundle FreeType 2.14.3 (LWJGL 3.4.3), 26.1–26.2 bundle 2.14.1
(3.4.1), 1.20.5–1.21.10 bundle 2.13.2 (3.3.3) — all older than the 2.15.0 fix — while 1.20.4 and
earlier never call FreeType at all. The mod now declares `minecraft >=1.20.5` instead of `>=26.3`;
`javap` on the 1.20.6 and 1.21.8 client jars confirms the same three call sites.

**Sharpness comparison.** New `docs/images/hinting-comparison.png`, rendered by the real FreeType
2.14.3 build at 9/16/32 px plus CJK, embedded in the README so people can see the cost before
installing. `tools/RenderComparison.java` generates it (it asserts per-row ink and sizes the canvas
from the scaled images — the first version silently clipped the vanilla row at 9 px).

## 2.0.0 — 2026-11-24

**The safe channel is now the default** (`m2sfix.policy=safe`). Every font loads with
`FT_LOAD_NO_HINTING`, so `tt_loader_init` never builds an interpreter context and no font program
runs. No signature has to match, so unpublished variants are covered too.

**Warnings only for high-confidence hits.** The `IDEF` scan now decides whether the player is
warned, not whether the font is neutralised: a false positive costs a dialog instead of a rendering
change. `m2sfix.policy=detect` restores the old signature-only behaviour for people who prefer
sharpness.

**Measured cost** (`tools/CompareHinting`, DejaVu Sans 32 px, 300 glyphs): 72.0% of bitmaps differ,
mean height difference 0.88 px, worst case 2 px. 65 tests green.

## 1.2.0 — 2026-11-24

**Signature refinement.** Sampling the Google Fonts repository (1,309 files → 2,351 faces) found two
false positives — `KoPubBatang` and `NanumMyeongjo`, legitimate Korean faces that define custom
instructions for `0x00-0x04` and `0x93`. Flagging any `IDEF` was too blunt; the signature is now
`IDEF` redefining a **point-zone** instruction, which is what the exploit needs. `IDEF` inside a
glyph program stays suspicious (it is illegal there); any other `IDEF` stays clean.

Also fixed how the definition number is read: FreeType takes it from the stack (`args[0]`), not from
the byte after `IDEF` — the exploit is literally `PUSHB 54; IDEF`.

Measured after the change: PoC pack 144/144 flagged, Google Fonts sample 2,351 faces clean, installed
fonts 460 faces clean, 60 tests green.

## 1.1.0 — 2026-11-24

**No `{command}` line.** The published exploit assembles its command inside the font's bytecode:
`calc.exe` and `WinExec` appear in none of the 144 shipped fonts in ASCII, UTF-16LE or as 4-byte
little-endian dwords. Anything shown there would be a guess, so the line was removed.

**Variant tests.** New fixtures (IDEF in `prep`, IDEF after a decoy push payload, 32-bit `loca`, tiny
`maxp`, hostile second face in a `.ttc`, truncated font, hinting-heavy but clean font) asserted in
both directions, plus every font installed on the build machine. Two real problems found this way:
a mis-encoded fixture, and a hard-coded 64 MiB scan cap that no test could reach (now
`m2sfix.maxscan`, failing closed).

**Disclaimer** added in both languages.

## 1.0.0 — 2026-11-24

First release.

- `core`: TrueType table/instruction scanner (IDEF detection), policy, guard, CLI
- `agent`: javaagent with a raw-ASM transformer (ByteBuddy `Advice` silently skips methods with
  `ByteBuffer` parameters)
- `mod`: one Mixin jar carrying both `fabric.mod.json` and `mods.toml`

Verified in Java only: 40 tests, 144/144 published PoC fonts flagged, 460 system fonts with no false
positives.
