"""Trains the learned rhythm model (RhythmModel.kt) on features exported by EcgDatasetReport.

    ECG_DATASET=<cinc dir> ECG_FEATURES_OUT=feat-cinc.csv ./gradlew :shared:test --tests '*EcgDatasetReport*' --rerun
    ECG_DATASET=<mit dir>  ECG_FEATURES_OUT=feat-mit.csv  ./gradlew ...
    python train_rhythm.py feat-cinc.csv feat-mit.csv --out ../../shared/src/main/resources/ecg/rhythm_model.json

Classes: normal (CinC N, MIT N), af (A), other (CinC O, MIT E/O: ectopy and other arrhythmias),
noisy (CinC ~). NSTDB segments are kept out of training and used only to check false AF on noise.
Evaluation is 5-fold cross-validation grouped by patient (MIT segments of one record stay together),
through the same decision logic as the app (decide() mirrors EcgAnalyzer).
"""
import argparse
import csv
import json
from collections import Counter, defaultdict

import numpy as np
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import StratifiedGroupKFold

CLASSES = ["normal", "af", "other", "noisy"]
LABEL = {"N": "normal", "A": "af", "O": "other", "E": "other", "~": "noisy"}


def load(paths):
    rows = []
    for p in paths:
        with open(p) as f:
            r = csv.reader(f)
            header = next(r)
            for line in r:
                rows.append(dict(zip(header, line)))
    return header[5:], rows


def group(row):
    rid = row["id"]
    # Segments of one recording (MIT-BIH, NSTDB, AFDB) or one patient (CPSC 2021: cpsc_<patient>_…) stay together.
    return "_".join(rid.split("_")[:2]) if rid.startswith(("mitdb", "nstdb", "afdb", "cpsc")) else rid


HOLTER = ("afdb", "cpsc2021")


def af_threshold(p_af, rows, y):
    """Lowest AFib threshold with false AFib on normal recordings ≤ 1 % on the short single-lead
    recordings (CinC, MIT-BIH: closest to a watch ECG) and ≤ 4 % on each Holter database, only a
    guard: their "normal" stretches are noisy ambulatory channels, and ~2 % of AFDB's are called
    AFib at any threshold (unlabelled ectopy or AFib around episode boundaries)."""
    groups = np.array([r["group"] for r in rows])
    normal = y == 0
    short = normal & ~np.isin(groups, HOLTER)

    def ok(t):
        if short.any() and np.mean(p_af[short] >= t) > 0.01:
            return False
        return all(np.mean(p_af[normal & (groups == h)] >= t) <= 0.04 for h in HOLTER if (normal & (groups == h)).any())

    return next((t for t in np.arange(0.05, 0.99, 0.01) if ok(t)), 0.99)


def decide(p, bpm, reason, t):
    """Mirrors EcgAnalyzer: signal-quality poor first, then the model."""
    if reason != "NONE" or bpm <= 0:
        return "POOR"
    if p[3] >= t["noisy"]:
        return "POOR"
    if p[1] >= t["af"] and bpm <= 150:
        return "AF"
    if bpm > 120:
        return "HIGH"
    if bpm < 50:
        return "LOW"
    if p[0] >= t["normal"] and bpm <= 100:
        return "SINUS"
    return "INC"


def report(name, rows, probs, t):
    out = defaultdict(Counter)
    for r, p in zip(rows, probs):
        out[(r["group"], r["label"])][decide(p, float(r["bpm"]), r["reason"], t)] += 1
    print(f"-- {name} thresholds {t}")
    for k in sorted(out):
        n = sum(out[k].values())
        print("  %-12s %-3s n=%-5d %s" % (k[0], k[1], n, " ".join(f"{c}={100 * out[k][c] / n:.0f}%" for c in ["SINUS", "AF", "HIGH", "LOW", "INC", "POOR"])))
    return out


def export(model, features, thresholds, info):
    trees = []
    for stage in model.estimators_:
        trees.append([
            {
                "feature": [int(max(f, 0)) for f in est.tree_.feature],
                "threshold": [float(x) for x in est.tree_.threshold],
                "left": [int(x) for x in est.tree_.children_left],
                "right": [int(x) for x in est.tree_.children_right],
                "value": [float(v[0][0]) for v in est.tree_.value],
            }
            for est in stage
        ])
    prior = model.init_.class_prior_
    return {
        "features": features,
        "classes": CLASSES,
        "learningRate": model.learning_rate,
        "init": [float(np.log(max(p, 1e-12))) for p in prior],
        "trees": trees,
        "thresholds": thresholds,
        "info": info,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("features", nargs="+")
    ap.add_argument("--out")
    a = ap.parse_args()
    names, rows = load(a.features)
    train = [r for r in rows if r["label"] in LABEL and r["group"] != "nstdb"]
    X = np.array([[float(r[n]) for n in names] for r in train])
    y = np.array([CLASSES.index(LABEL[r["label"]]) for r in train])
    g = np.array([group(r) for r in train])
    counts = np.bincount(y)
    w = np.array([len(y) / (len(counts) * counts[c]) for c in y])

    def make():
        return GradientBoostingClassifier(n_estimators=150, max_depth=3, learning_rate=0.1, subsample=0.8, random_state=0)

    oof = np.zeros((len(y), len(CLASSES)))
    for tr, te in StratifiedGroupKFold(5, shuffle=True, random_state=0).split(X, y, g):
        m = make().fit(X[tr], y[tr], sample_weight=w[tr])
        oof[te] = m.predict_proba(X[te])

    # Thresholds: the most AF found within the false-AF limits (af_threshold); noisy flagged at ≤ 3 % of normal.
    normal = y == 0
    t_af = af_threshold(oof[:, 1], train, y)
    t_noisy = next(t for t in np.arange(0.05, 0.99, 0.01) if np.mean(oof[normal, 3] >= t) <= 0.03)
    t = {"af": round(float(t_af), 2), "noisy": round(float(t_noisy), 2), "normal": 0.5}
    report("cross-validated", train, oof, t)

    final = make().fit(X, y, sample_weight=w)
    nst = [r for r in rows if r["group"] == "nstdb"]
    if nst:
        report("NSTDB (never trained on)", nst, final.predict_proba(np.array([[float(r[n]) for n in names] for r in nst])), t)
    imp = sorted(zip(final.feature_importances_, names), reverse=True)[:8]
    print("top features", [(n, round(float(v), 3)) for v, n in imp])
    if a.out:
        info = f"GBT 150x4 depth 3, trained on {len(y)} recordings ({dict(Counter(LABEL[r['label']] for r in train))}); CV grouped by patient"
        with open(a.out, "w") as f:
            json.dump(export(final, names, t, info), f, separators=(",", ":"))
        print("wrote", a.out)


if __name__ == "__main__":
    main()
