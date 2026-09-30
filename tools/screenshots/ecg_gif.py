#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""
Makes docs/brand/ecg-on-watch.gif: a short loop of an ECG recording on the watch, for README and
posts. The frames are the published watch screenshots (docs/screenshots/wear/ecg, large watch), so
the GIF stays in step with the app. Run it after tools/screenshots/sync.py:

  python3 tools/screenshots/ecg_gif.py
"""
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parents[2]
SHOTS = ROOT / "docs" / "screenshots" / "wear" / "ecg"
OUT = ROOT / "docs" / "brand" / "ecg-on-watch.gif"

# (screen, how long it stays, in ms)
STEPS = [
    ("ecg_instruction", 1800),
    ("ecg_waiting", 1200),
    ("ecg_arming", 1200),
    ("ecg_measuring", 2200),
    ("ecg_analyzing", 1000),
    ("ecg_result_sinus", 2600),
]
FADE_FRAMES = 5
FADE_MS = 50


def round_face(image: Image.Image) -> Image.Image:
    """The screenshot inside a round watch face on black, as it looks on the watch."""
    size = image.size[0]
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize(image.size, Image.LANCZOS)
    face = Image.new("RGB", image.size, "black")
    face.paste(image, mask=mask)
    return face


def main() -> int:
    screens = [round_face(Image.open(SHOTS / f"{name}_large.png").convert("RGB")) for name, _ in STEPS]
    frames, durations = [], []
    for i, (screen, (_, hold)) in enumerate(zip(screens, STEPS)):
        frames.append(screen)
        durations.append(hold)
        following = screens[(i + 1) % len(screens)]
        for f in range(1, FADE_FRAMES + 1):
            frames.append(Image.blend(screen, following, f / (FADE_FRAMES + 1)))
            durations.append(FADE_MS)
    # One shared palette keeps the colours steady from frame to frame.
    strip = Image.new("RGB", (frames[0].width, frames[0].height * len(screens)))
    for i, screen in enumerate(screens):
        strip.paste(screen, (0, i * screen.height))
    palette = strip.quantize(colors=255, method=Image.MEDIANCUT)
    quantized = [frame.quantize(palette=palette, dither=Image.NONE) for frame in frames]
    quantized[0].save(
        OUT, save_all=True, append_images=quantized[1:], duration=durations, loop=0, optimize=True, disposal=1,
    )
    print(f"Wrote {OUT.relative_to(ROOT)} ({OUT.stat().st_size // 1024} KB, {len(frames)} frames)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
