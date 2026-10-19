# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Trains the median-beat models (EcgMorphology.kt): a QRS wider than usual (bundle branch block,
intraventricular conduction delay, pre-excitation) and a long PR interval (first-degree AV
block), from the features EcgDatasetReport exports (ECG_MORPH_OUT) for 10 s 12-lead databases,
read on lead I only.

    python train_morphology.py --morph morph-ptbxl.csv morph-chapman.csv --features feat-ptbxl.csv feat-chapman.csv \
        --measures <ptbxl dir>/measures.csv --out-dir ../../shared/src/main/resources/ecg

References: PTB-XL's statements plus PTB-XL+'s GE 12SL measurements (QRS ≥ 120 ms, PR > 200 ms,
global over 12 leads), Chapman-Shaoxing's diagnoses. AFib, flutter and paced records are left
out (no PR; the QRS of a paced beat is not the point). 5-fold cross-validation grouped by patient;
the threshold is the one at which 97 % (--specificity) of the records without the finding stay
unflagged.
"""
import argparse
import csv
import json
import math

import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.model_selection import StratifiedGroupKFold

from train_beats import export as export_hist
from train_beats import predict_exported
from train_rhythm import group

WIDE = {"CLBBB", "CRBBB", "LBBB", "RBBB", "IVCD", "WPW", "IVB", "VPE"}
AVB1 = {"1AVB", "PRIE"}
EXCLUDE = {"AFIB", "AFLT", "AF", "PACE"}
SPECIFICITY = 0.97
# EcgModels.LONG_PR_MIN_MS: the app also needs the measured PR (feature pr_ms) to be this long.
LONG_PR_MIN_MS = 180.0


def number(v):
    try:
        x = float(v)
    except ValueError:
        return None
    return None if math.isnan(x) else x


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--morph", nargs="+", required=True)
    ap.add_argument("--features", nargs="+", required=True, help="recording feature csvs (labels, signal verdict)")
    ap.add_argument("--measures")
    ap.add_argument("--out-dir")
    ap.add_argument("--specificity", type=float, default=SPECIFICITY)
    a = ap.parse_args()
    info = {}
    for p in a.features:
        for r in csv.DictReader(open(p)):
            info[r["id"]] = r
    ref = {r["id"]: r for r in csv.DictReader(open(a.measures))} if a.measures else {}
    names, ids, X = None, [], []
    for p in a.morph:
        with open(p) as f:
            r = csv.reader(f)
            header = next(r)
            cols = [i for i, n in enumerate(header) if i > 0 and n != "range_mv"]
            names = [header[i] for i in cols]
            for line in r:
                ids.append(line[0])
                X.append([float(line[i]) for i in cols])
    X = np.array(X)
    keep, wide, long_pr, measured = [], [], [], []
    for i, rid in enumerate(ids):
        r = info.get(rid)
        if r is None or r["reason"] != "NONE":
            continue
        codes = set(r["label"].split(";"))
        if codes & EXCLUDE:
            continue
        m = ref.get(rid, {})
        qrs, pr = number(m.get("qrs_ms", "")), number(m.get("pr_ms", ""))
        keep.append(i)
        measured.append(number(r.get("pr_ms", "")) or -1.0)
        wide.append(bool(codes & WIDE) or (qrs is not None and qrs >= 120))
        long_pr.append(bool(codes & AVB1) or (pr is not None and pr > 200))
    keep = np.array(keep)
    measured = np.array(measured)
    X = X[keep]
    rid = np.array(ids)[keep]
    g = np.array([group({"id": i}) for i in rid])
    src = np.array([i.split("_")[0] for i in rid])
    print(f"records: {len(rid)} ({dict(zip(*np.unique(src, return_counts=True)))})")

    for name, y in (("wide_qrs", np.array(wide)), ("long_pr", np.array(long_pr))):
        def make():
            return HistGradientBoostingClassifier(max_iter=300, learning_rate=0.08, max_depth=5, l2_regularization=1.0, random_state=0)

        oof = np.zeros(len(y))
        for tr, te in StratifiedGroupKFold(5, shuffle=True, random_state=0).split(X, y, g):
            oof[te] = make().fit(X[tr], y[tr]).predict_proba(X[te])[:, 1]
        guard = measured >= LONG_PR_MIN_MS if name == "long_pr" else np.ones(len(y), bool)
        # The threshold at which the app's rule (model, and the measured PR for long PR) keeps the
        # asked specificity.
        t = float(min((th for th in np.quantile(oof[~y], np.linspace(0.5, 0.999, 500))
                       if np.mean(~((oof[~y] >= th) & guard[~y])) >= a.specificity), default=1.0))
        print(f"-- {name}: {int(y.sum())} of {len(y)} records; threshold {t:.3f} ({100 * a.specificity:.0f}% specificity)")
        rules = [("model", oof >= t)]
        if name == "long_pr":
            rules = [(f"model + measured PR ≥ {LONG_PR_MIN_MS:.0f} ms (as the app)", (oof >= t) & guard)]
        for rule, flagged in rules:
            print(f"  {rule}:")
            for s in sorted(set(src)) + ["all"]:
                sel = np.ones(len(y), bool) if s == "all" else src == s
                pos, neg = y & sel, ~y & sel
                if pos.any() and neg.any():
                    print(f"    {s:8s} sensitivity {100 * flagged[pos].mean():.0f}% ({pos.sum()})  specificity {100 * (~flagged[neg]).mean():.1f}% "
                          f"({neg.sum()})  PPV {100 * (flagged & pos).sum() / max((flagged & sel).sum(), 1):.0f}%")
        if a.out_dir:
            final = make().fit(X, y.astype(int))
            m = export_hist(final, names, {"yes": round(t, 4)}, f"HistGBT 300 depth 5 on {len(y)} 10 s lead-I records (PTB-XL, Chapman); CV grouped by patient")
            m["classes"] = ["no", "yes"]
            # Binary HistGradientBoosting has one tree per round and a logit; RhythmModel's softmax
            # needs one per class: class "no" gets zero trees' worth (a constant 0 logit).
            m["init"] = [0.0, m["init"][0]]
            m["trees"] = [[{"feature": [0], "threshold": [0.0], "left": [-1], "right": [-1], "value": [0.0]}, stage[0]] for stage in m["trees"]]
            sample = X[:: max(1, len(X) // 1000)]
            err = np.abs(predict_exported(m, sample)[:, 1] - final.predict_proba(sample)[:, 1]).max()
            assert err < 1e-6, f"exported model differs from sklearn by {err}"
            with open(f"{a.out_dir}/{name}_model.json", "w") as f:
                json.dump(m, f, separators=(",", ":"))
            print("  wrote", f"{a.out_dir}/{name}_model.json", f"({len(json.dumps(m)) // 1024} kB)")


if __name__ == "__main__":
    main()
