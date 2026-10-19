# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Converts public ECG databases into the evaluation format read by EcgDatasetReport.

Output directory:
    index.csv            id,group,label,file,beats
    <id>.f32             float32 little-endian samples, mV, 500 Hz
    <id>.beats           int32 little-endian reference QRS positions (samples at 500 Hz), if known
    <id>.sym             one reference beat class per beat (N / S / V, "-" for others), if annotated
    measures.csv         PTB-XL only: id,qrs_ms,pr_ms from PTB-XL+ (GE 12SL, global over 12 leads)

Groups and labels (30 s segments of the long recordings; see label_of):
    cinc2017   N (normal) / A (AF) / O (other rhythm) / ~ (noisy)      single lead, 30–60 s
    mitdb      MLII: N / V / S / VS (sinus with ventricular / supraventricular / both early beats) /
               A / FL (flutter) / SVT / SB / VT / P (paced) / O / X, XF (AFib, flutter for part)
    afdb       sampled segments of the MIT-BIH AF Database (channel 1), beats from .qrs (no classes)
    cpsc2021   sampled segments of CPSC 2021 lead I, with classified beats
    svdb       sampled segments of the MIT-BIH Supraventricular Arrhythmia Database (channel 1)
    incart     sampled segments of the St Petersburg INCART database, lead I
    ltafdb     sampled segments of the Long-Term AF Database (channel 1)
    nstdb      30 s segments of 118/119 + electrode-motion noise at 24…-6 dB: label = SNR, with beats
    ptbxl      PTB-XL, 10 s, lead I: label = its SCP statements joined by ";"
    chapman    Chapman-Shaoxing / Ningbo (ecg-arrhythmia), 10 s, lead I: label = diagnoses joined by ";"

    python convert.py --cinc <dir with training2017/ and REFERENCE-v3.csv> --mitdb <dir> --nstdb <dir> \
        --afdb <dir> --cpsc <dir> --svdb <dir> --incart <dir> --ltafdb <dir> --ptbxl <dir> --chapman <dir> --out <dir>
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
ECTOPIC_V = set("VFE")  # ventricular premature, fusion, ventricular escape
ECTOPIC_S = set("AaJSej")  # atrial / nodal / supraventricular premature, atrial / nodal escape
BEATS = set("NLRBAaJSVrFejnE/fQ")
# AAMI beat classes: normal-conducted (bundle branch block and escape beats included), supraventricular
# ectopic, ventricular ectopic.
BEAT_CLASS = {**dict.fromkeys("NLRBenj", "N"), **dict.fromkeys("AaJS", "S"), **dict.fromkeys("VE", "V")}


def resample(x, fs):
    if fs == FS:
        return x.astype(np.float32)
    from fractions import Fraction
    f = Fraction(FS, int(round(fs))).limit_denominator(1000)
    return resample_poly(x, f.numerator, f.denominator).astype(np.float32)


def write(out, rows, rid, group, label, x, beats=None, symbols=None):
    x.astype("<f4").tofile(os.path.join(out, rid + ".f32"))
    bfile = ""
    if beats is not None:
        bfile = rid + ".beats"
        np.asarray(beats, dtype="<i4").tofile(os.path.join(out, bfile))
        if symbols is not None:
            # One reference beat class per beat (N / S / V, or "-" for fusion, paced and unclassified).
            with open(os.path.join(out, rid + ".sym"), "w") as f:
                f.write("".join(BEAT_CLASS.get(a, "-") for a in symbols))
    rows.append([rid, group, label, rid + ".f32", bfile])


def cinc(src, out, rows):
    ref = dict(csv.reader(open(os.path.join(src, "REFERENCE-v3.csv"))))
    for rid, label in sorted(ref.items()):
        rec = wfdb.rdrecord(os.path.join(src, "training2017", rid))
        write(out, rows, "cinc_" + rid, "cinc2017", label, resample(rec.p_signal[:, 0], rec.fs))


def rhythm_changes(ann):
    """(sample, rhythm) at each rhythm annotation ("+" with an aux note such as "(AFIB")."""
    out = []
    for s, a, note in zip(ann.sample, ann.symbol, ann.aux_note):
        note = (note or "").strip("\x00").strip()
        if a == "+" and note.startswith("("):
            out.append((s, note))
    return out


