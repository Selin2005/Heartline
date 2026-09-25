"""Patient-grouped CV: the app's rhythm features alone vs plus ECGFounder's label probabilities."""
import csv
import sys

import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import StratifiedGroupKFold

sys.path.insert(0, __file__.rsplit("/", 2)[0] + "/ecg-eval")
from train_rhythm import CLASSES, LABEL, decide, group, load, report  # noqa: E402


def main():
    feats, founder = sys.argv[1], sys.argv[2]
    names, rows = load([feats])
    fr = list(csv.reader(open(founder)))
    fnames, fmap = fr[0][1:], {r[0]: [float(v) for v in r[1:]] for r in fr[1:]}
    rows = [r for r in rows if r["id"] in fmap and r["label"] in LABEL and r["group"] != "nstdb"]
    y = np.array([CLASSES.index(LABEL[r["label"]]) for r in rows])
    g = np.array([group(r) for r in rows])
    counts = np.bincount(y, minlength=4)
    w = np.array([len(y) / (4 * counts[c]) for c in y])
    base = np.array([[float(r[n]) for n in names] for r in rows])
    plus = np.hstack([base, np.array([fmap[r["id"]] for r in rows])])
    for title, X in [("app features", base), ("app + ECGFounder", plus)]:
        oof = np.zeros((len(y), 4))
        for tr, te in StratifiedGroupKFold(5, shuffle=True, random_state=0).split(X, y, g):
            m = GradientBoostingClassifier(n_estimators=150, max_depth=3, learning_rate=0.1, subsample=0.8, random_state=0)
            oof[te] = m.fit(X[tr], y[tr], sample_weight=w[tr]).predict_proba(X[te])
        normal = y == 0
        t_af = next(t for t in np.arange(0.3, 0.99, 0.01) if np.mean(oof[normal, 1] >= t) <= 0.01)
        t_noisy = next(t for t in np.arange(0.3, 0.99, 0.01) if np.mean(oof[normal, 3] >= t) <= 0.03)
        report(title, rows, oof, {"af": round(float(t_af), 2), "noisy": round(float(t_noisy), 2), "normal": 0.5})


if __name__ == "__main__":
    main()
