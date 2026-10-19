# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Trains the beat model (EctopicBeats.kt): normal-conducted (N), supraventricular (S) and
ventricular (V) beats from the per-beat rows EcgDatasetReport exports (ECG_BEATS_OUT), on the
beat-annotated databases (MIT-BIH, SVDB, INCART, LTAFDB, CPSC 2021).

    python train_beats.py beats-mit.csv beats-cpsc.csv beats-holter.csv ... \
        --features feat-mit.csv feat-cinc.csv ... --out ../../shared/src/main/resources/ecg/beat_model.json \
        --oof-out beat-shares.csv

Patient-grouped 5-fold cross-validation. Thresholds are chosen at the recording level, where the
app reports: a recording "has" S or V beats when at least one beat reaches its class threshold;
the thresholds are the lowest at which ≤ 2 % of MIT-BIH's normal 30 s recordings (no early beat
annotated) would be flagged, and ≤ 8 % of each Holter database's, whose "normal" stretches are
noisier ambulatory channels (as for AFib in train_rhythm.py). --oof-out writes each recording's s_beat_share and
v_beat_share from out-of-fold predictions (the final model for recordings not trained on), so the
rhythm model is trained on the same counts the app computes without seeing its own beats twice.
"""
import argparse
import csv
import json
from collections import Counter, defaultdict

import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.model_selection import StratifiedGroupKFold

from train_rhythm import group

CLASSES = ["N", "S", "V"]
ANNOTATED = ("mitdb", "svdb", "incart", "ltafdb", "cpsc2021")


def load(paths):
    rows = []
    names = None
    for p in paths:
        with open(p) as f:
            r = csv.reader(f)
            header = next(r)
            names = header[4:]
            for line in r:
                rows.append(line)
    return names, rows


def make():
    return HistGradientBoostingClassifier(max_iter=200, max_depth=4, learning_rate=0.1, l2_regularization=1.0,
                                          class_weight="balanced", random_state=0)


def export(model, features, thresholds, info):
    """HistGradientBoosting trees in RhythmModel.kt's format: leaf values already include the
    learning rate, so learningRate is 1 and init is the baseline raw score."""
    trees = []
    for stage in model._predictors:
        out = []
        for pred in stage:
            nodes = pred.nodes
            out.append({
                "feature": [int(n["feature_idx"]) if not n["is_leaf"] else 0 for n in nodes],
                "threshold": [float(n["num_threshold"]) if not n["is_leaf"] else 0.0 for n in nodes],
                "left": [int(n["left"]) if not n["is_leaf"] else -1 for n in nodes],
                "right": [int(n["right"]) if not n["is_leaf"] else -1 for n in nodes],
                "value": [float(n["value"]) for n in nodes],
            })
        trees.append(out)
    return {
        "features": features,
        "classes": CLASSES,
        "learningRate": 1.0,
        "init": [float(v) for v in np.ravel(model._baseline_prediction)],
        "trees": trees,
        "thresholds": thresholds,
        "info": info,
    }


def predict_exported(m, X):
    raw = np.tile(np.array(m["init"]), (len(X), 1))
    for stage in m["trees"]:
        for k, t in enumerate(stage):
            feat, thr, left, right, val = (np.array(t[key]) for key in ("feature", "threshold", "left", "right", "value"))
            node = np.zeros(len(X), dtype=int)
            while True:
                inner = left[node] >= 0
                if not inner.any():
                    break
                go_left = X[np.arange(len(X)), feat[node]] <= thr[node]
                node = np.where(inner, np.where(go_left, left[node], right[node]), node)
            raw[:, k] += val[node]
    e = np.exp(raw - raw.max(axis=1, keepdims=True))
    return e / e.sum(axis=1, keepdims=True)


def flagged(prob_by_rec, t_s, t_v):
    """Per recording: (has S, has V) with the app's counting rule (EctopicBeats.count)."""
    out = {}
    for rid, p in prob_by_rec.items():
        v = (p[:, 2] >= t_v) & (p[:, 2] / t_v >= p[:, 1] / t_s)
        s = ~v & (p[:, 1] >= t_s)
        out[rid] = (int(s.sum()), int(v.sum()), len(p))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("beats", nargs="+")
    ap.add_argument("--features", nargs="+", required=True, help="recording feature csvs (labels)")
    ap.add_argument("--out")
    ap.add_argument("--oof-out")
    ap.add_argument("--max-false", type=float, default=0.02)
    ap.add_argument("--dump", help="save out-of-fold beat probabilities (npz) for analysis")
    a = ap.parse_args()
    names, rows = load(a.beats)
    labels = {}
    for p in a.features:
        for r in csv.DictReader(open(p)):
            labels[r["id"]] = (r["group"], r["label"], r["reason"])
    rid = np.array([r[0] for r in rows])
    grp = np.array([r[1] for r in rows])
    ref = np.array([r[3] for r in rows])
    X = np.array([[float(v) for v in r[4:]] for r in rows])
    train = np.isin(grp, ANNOTATED) & np.isin(ref, CLASSES)
    y = np.array([CLASSES.index(c) if c in CLASSES else -1 for c in ref])
    g = np.array([group({"id": i}) for i in rid])
    print("beats:", Counter(ref[train]))

    probs = np.zeros((len(rows), 3))
    idx = np.where(train)[0]
    # Folds over patients; every beat of a held-out patient (annotated or not) is predicted out of fold.
    patients = np.unique(g[idx])
    fold_of = {}
    for k, (_, te) in enumerate(StratifiedGroupKFold(5, shuffle=True, random_state=0).split(X[idx], y[idx], g[idx])):
        for p in np.unique(g[idx][te]):
            fold_of[p] = k
    for k in range(5):
        tr = idx[np.array([fold_of[p] != k for p in g[idx]])]
        te = np.array([i for i in range(len(rows)) if fold_of.get(g[i], -1) == k])
        m = make().fit(X[tr], y[tr])
        probs[te] = m.predict_proba(X[te])
    final = make().fit(X[idx], y[idx])
    other = np.array([i for i in range(len(rows)) if g[i] not in fold_of])
    if len(other):
        probs[other] = final.predict_proba(X[other])
    print("patients:", len(patients))

    # Beat-level quality (out of fold).
    pred = probs[idx].argmax(1)
    for c in range(3):
        tp = np.sum((pred == c) & (y[idx] == c))
        print(f"  beat {CLASSES[c]}: sensitivity {100 * tp / max(np.sum(y[idx] == c), 1):.1f}%  PPV {100 * tp / max(np.sum(pred == c), 1):.1f}%")

    if a.dump:
        np.savez_compressed(a.dump, rid=rid, grp=grp, ref=ref, probs=probs)
    by_rec = defaultdict(list)
    for i in range(len(rows)):
        by_rec[rid[i]].append(probs[i])
    by_rec = {k: np.array(v) for k, v in by_rec.items()}

    def rec_rate(t_s, t_v, which, label_set, groups=ANNOTATED):
        f = flagged(by_rec, t_s, t_v)
        ids = [k for k, (gr, lab, reason) in labels.items() if lab in label_set and gr in groups and reason == "NONE" and k in f]
        if not ids:
            return 0.0, 0
        hit = [f[k][0] > 0 if which == "S" else f[k][1] > 0 if which == "V" else (f[k][0] + f[k][1]) > 0 for k in ids]
        return float(np.mean(hit)), len(ids)

    grid = np.round(np.arange(0.30, 1.0, 0.01), 2)

    def ok(t_s, t_v, which, short_max, holter_max):
        if rec_rate(t_s, t_v, which, {"N"}, ("mitdb",))[0] > short_max:
            return False
        return all(rec_rate(t_s, t_v, which, {"N"}, (h,))[0] <= holter_max for h in ANNOTATED if h != "mitdb")

    # V first (≤ 1 % of MIT-BIH's normal recordings, ≤ 4 % of each Holter database's), then S so
    # that either kind is reported on ≤ 2 % / ≤ 8 % (Holter "normal" segments are noisier).
    t_v = next((t for t in grid if ok(0.999, t, "V", a.max_false / 2, 0.04)), 0.99)
    t_s = next((t for t in grid if ok(t, t_v, "any", a.max_false, 0.08)), 0.99)
    t = {"S": float(t_s), "V": float(t_v)}
    print(f"-- thresholds {t}")
    for h in ANNOTATED:
        print(f"  {h}: normal recordings with early beats reported: {100 * rec_rate(t_s, t_v, 'any', {'N'}, (h,))[0]:.1f}%  "
              f"V found in V: {100 * rec_rate(t_s, t_v, 'V', {'V'}, (h,))[0]:.0f}%  S found in S: {100 * rec_rate(t_s, t_v, 'S', {'S'}, (h,))[0]:.0f}%")
    for lab in ("N", "V", "S", "VS", "A", "O", "FL", "SB", "VT"):
        for which in ("V", "S", "any"):
            r, n = rec_rate(t_s, t_v, which, {lab})
            if n:
                print(f"  {lab:3s} n={n:5d} has {which:3s}: {100 * r:.0f}%")
    for gr in ("cinc2017", "afdb", "nstdb"):
        f = flagged(by_rec, t_s, t_v)
        for lab in sorted({lab for k, (g2, lab, _) in labels.items() if g2 == gr}):
            ids = [k for k, (g2, l2, reason) in labels.items() if g2 == gr and l2 == lab and reason == "NONE" and k in f]
            if ids:
                print(f"  {gr} {lab:3s} n={len(ids):5d} any S: {100 * np.mean([f[k][0] > 0 for k in ids]):.0f}%  "
                      f"any V: {100 * np.mean([f[k][1] > 0 for k in ids]):.0f}%")

    if a.oof_out:
        f = flagged(by_rec, t_s, t_v)
        with open(a.oof_out, "w", newline="") as out:
            w = csv.writer(out)
            w.writerow(["id", "s_beat_share", "v_beat_share"])
            for k, (s, v, n) in f.items():
                w.writerow([k, s / max(n, 1), v / max(n, 1)])
    if a.out:
        info = f"HistGBT 200x3 depth 4 on {int(train.sum())} annotated beats of {len(patients)} patients; CV grouped by patient"
        m = export(final, names, t, info)
        sample = X[idx][:: max(1, len(idx) // 2000)]
        err = np.abs(predict_exported(m, sample) - final.predict_proba(sample)).max()
        assert err < 1e-6, f"exported model differs from sklearn by {err}"
        with open(a.out, "w") as f:
            json.dump(m, f, separators=(",", ":"))
        print("wrote", a.out, f"({len(json.dumps(m)) // 1024} kB)")


if __name__ == "__main__":
    main()