def segments(rec, ann, fs):
    """30 s segments: (start, end, rhythm cover {rhythm: fraction of the segment}, [(sample, beat symbol)])."""
    import bisect
    n = int(SEG_S * fs)
    changes = rhythm_changes(ann)
    change_at = [s for s, _ in changes]
    beats_all = [(s, a) for s, a in zip(ann.sample, ann.symbol) if a in BEATS]
    beat_at = [s for s, _ in beats_all]
    for start in range(0, rec.sig_len - n + 1, n):
        end = start + n
        i = bisect.bisect_right(change_at, start)
        current, pos, cover = (changes[i - 1][1] if i > 0 else "(N"), start, {}
        while i < len(changes) and changes[i][0] < end:
            cover[current] = cover.get(current, 0) + (changes[i][0] - pos) / n
            current, pos = changes[i][1], changes[i][0]
            i += 1
        cover[current] = cover.get(current, 0) + (end - pos) / n
        beats = beats_all[bisect.bisect_left(beat_at, start):bisect.bisect_left(beat_at, end)]
        yield start, end, cover, beats


def mitdb(src, out, rows):
    for rid in sorted({f[:-4] for f in os.listdir(src) if f.endswith(".hea")}):
        rec = wfdb.rdrecord(os.path.join(src, rid), channels=[0])
        ann = wfdb.rdann(os.path.join(src, rid), "atr")
        for start, end, cover, beats in segments(rec, ann, rec.fs):
            label = label_of(cover, beats)
            x = resample(rec.p_signal[start:end, 0], rec.fs)
            b = [int(round((s - start) * FS / rec.fs)) for s, _ in beats]
            write(out, rows, f"mitdb_{rid}_{start}", "mitdb", label, x, b, [a for _, a in beats])


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


def label_of(cover, beats):
    """The 30 s segment's label from its rhythm annotations and beat labels:
    N sinus without early beats; V / S / VS sinus with ventricular / supraventricular / both kinds of
    early beats (bigeminy and trigeminy included); A AFib; FL atrial flutter; SVT supraventricular
    tachycardia (≥ 80 % of the segment); SB sinus bradycardia; VT ventricular tachycardia or flutter;
    P paced; O any other rhythm. X / XF: AFib / flutter for part of the segment only (ambiguous as a
    30 s label: kept out of training, reported apart)."""
    r = {k for k, v in cover.items() if v > 0}
    sym = {a for _, a in beats}
    if "(AFIB" in r:
        return "A" if r == {"(AFIB"} else "X"
    if "(AFL" in r:
        return "FL" if r == {"(AFL"} else "XF"
    if r & {"(VT", "(VFL", "(VF"}:
        return "VT"
    if "(P" in r or "/" in sym or "f" in sym:
        return "P"
    if r <= {"(N", "(B", "(T", "(AB"}:
        v = bool(sym & ECTOPIC_V) or bool(r & {"(B", "(T"})
        sv = bool(sym & ECTOPIC_S) or "(AB" in r
        return "VS" if v and sv else "V" if v else "S" if sv else "N"
    if r == {"(SBR"}:
        return "SB"
    if cover.get("(SVTA", 0) >= 0.8:
        return "SVT"
    return "O"


def sampled(rec, ann, fs, rid, per_label, beat_ann=None):
    """Up to per_label 30 s segments of each label, spread over the record (deterministic)."""
    by_label = {}
    for start, end, cover, beats in segments(rec, ann, fs):
        if beat_ann is not None:
            beats = [(s, "N") for s in beat_ann.sample if start <= s < end]
        by_label.setdefault(label_of(cover, beats), []).append((start, end, beats))
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
            if label in ("V", "S", "VS"):
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
            write(out, found, f"cpsc_{rid[5:]}_{start}", "cpsc2021", label, x, b, [a for _, a in beats])
        return found

    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(48) as pool:
        ids = {f[:-4] for f in os.listdir(src) if f.endswith(".atr")} & {f[:-4] for f in os.listdir(src) if f.endswith(".hea")}
        for found in pool.map(one, sorted(ids)):
            rows.extend(found)


def holter(src, out, rows, group, channel=0, per_label=10):
    """Annotated Holter / long-term databases (SVDB, INCART lead I, LTAFDB): up to per_label sampled
    30 s segments of each label per record, beats from the reference annotations."""
    for rid in sorted({f[:-4] for f in os.listdir(src) if f.endswith(".dat")}):
        path = os.path.join(src, rid)
        if not os.path.exists(path + ".atr"):
            continue
        hdr = wfdb.rdheader(path)
        ann = wfdb.rdann(path, "atr")
        for label, (start, end, beats) in sampled(hdr, ann, hdr.fs, rid, per_label):
            rec = wfdb.rdrecord(path, channels=[channel], sampfrom=start, sampto=end)
            x = resample(rec.p_signal[:, 0], hdr.fs)
            b = [int(round((s - start) * FS / hdr.fs)) for s, _ in beats]
            write(out, rows, f"{group}_{rid}_{start}", group, label, x, b, [a for _, a in beats])


