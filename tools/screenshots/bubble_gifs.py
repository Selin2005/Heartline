#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""
Turns the particle-globe frames into one looping GIF per metric and theme, to review the motion.
The frames are recorded by BubbleScreenshotTest.frames, which only runs when asked:

  HEARTLINE_BUBBLE_FRAMES=1 ./gradlew :bubbles:recordPaparazziDebug --tests '*BubbleScreenshotTest.frames'
  python3 tools/screenshots/bubble_gifs.py OUT_DIR [--keep]

The frames are deleted afterwards (they are not goldens) unless --keep is given.
"""
import collections
import pathlib
import re
import sys

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
FRAMES = ROOT / "bubbles/src/test/snapshots/images"
PATTERN = re.compile(r"_frame_(phonelight|phone|light|globe)_([a-z0-9_]+)_(\d{4})\.png$")
FPS = 15
SIZE = 360


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out = pathlib.Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    runs = collections.defaultdict(list)
    for path in FRAMES.glob("*_frame_*.png"):
        m = PATTERN.search(path.name)
        if m:
            runs[(m.group(1), m.group(2))].append((int(m.group(3)), path))
    if not runs:
        sys.exit("No frames: record them first (see the top of this file).")
    for (theme, metric), frames in sorted(runs.items()):
        images = [Image.open(p).convert("RGB").resize((SIZE, SIZE), Image.LANCZOS) for _, p in sorted(frames)]
        target = out / f"bubble_{metric}_{theme}.gif"
        images[0].save(target, save_all=True, append_images=images[1:], duration=1000 // FPS, loop=0, optimize=True)
        print(f"{target} ({len(images)} frames)")
    if "--keep" not in sys.argv:
        for frames in runs.values():
            for _, p in frames:
                p.unlink()


if __name__ == "__main__":
    main()
