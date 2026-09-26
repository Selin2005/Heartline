# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Shared preprocessing, mirroring the app (PpgFeatures / PapageiEmbedder on the phone).

Watch green PPG is 100 Hz and usually upside down (raw light intensity). PaPaGei expects
upright PPG, band-passed, at 125 Hz, in 10 s segments, z-scored.
"""
import numpy as np
from scipy.signal import butter, filtfilt, resample_poly

FS_WATCH = 100
FS_PAPAGEI = 125
SEGMENT_S = 10


def band_pass(x, fs, lo=0.5, hi=12.0, order=2):
    b, a = butter(order, [lo / (fs / 2), hi / (fs / 2)], btype="band")
    return filtfilt(b, a, x)


def is_inverted(x):
    """Arterial pulses rise fast and fall slowly: steepest slopes negative means upside down."""
    d = np.sort(np.diff(x))
    k = max(1, int(len(d) * 0.02))
    return -d[:k].mean() > d[-k:].mean() * 1.15


def upright(x, fs=FS_WATCH):
    y = band_pass(np.asarray(x, dtype=np.float64), fs)
    return -y if is_inverted(y) else y


def papagei_segments(ppg, fs=FS_WATCH):
    """(n_segments, 1250) float32: upright, 50 ms smoothed, 125 Hz, z-scored 10 s windows."""
    y = upright(ppg, fs)
    w = max(1, int(0.05 * fs))
    y = np.convolve(y, np.ones(w) / w, mode="same")
    y = resample_poly(y, FS_PAPAGEI, fs)
    n = FS_PAPAGEI * SEGMENT_S
    segs = [y[i:i + n] for i in range(0, len(y) - n + 1, n)]
    out = []
    for s in segs:
        sd = s.std()
        out.append((s - s.mean()) / (sd if sd > 1e-9 else 1.0))
    return np.asarray(out, dtype=np.float32)
