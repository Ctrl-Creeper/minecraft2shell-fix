"""Renders the Modrinth icon (512x512) and banner (1280x720) for mc2shell-fix.

    python3 tools/make_listing_images.py

Needs Pillow. Fonts are macOS system fonts; pass other paths with --latin/--latin-bold/--cjk.
"""
import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "modrinth" / "assets"

BG = (15, 17, 22)
CARD = (21, 25, 33)
EDGE = (40, 46, 58)
GREEN = (61, 220, 132)
SHIELD = (22, 46, 35)
LETTER = (232, 250, 240)
WHITE = (240, 244, 250)
GREY = (150, 160, 175)
DIM = (110, 118, 132)


def shield(draw, cx, top, w, h, scale_line):
    """Shield with a struck-through 'A', centred on cx."""
    pts = [(cx, top), (cx + w / 2, top + h * 0.2), (cx + w / 2, top + h * 0.6),
           (cx, top + h), (cx - w / 2, top + h * 0.6), (cx - w / 2, top + h * 0.2)]
    draw.polygon(pts, fill=SHIELD, outline=GREEN)
    return pts


def draw_shield_mark(img, cx, top, w, h, letter_font):
    d = ImageDraw.Draw(img)
    shield(d, cx, top, w, h, 1)
    box = d.textbbox((0, 0), "A", font=letter_font)
    lw, lh = box[2] - box[0], box[3] - box[1]
    d.text((cx - lw / 2 - box[0], top + h * 0.46 - lh / 2 - box[1]), "A", font=letter_font, fill=LETTER)
    d.line([(cx - w * 0.48, top + h * 0.95), (cx + w * 0.48, top + h * 0.02)], fill=GREEN,
           width=max(6, int(w * 0.045)))


def chip(d, x, y, text, font, active):
    box = d.textbbox((0, 0), text, font=font)
    tw, th = box[2] - box[0], box[3] - box[1]
    w, h = tw + 34, 46
    d.rounded_rectangle([x, y, x + w, y + h], radius=10,
                        fill=SHIELD if active else CARD, outline=GREEN if active else EDGE)
    d.text((x + 17 - box[0], y + (h - th) / 2 - box[1]), text, font=font,
           fill=GREEN if active else GREY)
    return x + w + 18


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--latin", default="/System/Library/Fonts/Supplemental/Arial.ttf")
    ap.add_argument("--latin-bold", default="/System/Library/Fonts/Supplemental/Arial Bold.ttf")
    ap.add_argument("--cjk", default="/System/Library/Fonts/Hiragino Sans GB.ttc")
    a = ap.parse_args()
    f = lambda path, size: ImageFont.truetype(path, size)

    # icon
    icon = Image.new("RGB", (512, 512), BG)
    d = ImageDraw.Draw(icon)
    d.rounded_rectangle([24, 24, 488, 488], radius=64, fill=CARD, outline=EDGE, width=3)
    draw_shield_mark(icon, 256, 80, 200, 250, f(a.latin_bold, 130))
    title = f(a.latin_bold, 58)
    box = d.textbbox((0, 0), "mc2shell-fix", font=title)
    d.text((256 - (box[2] - box[0]) / 2 - box[0], 372), "mc2shell-fix", font=title, fill=WHITE)
    icon.save(OUT / "icon-512.png", optimize=True)

    # banner
    banner = Image.new("RGB", (1280, 720), BG)
    d = ImageDraw.Draw(banner)
    draw_shield_mark(banner, 250, 100, 236, 308, f(a.latin_bold, 160))
    d.text((472, 150), "mc2shell-fix", font=f(a.latin_bold, 96), fill=WHITE)
    d.text((472, 282), "Blocks the resource-pack font attack on the client", font=f(a.latin, 32), fill=GREY)
    d.text((472, 330), "在客户端拦截 Minecraft 资源包字体攻击", font=f(a.cjk, 32), fill=GREY)
    x = 472
    chipf = f(a.latin_bold, 22)
    for text, active in (("1.20.5 → 1.21.11", True), ("Fabric · Forge · NeoForge", False),
                         ("client-side", False)):
        x = chip(d, x, 400, text, chipf, active)
    d.text((72, 560), "Every font is loaded without hinting, so a font's own bytecode never runs.",
           font=f(a.latin, 24), fill=DIM)
    d.text((72, 598), "所有字体都不执行 hinting，字体自带的程序永远不会运行。", font=f(a.cjk, 24), fill=DIM)
    banner.save(OUT / "banner-1280x720.png", optimize=True)
    print("wrote", OUT / "icon-512.png", "and", OUT / "banner-1280x720.png")


if __name__ == "__main__":
    main()
