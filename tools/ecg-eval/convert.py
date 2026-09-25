"""Converts public ECG databases into the evaluation format read by EcgDatasetReport.

Output directory:
    index.csv            id,group,label,file,beats
    <id>.f32             float32 little-endian samples, mV, 500 Hz
    <id>.beats           int32 little-endian reference QRS positions (samples at 500 Hz), if known

Groups and labels:
    cinc2017   N (normal) / A (AF) / O (other rhythm) / ~ (noisy)      single lead, 30–60 s
    mitdb      30 s segments of MLII: N (normal rhythm, no ectopy) / E (ectopic beats) /
               A (AF) / O (other rhythm), with reference beats
    nstdb      30 s segments of 118/119 + electrode-motion noise at 24…-6 dB: label = SNR, with beats

    python convert.py --cinc <dir with training2017/ and REFERENCE-v3.csv> --mitdb <dir> --nstdb <dir> --out <dir>
"""
import argparse
import csv
import os

import numpy as np
import wfdb
from scipy.signal import resample_poly

FS = 500
SEG_S = 30
ECTOPIC = set("VAaJSEFe")  # ventricular, atrial/nodal/supraventricular premature, fusion, escape
BEATS = set("NLRBAaJSVrFejnE/fQ")


def resample(x, fs):
    if fs == FS:
        return x.astype(np.float32)
    from fractions import Fraction
    f = Fraction(FS, int(round(fs))).limit_denominator(1000)
    return resample_poly(x, f.numerator, f.denominator).astype(np.float32)


def write(out, rows, rid, group, label, x, beats=None):
    x.astype("<f4").tofile(os.path.join(out, rid + ".f32"))
    bfile = ""
    if beats is not None:
        bfile = rid + ".beats"
        np.asarray(beats, dtype="<i4").tofile(os.path.join(out, bfile))
    rows.append([rid, group, label, rid + ".f32", bfile])


def cinc(src, out, rows):
    ref = dict(csv.reader(open(os.path.join(src, "REFERENCE-v3.csv"))))
    for rid, label in sorted(ref.items()):
        rec = wfdb.rdrecord(os.path.join(src, "training2017", rid))
        write(out, rows, "cinc_" + rid, "cinc2017", label, resample(rec.p_signal[:, 0], rec.fs))


def segments(rec, ann, fs):
    n = int(SEG_S * fs)
    rhythm_at = []
    current = "(N"
    for s, a, note in zip(ann.sample, ann.symbol, ann.aux_note):
        if a == "+" and note:
            current = note.strip("\x00")
        rhythm_at.append((s, current))
    for start in range(0, rec.sig_len - n + 1, n):
        end = start + n
        rhythms = {r for s, r in rhythm_at if start <= s < end}
        before = [r for s, r in rhythm_at if s < start]
        rhythms |= {before[-1]} if before else {"(N"}
        beats = [(s, a) for s, a in zip(ann.sample, ann.symbol) if start <= s < end and a in BEATS]
        yield start, end, rhythms, beats


def mitdb(src, out, rows):
    for rid in sorted({f[:-4] for f in os.listdir(src) if f.endswith(".hea")}):
        rec = wfdb.rdrecord(os.path.join(src, rid), channels=[0])
        ann = wfdb.rdann(os.path.join(src, rid), "atr")
        for start, end, rhythms, beats in segments(rec, ann, rec.fs):
            if rhythms == {"(N"}:
                label = "E" if any(a in ECTOPIC for _, a in beats) else "N"
            elif rhythms == {"(AFIB"}:
                label = "A"
            else:
                label = "O"
            x = resample(rec.p_signal[start:end, 0], rec.fs)
            b = [int(round((s - start) * FS / rec.fs)) for s, _ in beats]
            write(out, rows, f"mitdb_{rid}_{start}", "mitdb", label, x, b)


def nstdb(src, out, rows):
    for rid in sorted(f[:-4] for f in os.listdir(src) if f.endswith(".hea") and f[:3] in ("118", "119")):
        snr = rid[4:].replace("_", "-")
        rec = wfdb.rdrecord(os.path.join(src, rid), channels=[0])
        ann = wfdb.rdann(os.path.join(src, rid), "atr")
        n = int(SEG_S * rec.fs)
        # Noise is added in alternating 2-minute blocks from minute 5: keep segments fully inside noisy blocks.
        for start in range(int(300 * rec.fs), rec.sig_len - n + 1, n):
            block = int((start / rec.fs - 300) // 120)
            if block % 2 != 0 or int(((start + n - 1) / rec.fs - 300) // 120) != block:
                continue
            beats = [s for s, a in zip(ann.sample, ann.symbol) if start <= s < start + n and a in BEATS]
            x = resample(rec.p_signal[start:start + n, 0], rec.fs)
            write(out, rows, f"nstdb_{rid}_{start}", "nstdb", snr, x, [int(round((s - start) * FS / rec.fs)) for s in beats])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cinc")
    ap.add_argument("--mitdb")
    ap.add_argument("--nstdb")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    rows = []
    if a.cinc:
        cinc(a.cinc, a.out, rows)
    if a.mitdb:
        mitdb(a.mitdb, a.out, rows)
    if a.nstdb:
        nstdb(a.nstdb, a.out, rows)
    with open(os.path.join(a.out, "index.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["id", "group", "label", "file", "beats"])
        w.writerows(rows)
    from collections import Counter
    print(Counter((r[1], r[2]) for r in rows))


if __name__ == "__main__":
    main()
