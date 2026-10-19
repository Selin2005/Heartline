# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""What the app shows on 10 s 12-lead databases it is never trained on for rhythm (lead I only):
PTB-XL (with PTB-XL+ reference QRS duration and PR interval) and Chapman-Shaoxing / Ningbo. A 10 s
strip has a third of the watch's beats, so these rhythm numbers are a pessimistic, independent
check, not the headline result. Reads EcgDatasetReport's ECG_RESULTS_OUT, which holds exactly
what the app decided (result, note, early beats, long PR).

    ECG_MIN_SEC=8 ECG_DATASET=<ptbxl dir> ECG_RESULTS_OUT=results-ptbxl.csv ./gradlew :shared:test --tests '*EcgDatasetReport*' --rerun
    python evaluate_short.py results-ptbxl.csv results-chapman.csv --measures <ptbxl dir>/measures.csv
"""
import argparse
import csv
import math
from collections import Counter, defaultdict

# PTB-XL SCP codes and Chapman acronyms (in Chapman "AF" is atrial flutter and "AFIB" AFib).
RHYTHM = {
    "AF": {"AFIB"},
    "FL": {"AFLT", "AF"},
    "SVT": {"SVTAC", "PSVT", "SVT", "AT", "AVNRT", "AVRT"},
    "ST": {"STACH", "ST"},
    "SB": {"SBRAD", "SB"},
    "V": {"PVC", "VPB", "BIGU", "TRIGU", "VB"},
    "S": {"PAC", "APB", "ABI", "JPT"},
    "SR": {"SR", "NORM"},
    "PACE": {"PACE"},
}
AVB1 = {"1AVB", "PRIE"}
NOTE_SHORT = {
    "EXTRA_BEATS_UPPER": "+S", "EXTRA_BEATS_LOWER": "+V", "EXTRA_BEATS_BOTH": "+VS",
    "SINUS_FAST": "/sinus", "SINUS_SLOW": "/sinus",
}


def reference(label):
    codes = set(label.split(";"))
    if codes & RHYTHM["PACE"]:
        return None
    for name in ("AF", "FL", "SVT", "V", "S", "ST", "SB"):
        if codes & RHYTHM[name]:
            return name
    return "SR" if codes & RHYTHM["SR"] else None


def shown(r):
    """Result as shown, with the early-beat kind or "sinus" naming of a rate."""
    res = {"SINUS_RHYTHM": "SINUS", "FREQUENT_EXTRA_BEATS": "FREQ", "AFIB_SIGNS": "AF", "HIGH_HEART_RATE": "HIGH",
           "LOW_HEART_RATE": "LOW", "INCONCLUSIVE": "INC", "POOR_RECORDING": "POOR"}[r["result"]]
    return res + NOTE_SHORT.get(r["note"], "")


def number(v):
    try:
        x = float(v)
    except ValueError:
        return None
    return None if math.isnan(x) else x


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results", nargs="+")
    ap.add_argument("--measures")
    a = ap.parse_args()
    rows = [r for p in a.results for r in csv.DictReader(open(p))]
    out = defaultdict(Counter)
    for r in rows:
        ref = reference(r["label"])
        if ref is not None:
            out[(r["group"], ref)][shown(r)] += 1
    print("-- what the app shows on 10 s records (never trained on for rhythm)")
    for k in sorted(out):
        n = sum(out[k].values())
        top = ", ".join(f"{c} {100 * v / n:.0f}%" for c, v in out[k].most_common(8))
        print(f"  {k[0]:8s} {k[1]:4s} n={n:6d}  {top}")

    def share(refs, pred):
        n = sum(sum(c.values()) for (g, ref), c in out.items() if ref in refs)
        k = sum(sum(v for s, v in c.items() if pred(s)) for (g, ref), c in out.items() if ref in refs)
        return f"{100 * k / n:.0f}% of {n}" if n else "-"

    print("-- summary (PTB-XL + Chapman)")
    print("  sinus tachycardia named sinus (ST → HIGH/INC + sinus):", share({"ST"}, lambda s: s.endswith("/sinus")))
    print("  SVT, flutter, AFib named sinus (wrongly):            ",
          share({"SVT", "FL", "AF"}, lambda s: s.endswith("/sinus") or s.startswith("SINUS") or s.startswith("FREQ")))
    low = sum(c[k] for (g, ref), c in out.items() if ref == "SB" for k in c if k.startswith("LOW"))
    named = sum(c["LOW/sinus"] for (g, ref), c in out.items() if ref == "SB")
    print("  sinus bradycardia < 50 bpm named sinus (LOW → + sinus):", f"{100 * named / max(low, 1):.0f}% of {low}")
    print("  ventricular early beats reported with V:             ", share({"V"}, lambda s: "+" in s and "V" in s.split("+")[-1]))
    print("  supraventricular early beats reported as S:          ", share({"S"}, lambda s: s.endswith("+S")))
    print("  normal sinus: early beats reported (wrongly):         ", share({"SR"}, lambda s: "+" in s))
    print("  normal sinus → AF (wrongly):                          ", share({"SR"}, lambda s: s == "AF"))
    print("  AFib → AF:                                            ", share({"AF"}, lambda s: s == "AF"))

    ref = {r["id"]: r for r in csv.DictReader(open(a.measures))} if a.measures else {}
    tp = fp = fn = tn = 0
    pr_pairs, qrs_pairs = [], []
    for r in rows:
        if r["poor"] != "NONE" or r["id"] not in ref:
            continue
        codes = set(r["label"].split(";"))
        if codes & (RHYTHM["AF"] | RHYTHM["FL"] | RHYTHM["PACE"]):
            continue
        m = ref[r["id"]]
        pr = number(m.get("pr_ms", ""))
        q = number(m.get("qrs_ms", ""))
        if q is not None and number(r["qrs_ms"]) is not None:
            qrs_pairs.append((number(r["qrs_ms"]), q))
        if r["result"] not in ("SINUS_RHYTHM", "FREQUENT_EXTRA_BEATS") and r["note"] not in ("SINUS_FAST", "SINUS_SLOW"):
            continue  # a long PR is only reported on a sinus rhythm
        truth = bool(codes & AVB1) or (pr is not None and pr > 200)
        flag = r["long_pr"] == "true"
        tp += flag and truth
        fp += flag and not truth
        fn += (not flag) and truth
        tn += (not flag) and not truth
        if pr is not None and number(r["pr_ms"]) is not None:
            pr_pairs.append((number(r["pr_ms"]), pr))
    print("-- long PR note on PTB-XL sinus recordings (PTB-XL+ PR > 200 ms or first-degree AV block)")
    if tp + fn and fp + tn:
        print(f"  sensitivity {100 * tp / (tp + fn):.0f}% ({tp + fn})  specificity {100 * tn / (tn + fp):.1f}% ({tn + fp})  "
              f"PPV {100 * tp / max(tp + fp, 1):.0f}%  (in-sample: the model was trained on these records; "
              f"train_morphology.py has the cross-validated numbers)")
    for name, pairs in (("PR interval", pr_pairs), ("QRS duration", qrs_pairs)):
        if pairs:
            d = sorted(x - y for x, y in pairs)
            med = d[len(d) // 2]
            mad = sorted(abs(v - med) for v in d)[len(d) // 2]
            print(f"  {name} (lead I) vs 12SL (12 leads): n={len(d)} bias {med:+.0f} ms, median spread {mad:.0f} ms")


if __name__ == "__main__":
    main()
