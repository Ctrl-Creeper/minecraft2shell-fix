#!/usr/bin/env python3
"""Generate the binary TrueType fixtures used by the core unit tests.

Run with the fontTools virtualenv, from any directory:

    /home/user/.venvs/fonttools/bin/python tools/make_fixture_fonts.py

The generated fonts are tiny, valid sfnt files.  They are intentionally
committed so that `mvn test` does not need Python or network access.

Fixtures:
  clean.ttf                valid fpgm/prep, no IDEF
  clean-hinting-heavy.ttf  FDEF + SHZ + SHPIX + WS in composite and simple glyphs, no IDEF
  pushb-decoy.ttf          0x89 hidden in PUSHB / NPUSHB / NPUSHW payloads
  malicious-idef.ttf       IDEF (0x89) in fpgm
  malicious-idef-prep.ttf  IDEF only in prep
  malicious-idef-decoyed.ttf  0x89 in a push payload *and* a real IDEF after it
  malicious-idef-longloca.ttf IDEF in fpgm with a 32-bit loca table
  malicious-small-stack.ttf   IDEF in fpgm with a 1-glyph font (maxp edge case)
  nested-composite.ttf     IDEF only in a composite glyph's instruction stream
  simple-glyph-idef.ttf    IDEF only in a simple glyph's instruction stream
  mixed-collection.ttc     two faces: clean face first, malicious face second
  truncated.ttf            first 120 bytes of malicious-idef.ttf (must be UNPARSABLE)
"""

from __future__ import annotations

from array import array
from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont, newTable
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
    force_long_loca: bool = False,
    max_stack_elements: int | None = None,
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

    if force_long_loca:
        builder.font["head"].indexToLocFormat = 1
    if max_stack_elements is not None:
        builder.font["maxp"].maxStackElements = max_stack_elements

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


def _build_collection() -> None:
    """Two faces in one .ttc: the clean face first, the malicious one second.

    A scanner that only looks at face 0 of a collection would miss this.
    """
    from fontTools.ttLib import TTCollection

    clean_path = OUT_DIR / "clean.ttf"
    malicious_path = OUT_DIR / "malicious-idef.ttf"
    if not clean_path.exists():
        build_font(clean_path, fpgm=b"\xb0\x01", prep=b"\x36")
    if not malicious_path.exists():
        build_font(malicious_path, fpgm=b"\x36\x89\x00")

    collection = TTCollection()
    collection.fonts = [
        TTFont(str(clean_path), lazy=False),
        TTFont(str(malicious_path), lazy=False),
    ]
    # Give the two faces distinguishable names so the file is not obviously
    # two copies of the same font.
    for index, font in enumerate(collection.fonts):
        name = font["name"]
        name.setName(f"M2SFix Collection Face {index}", 1, 3, 1, 0x409)
        name.setName(f"M2SFix Collection Face {index}", 4, 3, 1, 0x409)
        name.setName(f"M2SFixCollection-Face{index}", 6, 3, 1, 0x409)
    collection.save(str(OUT_DIR / "mixed-collection.ttc"))


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

    # --- variants that must still be flagged ---------------------------------
    build_font(
        OUT_DIR / "malicious-idef-prep.ttf",
        prep=b"\x89\x00",
    )

    build_font(
        OUT_DIR / "malicious-idef-decoyed.ttf",
        # PUSHB[3] swallows four payload bytes (0x89 inside), then a real IDEF follows it.
        fpgm=b"\xb3\x89\x00\x11\x22\x89\x36",
    )

    build_font(
        OUT_DIR / "malicious-idef-longloca.ttf",
        fpgm=b"\x89\x36",
        glyph_programs={"A": b"\x89\x36"},
        force_long_loca=True,
    )

    build_font(
        OUT_DIR / "malicious-small-stack.ttf",
        fpgm=b"\x89\x36",
        max_stack_elements=1,
    )

    # --- a legitimate font that uses plenty of hinting, but no IDEF ----------
    build_font(
        OUT_DIR / "clean-hinting-heavy.ttf",
        # FDEF defining function 3, then SHZ/SHPIX/WS usage; no IDEF anywhere.
        fpgm=b"\xb0\x03\x2c\xb0\x01\x2d",
        glyph_programs={
            "A": b"\x36\xb0\x01\x38",
            "B": b"\x36\xb0\x01\x38\xb0\x02\x71",
        },
        composite_b=True,
    )

    # --- edge-case containers ------------------------------------------------
    _build_collection()

    truncated = (OUT_DIR / "malicious-idef.ttf").read_bytes()[:120]
    (OUT_DIR / "truncated.ttf").write_bytes(truncated)

    for path in sorted(OUT_DIR.glob("*")):
        if path.suffix in {".ttf", ".ttc"}:
            print(f"wrote {path.relative_to(REPO_ROOT)} ({path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
