"""Trains the phone's "second opinion" model: the app's rhythm features plus ECGFounder's 150 label
probabilities, same tree model and decision logic as train_rhythm.py. Needs ecgfounder_features.py
output. The phone uses it only when ecgfounder_1lead_fp16.onnx is bundled (see README).

    python train_second_opinion.py feat-cinc.csv founder.csv --out ../../phone/src/main/assets/ecg/second_opinion_model.json
"""
import argparse
import csv
import json
import sys

import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import StratifiedGroupKFold

sys.path.insert(0, __file__.rsplit("/", 2)[0] + "/ecg-eval")
from train_rhythm import CLASSES, LABEL, export, group, load, report  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("features")
    ap.add_argument("founder")
    ap.add_argument("--out")
    a = ap.parse_args()
    names, rows = load([a.features])
    fr = list(csv.reader(open(a.founder)))
    fnames, fmap = fr[0][1:], {r[0]: [float(v) for v in r[1:]] for r in fr[1:]}
    rows = [r for r in rows if r["id"] in fmap and r["label"] in LABEL]
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
        "af": round(float(next(t for t in np.arange(0.3, 0.99, 0.01) if np.mean(oof[normal, 1] >= t) <= 0.01)), 2),
        "noisy": round(float(next(t for t in np.arange(0.3, 0.99, 0.01) if np.mean(oof[normal, 3] >= t) <= 0.03)), 2),
        "normal": 0.5,
    }
    report("cross-validated (app + ECGFounder)", rows, oof, t)
    if a.out:
        final = make().fit(X, y, sample_weight=w)
        info = f"GBT on app features + ECGFounder (1-lead) probabilities, {len(y)} CinC 2017 recordings, CV grouped"
        with open(a.out, "w") as f:
            json.dump(export(final, names + fnames, t, info), f, separators=(",", ":"))
        print("wrote", a.out)


if __name__ == "__main__":
    main()
