"""
Generates pixel-art 32x32 schematic-symbol PNGs for the built-in CircuitsLib elements
into display/src/main/resources/texture/.

White, opaque pixels on a fully-transparent background. Tint at draw time via
SpriteBatch.setColor(...).

Symbols rendered:
  - resistor.png   IEEE zigzag
  - battery.png    long bar + short bar (EMF)
  - capacitor.png  two parallel plates
  - diode.png      triangle into a cathode bar
  - wire.png       single straight horizontal line
  - junction.png   filled circle node
  - bjt.png        NPN transistor in a circle, three leads, emitter arrow

Run from project root:
  python3 display/scripts/generate_textures.py
"""

from __future__ import annotations

import os
from PIL import Image, ImageDraw

SIZE = 32
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)

THIS_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.normpath(os.path.join(THIS_DIR, "..", "src", "main", "resources", "texture"))


def new_canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (SIZE, SIZE), CLEAR)
    return img, ImageDraw.Draw(img)


def hline(d: ImageDraw.ImageDraw, x0: int, x1: int, y: int) -> None:
    """2-pixel-thick horizontal line for chunky readability at 32x32."""
    d.line([(x0, y), (x1, y)], fill=WHITE)
    d.line([(x0, y + 1), (x1, y + 1)], fill=WHITE)


def vline(d: ImageDraw.ImageDraw, x: int, y0: int, y1: int) -> None:
    """2-pixel-thick vertical line."""
    d.line([(x, y0), (x, y1)], fill=WHITE)
    d.line([(x + 1, y0), (x + 1, y1)], fill=WHITE)


def save(img: Image.Image, name: str) -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, name)
    img.save(path)
    print(f"  wrote {path}")


# --- wire ---
def make_wire():
    img, d = new_canvas()
    hline(d, 0, 31, 15)
    save(img, "wire.png")


# --- junction (filled circle node) ---
def make_junction():
    img, d = new_canvas()
    d.ellipse([(11, 11), (20, 20)], fill=WHITE)
    save(img, "junction.png")


# --- resistor (IEEE zigzag, leads on horizontal) ---
def make_resistor():
    img, d = new_canvas()
    hline(d, 0, 7, 15)
    hline(d, 24, 31, 15)
    pts = [(7, 15), (10, 10), (13, 21), (16, 10), (19, 21), (22, 10), (24, 15)]
    d.line(pts, fill=WHITE, width=2)
    save(img, "resistor.png")


# --- battery (long + short vertical bars) ---
def make_battery():
    img, d = new_canvas()
    hline(d, 0, 13, 15)
    hline(d, 18, 31, 15)
    vline(d, 14, 8, 23)
    vline(d, 18, 12, 19)
    save(img, "battery.png")


# --- capacitor (two parallel plates) ---
def make_capacitor():
    img, d = new_canvas()
    hline(d, 0, 13, 15)
    hline(d, 18, 31, 15)
    vline(d, 14, 8, 23)
    vline(d, 17, 8, 23)
    save(img, "capacitor.png")


# --- diode (triangle into cathode bar) ---
def make_diode():
    img, d = new_canvas()
    hline(d, 0, 11, 15)
    hline(d, 22, 31, 15)
    d.polygon([(12, 8), (12, 23), (22, 16)], fill=WHITE)
    vline(d, 22, 8, 23)
    save(img, "diode.png")


# --- BJT (NPN in circle, three leads, emitter arrow) ---
def make_bjt():
    img, d = new_canvas()
    # circle outline
    d.ellipse([(4, 4), (28, 28)], outline=WHITE, width=2)
    # base lead (left, horizontal into the base bar)
    hline(d, 0, 11, 15)
    # vertical base bar inside the circle
    vline(d, 12, 9, 22)
    # collector lead (top-right diagonal to top edge)
    d.line([(14, 14), (22, 6)], fill=WHITE, width=2)
    d.line([(22, 6), (22, 0)], fill=WHITE, width=2)
    # emitter lead (bottom-right diagonal with arrowhead pointing OUT for NPN)
    d.line([(14, 18), (22, 26)], fill=WHITE, width=2)
    d.line([(22, 26), (22, 31)], fill=WHITE, width=2)
    # arrowhead on emitter (small filled triangle pointing along the diagonal, away from base)
    d.polygon([(20, 22), (23, 22), (22, 26)], fill=WHITE)
    save(img, "bjt.png")


def main() -> None:
    print(f"Generating textures into {OUT_DIR}")
    make_wire()
    make_junction()
    make_resistor()
    make_battery()
    make_capacitor()
    make_diode()
    make_bjt()
    print("Done.")


if __name__ == "__main__":
    main()