def ptbxl(src, out, rows, measures):
    """PTB-XL: 21 799 ten-second 12-lead ECGs; lead I at 500 Hz. Label = its SCP statements joined by
    ";" (e.g. "NORM;SR", "AFLT", "SR;PVC;CRBBB"); QRS duration and PR interval from PTB-XL+ (GE 12SL,
    global over 12 leads) go to measures."""
    import ast
    plus = {}
    pf = os.path.join(src, "plus_12sl_features.csv")
    if os.path.exists(pf):
        for r in csv.DictReader(open(pf)):
            plus[r["ecg_id"].split(".")[0]] = (r["QRS_Dur_Global"], r["PR_Int_Global"])
    for r in csv.DictReader(open(os.path.join(src, "ptbxl_database.csv"))):
        eid = r["ecg_id"]
        path = os.path.join(src, r["filename_hr"])
        if not os.path.exists(path + ".dat"):
            continue
        codes = sorted(ast.literal_eval(r["scp_codes"]))
        rec = wfdb.rdrecord(path, channels=[0])
        rid = f"ptbxl_{int(float(r['patient_id']))}_{eid}"
        write(out, rows, rid, "ptbxl", ";".join(codes), resample(np.nan_to_num(rec.p_signal[:, 0]), rec.fs))
        qrs, pr = plus.get(eid, ("", ""))
        measures.append([rid, qrs, pr])


def chapman(src, out, rows):
    """Chapman-Shaoxing / Ningbo (PhysioNet ecg-arrhythmia): 45 152 ten-second 12-lead ECGs; lead I.
    Label = the SNOMED diagnoses' acronyms joined by ";" (e.g. "SR", "AFL", "SB;1AVB")."""
    names = {}
    for r in csv.DictReader(open(os.path.join(src, "ConditionNames_SNOMED-CT.csv"), encoding="utf-8-sig")):
        names[r["Snomed_CT"].strip()] = r["Acronym Name"].strip()
    for root, _, fs in sorted(os.walk(src)):
        for f in sorted(fs):
            if not f.endswith(".hea"):
                continue
            path = os.path.join(root, f[:-4])
            try:
                hdr = wfdb.rdheader(path)
                dx = next((c.split(":", 1)[1] for c in hdr.comments if c.startswith("Dx")), "")
                rec = wfdb.rdrecord(path, channels=[0])
            except Exception:
                continue  # a few records have broken headers or files
            x = np.nan_to_num(rec.p_signal[:, 0])
            if not np.any(x):
                continue
            label = ";".join(sorted({names.get(c.strip(), c.strip()) for c in dx.split(",") if c.strip()}))
            write(out, rows, f"chapman_{f[:-4]}", "chapman", label, resample(x, rec.fs))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cinc")
    ap.add_argument("--mitdb")
    ap.add_argument("--nstdb")
    ap.add_argument("--afdb")
    ap.add_argument("--cpsc")
    ap.add_argument("--svdb")
    ap.add_argument("--incart")
    ap.add_argument("--ltafdb")
    ap.add_argument("--ptbxl")
    ap.add_argument("--chapman")
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
    if a.svdb:
        holter(a.svdb, a.out, rows, "svdb")
    if a.incart:
        holter(a.incart, a.out, rows, "incart")
    if a.ltafdb:
        holter(a.ltafdb, a.out, rows, "ltafdb", per_label=8)
    measures = []
    if a.ptbxl:
        ptbxl(a.ptbxl, a.out, rows, measures)
    if a.chapman:
        chapman(a.chapman, a.out, rows)
    if measures:
        with open(os.path.join(a.out, "measures.csv"), "w", newline="") as f:
            csv.writer(f).writerows([["id", "qrs_ms", "pr_ms"]] + measures)
    with open(os.path.join(a.out, "index.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["id", "group", "label", "file", "beats"])
        w.writerows(rows)
    from collections import Counter
    print(Counter((r[1], r[2]) for r in rows if r[1] not in ("ptbxl", "chapman")))
    print(len(rows), "recordings")


if __name__ == "__main__":
    main()
