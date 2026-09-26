# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Trains the phone's "second opinion" model: the app's rhythm features plus ECGFounder's 150 label
probabilities, same tree model and decision logic as train_rhythm.py. Needs ecgfounder_features.py
output. The phone uses it only when ecgfounder_1lead_fp16.onnx is bundled (see README).

    python train_second_opinion.py --features feat-cinc.csv feat-mit.csv --founder founder*.csv \
        --out ../../phone/src/main/assets/ecg/second_opinion_model.json

NSTDB segments (noise stress) are never trained on; they only check false AFib on noise.
"""
import argparse
import csv
import json
import sys

import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import StratifiedGroupKFold

sys.path.insert(0, __file__.rsplit("/", 2)[0] + "/ecg-eval")
from train_rhythm import CLASSES, LABEL, af_threshold, export, group, load, report  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--features", nargs="+", required=True)
    ap.add_argument("--founder", nargs="+", required=True)
    ap.add_argument("--out")
    a = ap.parse_args()
    names, all_rows = load(a.features)
    fmap = {}
    for path in a.founder:
        fr = list(csv.reader(open(path)))
        fnames = fr[0][1:]
        fmap.update({r[0]: [float(v) for v in r[1:]] for r in fr[1:]})
    all_rows = [r for r in all_rows if r["id"] in fmap]
    rows = [r for r in all_rows if r["label"] in LABEL and r["group"] != "nstdb"]
    nstdb = [r for r in all_rows if r["group"] == "nstdb"]
    X = np.array([[float(r[n]) for n in names] + fmap[r["id"]] for r in rows])
    y = np.array([CLASSES.index(LABEL[r["label"]]) for r in rows])
    g = np.array([group(r) for r in rows])
    counts = np.bincount(y, minlength=4)
    w = np.array([len(y) / (4 * counts[c]) for c in y])

    def make():
        return GradientBoostingClassifier(n_estimators=150, max_depth=3, learning_rate=0.1, subsample=0.8, random_state=0)

    oof = np.zeros((len(y), 4))
    for tr, te in StratifiedGroupKFold(5, shuffle=True, random_state=0).split(X, y, g):
        oof[te] = make().fit(X[tr], y[tr], sample_weight=w[tr]).predict_proba(X[te])
    normal = y == 0
    t = {
        "af": round(float(af_threshold(oof[:, 1], rows, y)), 2),
        "noisy": round(float(next(t for t in np.arange(0.05, 0.99, 0.01) if np.mean(oof[normal, 3] >= t) <= 0.03)), 2),
        "normal": 0.5,
    }
    report("cross-validated (app + ECGFounder)", rows, oof, t)
    final = make().fit(X, y, sample_weight=w)
    if nstdb:
        Xn = np.array([[float(r[n]) for n in names] + fmap[r["id"]] for r in nstdb])
        report("NSTDB (never trained on)", nstdb, final.predict_proba(Xn), t)
    if a.out:
        info = f"GBT on app features + ECGFounder (1-lead) probabilities, {len(y)} CinC 2017 + MIT-BIH + AFDB + CPSC 2021 recordings, CV grouped by patient"
        with open(a.out, "w") as f:
            json.dump(export(final, names + fnames, t, info), f, separators=(",", ":"))
        print("wrote", a.out)


if __name__ == "__main__":
    main()
