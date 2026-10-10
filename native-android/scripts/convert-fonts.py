#!/usr/bin/env python3
"""Convert the web app's bundled @fontsource-variable woff2 files into TTF font resources.

Android font resources cannot load woff2, so the same typefaces the web app uses are
decompressed once and committed under app/src/main/res/font/.

    python3 -m venv .fontvenv && .fontvenv/bin/pip install fonttools==4.55.3 brotli==1.1.0
    .fontvenv/bin/python scripts/convert-fonts.py
"""
from pathlib import Path

from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "node_modules" / "@fontsource-variable"
OUT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res" / "font"

FONTS = {
    "literata_variable.ttf": "literata/files/literata-latin-wght-normal.woff2",
    "literata_italic_variable.ttf": "literata/files/literata-latin-wght-italic.woff2",
    "fraunces_variable.ttf": "fraunces/files/fraunces-latin-wght-normal.woff2",
    "source_serif_variable.ttf": "source-serif-4/files/source-serif-4-latin-wght-normal.woff2",
    "atkinson_variable.ttf": "atkinson-hyperlegible-next/files/atkinson-hyperlegible-next-latin-wght-normal.woff2",
}

OUT.mkdir(parents=True, exist_ok=True)
for name, rel in FONTS.items():
    font = TTFont(SRC / rel)
    font.flavor = None
    font.save(OUT / name)
    print(f"{rel} -> res/font/{name} ({(OUT / name).stat().st_size} bytes)")
