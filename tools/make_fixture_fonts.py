#!/usr/bin/env python3
"""Generate the binary TrueType fixtures used by the core unit tests.

Run with the fontTools virtualenv, from any directory:

    /home/user/.venvs/fonttools/bin/python tools/make_fixture_fonts.py

The generated fonts are tiny, valid sfnt files.  They are intentionally
committed so that `mvn test` does not need Python or network access.

Fixtures:
  clean.ttf              valid fpgm/prep, no IDEF
  malicious-idef.ttf     IDEF (0x89) in fpgm
  pushb-decoy.ttf        0x89 hidden in PUSHB / NPUSHB / NPUSHW payloads
  nested-composite.ttf   IDEF only in a composite glyph's instruction stream
  simple-glyph-idef.ttf  IDEF only in a simple glyph's instruction stream
"""

from __future__ import annotations

from array import array
from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import newTable
from fontTools.ttLib.tables._g_l_y_f import Glyph, GlyphComponent
from fontTools.ttLib.tables.ttProgram import Program

REPO_ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = REPO_ROOT / "core" / "src" / "test" / "resources" / "fonts"


def _program(bytecode: bytes) -> Program:
    program = Program()
    program.fromBytecode(bytes(bytecode))
    return program


def _base_font(composite_b: bool) -> tuple[FontBuilder, dict[str, Glyph]]:
    builder = FontBuilder(1000, isTTF=True)
    builder.setupGlyphOrder([".notdef", "space", "A", "B"])
    builder.setupCharacterMap({0x20: "space", 0x41: "A", 0x42: "B"})

    pen = TTGlyphPen(None)
    pen.moveTo((100, 0))
    pen.lineTo((500, 700))
    pen.lineTo((900, 0))
    pen.closePath()
    glyph_a = pen.glyph()

    if composite_b:
        glyph_b = Glyph()
        glyph_b.numberOfContours = -1
        component = GlyphComponent()
        component.glyphName = "A"
        component.x = 0
        component.y = 0
        component.flags = 0x0004  # ARGS_ARE_XY_VALUES
        glyph_b.components = [component]
    else:
        glyph_b = TTGlyphPen(None).glyph()

    glyphs: dict[str, Glyph] = {
        ".notdef": TTGlyphPen(None).glyph(),
        "space": TTGlyphPen(None).glyph(),
        "A": glyph_a,
        "B": glyph_b,
    }
    return builder, glyphs


def build_font(
    path: Path,
    *,
    fpgm: bytes = b"",
    prep: bytes = b"",
    glyph_programs: dict[str, bytes] | None = None,
    composite_b: bool = False,
) -> None:
    builder, glyphs = _base_font(composite_b)
    for name, code in (glyph_programs or {}).items():
        glyphs[name].program = _program(code)

    builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics(
        {".notdef": (500, 0), "space": (500, 0), "A": (1000, 0), "B": (1000, 0)}
    )
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable(
        {
            "familyName": "M2SFix Fixture",
            "styleName": "Regular",
            "uniqueFontIdentifier": "M2SFix Fixture Regular 1.0",
            "fullName": "M2SFix Fixture Regular",
            "psName": "M2SFixFixture-Regular",
            "version": "Version 1.0",
        }
    )
    builder.setupOS2()
    builder.setupPost()
    builder.setupMaxp()

    for tag, code in (("fpgm", fpgm), ("prep", prep)):
        table = newTable(tag)
        table.program = _program(code)
        builder.font[tag] = table

    cvt = newTable("cvt ")
    cvt.values = array("h", [0, 1, 2, 3])
    builder.font["cvt "] = cvt

    # Make the checked-in binaries byte-for-byte reproducible.
    builder.font["head"].created = 2082844800  # 1970-01-01, font epoch
    builder.font["head"].modified = 2082844800
    builder.font.recalcTimestamp = False

    path.parent.mkdir(parents=True, exist_ok=True)
    builder.save(path)


def main() -> None:
    build_font(
        OUT_DIR / "clean.ttf",
        fpgm=b"\xb0\x01",  # PUSHB[0] with one harmless payload byte
        prep=b"\x36",      # SHZ, diagnostics only
    )

    build_font(
        OUT_DIR / "malicious-idef.ttf",
        fpgm=b"\x36\x89\x00",  # SHZ, then IDEF 0 (the exploit primitive)
    )

    build_font(
        OUT_DIR / "pushb-decoy.ttf",
        # PUSHB[7] takes 8 payload bytes; 0x89 sits inside that payload.
        fpgm=b"\xb7\x01\x02\x03\x04\x05\x89\x00\x06",
        # NPUSHB (2 bytes) and NPUSHW (1 little-endian word) also contain 0x89.
        prep=b"\x40\x02\x89\x00\x41\x01\x00\x89",
    )

    build_font(
        OUT_DIR / "nested-composite.ttf",
        glyph_programs={"B": b"\x89\x00"},
        composite_b=True,
    )

    build_font(
        OUT_DIR / "simple-glyph-idef.ttf",
        glyph_programs={"A": b"\x89\x00"},
    )

    for path in sorted(OUT_DIR.glob("*.ttf")):
        print(f"wrote {path.relative_to(REPO_ROOT)} ({path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
