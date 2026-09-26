#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""
Turns the watch card screenshots (CardScreenshotTest, recorded with ./gradlew :wear:recordPaparazziDebug)
into the picker preview images: card_preview_<name>_{small,large}.png and tile_preview_<name>.png (full
screen, for watches without the tile stack). Crops each card out of its black test frame and copies the
images to docs/screenshots/wear/cards/.
"""
import glob, os, re, sys
from PIL import Image

root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
shots = os.path.join(root, "wear/src/test/snapshots/images")
res = os.path.join(root, "wear/src/main/res/drawable-nodpi")
docs = os.path.join(root, "docs/screenshots/wear/cards")
os.makedirs(docs, exist_ok=True)
for path in sorted(glob.glob(os.path.join(shots, "com.heartline.wear_CardScreenshotTest_*.png"))):
    # com.heartline.wear_CardScreenshotTest_<test>_<card>_<size>.png (test names have no underscores).
    m = re.search(r"CardScreenshotTest_[A-Za-z0-9]+_(.+)_(small|large|full)\.png$", os.path.basename(path))
    name, size = m.group(1), m.group(2)
    img = Image.open(path).convert("RGBA")
    # The card is everything brighter than the black frame.
    bbox = img.convert("L").point(lambda v: 255 if v > 8 else 0).getbbox()
    card = img.crop(bbox)
    card.save(os.path.join(docs, f"{name}_{size}.png"))
    if name.endswith("empty"):
        continue
    target = f"tile_preview_{name}.png" if size == "full" else f"card_preview_{name}_{size}.png"
    card.save(os.path.join(res, target))
    print(target)
