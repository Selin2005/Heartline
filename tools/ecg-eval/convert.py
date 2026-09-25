"""Converts public ECG databases into the evaluation format read by EcgDatasetReport.

Output directory:
    index.csv            id,group,label,file,beats
    <id>.f32             float32 little-endian samples, mV, 500 Hz
    <id>.beats           int32 little-endian reference QRS positions (samples at 500 Hz), if known

Groups and labels:
    cinc2017   N (normal) / A (AF) / O (other rhythm) / ~ (noisy)      single lead, 30–60 s
    mitdb      30 s segments of MLII: N (normal rhythm, no ectopy) / E (ectopic beats) /
               A (AF) / O (other rhythm) / X (AF for part of the segment), with reference beats
    afdb       sampled 30 s segments of the MIT-BIH AF Database (channel 1): N / A / O / X, beats from .qrs
    cpsc2021   sampled 30 s segments of CPSC 2021 lead I: N / E / A / O / X, with beats
    nstdb      30 s segments of 118/119 + electrode-motion noise at 24…-6 dB: label = SNR, with beats

    python convert.py --cinc <dir with training2017/ and REFERENCE-v3.csv> --mitdb <dir> --nstdb <dir> \
        --afdb <dir> --cpsc <dir with all Training_set_* records> --out <dir>
"""
import argparse
import csv
import os
import zlib

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
            label = label_of(rhythms, beats)
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


def label_of(rhythms, beats):
    if rhythms == {"(N"}:
        return "E" if any(a in ECTOPIC for _, a in beats) else "N"
    if rhythms == {"(AFIB"}:
        return "A"
    # AFib for part of the segment only: ambiguous as a 30 s label, kept out of training (reported apart).
    return "X" if "(AFIB" in rhythms else "O"


def sampled(rec, ann, fs, rid, per_label, beat_ann=None):
    """Up to per_label 30 s segments of each label, spread over the record (deterministic)."""
    by_label = {}
    for start, end, rhythms, beats in segments(rec, ann, fs):
        if beat_ann is not None:
            beats = [(s, "N") for s in beat_ann.sample if start <= s < end]
        by_label.setdefault(label_of(rhythms, beats), []).append((start, end, beats))
    rng = np.random.default_rng(zlib.crc32(rid.encode()))
    for label, segs in sorted(by_label.items()):
        pick = sorted(rng.choice(len(segs), size=min(per_label, len(segs)), replace=False))
        for i in pick:
            yield label, segs[i]


def afdb(src, out, rows, per_label=25):
    """MIT-BIH AF Database: 23 ten-hour ambulatory recordings (250 Hz), channel 1; beats from the .qrs file."""
    for rid in sorted({f[:-4] for f in os.listdir(src) if f.endswith(".dat")}):
        rec = wfdb.rdrecord(os.path.join(src, rid), channels=[0])
        ann = wfdb.rdann(os.path.join(src, rid), "atr")
        qrs = wfdb.rdann(os.path.join(src, rid), "qrs")
        for label, (start, end, beats) in sampled(rec, ann, rec.fs, rid, per_label, qrs):
            if label == "E":
                label = "N"  # the .qrs file doesn't label beats: ectopy can't be told apart here
            x = resample(rec.p_signal[start:end, 0], rec.fs)
            b = [int(round((s - start) * FS / rec.fs)) for s, _ in beats]
            write(out, rows, f"afdb_{rid}_{start}", "afdb", label, x, b)


def cpsc(src, out, rows, per_label=3):
    """CPSC 2021: Holter lead I (as on the watch), 200 Hz, rhythm and beat annotations; patient = data_<p>_<n>.
    src holds the .hea/.atr files; signal is read from a local .dat or streamed (only the sampled segments) from PhysioNet."""
    import time
    part = {}
    if os.path.exists(os.path.join(src, "RECORDS")):
        part = {r.split("/")[1]: r.split("/")[0] for r in open(os.path.join(src, "RECORDS")).read().split()}

    def one(rid):
        found = []
        hdr = wfdb.rdheader(os.path.join(src, rid))
        ann = wfdb.rdann(os.path.join(src, rid), "atr")
        local = os.path.exists(os.path.join(src, rid + ".dat"))
        for label, (start, end, beats) in sampled(hdr, ann, hdr.fs, rid, per_label):
            for attempt in range(6):
                try:
                    if local:
                        rec = wfdb.rdrecord(os.path.join(src, rid), channels=[0], sampfrom=start, sampto=end)
                    else:
                        rec = wfdb.rdrecord(rid, channels=[0], sampfrom=start, sampto=end, pn_dir="cpsc2021/1.0.0/" + part[rid])
                    break
                except Exception:
                    if attempt == 5:
                        raise
                    time.sleep(2 ** attempt)
            x = resample(rec.p_signal[:, 0], hdr.fs)
            b = [int(round((s - start) * FS / hdr.fs)) for s, _ in beats]
            write(out, found, f"cpsc_{rid[5:]}_{start}", "cpsc2021", label, x, b)
        return found

    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(48) as pool:
        ids = {f[:-4] for f in os.listdir(src) if f.endswith(".atr")} & {f[:-4] for f in os.listdir(src) if f.endswith(".hea")}
        for found in pool.map(one, sorted(ids)):
            rows.extend(found)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cinc")
    ap.add_argument("--mitdb")
    ap.add_argument("--nstdb")
    ap.add_argument("--afdb")
    ap.add_argument("--cpsc")
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
    if a.afdb:
        afdb(a.afdb, a.out, rows)
    if a.cpsc:
        cpsc(a.cpsc, a.out, rows)
    with open(os.path.join(a.out, "index.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["id", "group", "label", "file", "beats"])
        w.writerows(rows)
    from collections import Counter
    print(Counter((r[1], r[2]) for r in rows))


if __name__ == "__main__":
    main()
