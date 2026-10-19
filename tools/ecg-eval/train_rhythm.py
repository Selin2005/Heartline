# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Trains the learned rhythm model (RhythmModel.kt) on features exported by EcgDatasetReport.

    ECG_DATASET=<cinc dir> ECG_FEATURES_OUT=feat-cinc.csv ./gradlew :shared:test --tests '*EcgDatasetReport*' --rerun
    ECG_DATASET=<mit dir>  ECG_FEATURES_OUT=feat-mit.csv  ./gradlew ...
    python train_rhythm.py feat-cinc.csv feat-mit.csv ... --beat-shares beat-shares.csv \
        --out ../../shared/src/main/resources/ecg/rhythm_model.json

Two models (algorithm 4). The rhythm model's classes, as in algorithm 3: normal (CinC N, database
N and SB), af (A), other (CinC O, any other rhythm — flutter and SVT included: too few patients to
call them on their own, see docs/algorithms/ECG_ALGORITHM.md — and recordings with early beats:
V, S, VS), noisy (CinC ~). The sinus-with-early-beats model looks only at recordings
in which the beat model (train_beats.py) found early beats and the rhythm model saw no sinus
rhythm: is the rhythm underneath sinus (N, SB, V, S, VS) or not (AFib, flutter, other)?
--beat-shares replaces the exported s_beat_share / v_beat_share with the beat model's
out-of-fold counts. Labels X / XF (AFib
or flutter for part of the segment), P (paced) and the 10 s databases (PTB-XL, Chapman: see
evaluate_short.py) are never trained on. NSTDB segments only check false alarms on noise.
Evaluation is 5-fold cross-validation grouped by patient (segments of one record or patient stay
together), through the same decision logic as the app (decide() mirrors EcgAnalyzer.decide).
"""
import argparse
import csv
import json
from collections import Counter, defaultdict

import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.model_selection import StratifiedGroupKFold

CLASSES = ["normal", "af", "other", "noisy"]
LABEL = {
    "N": "normal", "SB": "normal", "V": "other", "S": "other", "VS": "other",
    "A": "af", "O": "other", "VT": "other", "~": "noisy", "FL": "other", "SVT": "other",
}
SINUS_BENEATH = {"N", "SB", "V", "S", "VS"}
# Outcomes, in the order reports list them: sinus without / with occasional early beats, frequent
# early beats, AFib, high / low rate, inconclusive, poor. Early-beat kinds are listed apart.
OUTCOMES = ["SINUS", "SINUS+X", "FREQ", "AF", "HIGH", "LOW", "INC", "POOR"]
HOLTER = ("afdb", "cpsc2021", "ltafdb", "svdb", "incart")
SHORT = ("cinc2017", "mitdb")
FREQUENT = 0.10


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
    # Segments of one recording (MIT-BIH, NSTDB, AFDB, LTAFDB, SVDB, INCART) or one patient
    # (CPSC 2021: cpsc_<patient>_…, PTB-XL: ptbxl_<patient>_…) stay together.
    prefixes = ("mitdb", "nstdb", "afdb", "cpsc", "ltafdb", "svdb", "incart", "ptbxl")
    return "_".join(rid.split("_")[:2]) if rid.startswith(prefixes) else rid


def limited_threshold(p, rows, y, short_max, holter_max):
    """Lowest threshold at which ≤ short_max of the normal recordings of the short single-lead
    databases (CinC, MIT-BIH: closest to a watch ECG) and ≤ holter_max of each Holter database's
    normal segments reach it. The Holter limit is only a guard: their "normal" stretches are noisy
    ambulatory channels with unlabelled episodes around annotation boundaries."""
    groups = np.array([r["group"] for r in rows])
    normal = y == 0
    short = normal & np.isin(groups, SHORT)

    def ok(t):
        if short.any() and np.mean(p[short] >= t) > short_max:
            return False
        return all(np.mean(p[normal & (groups == h)] >= t) <= holter_max for h in HOLTER if (normal & (groups == h)).any())

    return next((t for t in np.arange(0.05, 0.99, 0.01) if ok(t)), 0.99)


def af_threshold(p_af, rows, y):
    """AFib: ≤ 1 % of short normal recordings, ≤ 4 % of each Holter database's (as algorithm 3)."""
    return limited_threshold(p_af, rows, y, 0.01, 0.04)


def thresholds(oof, rows, y):
    normal = y == 0
    t = {
        "af": af_threshold(oof[:, 1], rows, y),
        "noisy": next(t for t in np.arange(0.05, 0.99, 0.01) if np.mean(oof[normal, 3] >= t) <= 0.03),
        "normal": 0.5,
    }
    return {k: round(float(v), 2) for k, v in t.items()}


def ectopy(row):
    """(kind, frequent) from the beat model's counts: kind is "V", "S", "VS" or ""."""
    s, v = float(row["s_beat_share"]), float(row["v_beat_share"])
    kind = ("V" if v > 0 else "") + ("S" if s > 0 else "")
    return kind, s + v >= FREQUENT


def decide(p, bpm, reason, t, row=None, sinus_beneath=0.0):
    """Mirrors EcgAnalyzer.decide: signal-quality poor first, then the rhythm model, then the beat
    counts and (for early beats on a rhythm the model didn't call sinus) the sinus-beneath model."""
    if reason != "NONE" or bpm <= 0:
        return "POOR"
    P = dict(zip(CLASSES, p))
    if P["noisy"] >= t["noisy"]:
        return "POOR"
    if P["af"] >= t["af"] and bpm <= 150:
        return "AF"
    if bpm > 120:
        return "HIGH"
    if bpm < 50:
        return "LOW"
    kind, frequent = ectopy(row) if row is not None else ("", False)
    sinus = P["normal"] >= t["normal"] or (kind and sinus_beneath >= t.get("sinus_beneath", 1.0))
    if sinus and bpm <= 100:
        return "FREQ" if kind and frequent else "SINUS+X" if kind else "SINUS"
    return "INC"


def report(name, rows, probs, t, beneath=None):
    out = defaultdict(Counter)
    kinds = defaultdict(Counter)
    beneath = np.zeros(len(rows)) if beneath is None else beneath
    for r, p, b in zip(rows, probs, beneath):
        o = decide(p, float(r["bpm"]), r["reason"], t, r, b)
        out[(r["group"], r["label"])][o] += 1
        if o in ("SINUS+X", "FREQ"):
            kinds[(r["group"], r["label"])][ectopy(r)[0]] += 1
    print(f"-- {name} thresholds {t}")
    for k in sorted(out):
        n = sum(out[k].values())
        kk = " ".join(f"{c}:{v}" for c, v in sorted(kinds[k].items()))
        print("  %-9s %-4s n=%-5d %s%s" % (k[0], k[1], n, " ".join(f"{c}={100 * out[k][c] / n:.0f}%" for c in OUTCOMES if out[k][c]),
                                           f"  [kinds {kk}]" if kk else ""))
    return out, kinds


def summary(result):
    """The acceptance numbers (docs/algorithms/ECG_ALGORITHM.md) pooled over the training databases."""
    out, kinds = result

    def pooled(labels, outcomes, groups=None):
        n = sum(sum(c.values()) for (g, l), c in out.items() if l in labels and (groups is None or g in groups))
        k = sum(sum(c[o] for o in outcomes) for (g, l), c in out.items() if l in labels and (groups is None or g in groups))
        return f"{100 * k / n:.0f}% of {n}" if n else "-"

    def kind(label, want):
        n = sum(sum(c.values()) for (g, l), c in out.items() if l == label)
        k = sum(sum(v for kk, v in c.items() if want(kk)) for (g, l), c in kinds.items() if l == label)
        return f"{100 * k / n:.0f}% of {n}" if n else "-"

    ex = {"SINUS+X", "FREQ"}
    print("-- summary")
    print("  AF found (A → AF):                       ", pooled({"A"}, {"AF"}))
    print("  normal → AF (short / Holter):            ", pooled({"N"}, {"AF"}, SHORT), "/", pooled({"N"}, {"AF"}, HOLTER))
    print("  normal → sinus, no extra beats (short / Holter):", pooled({"N"}, {"SINUS"}, SHORT), "/", pooled({"N"}, {"SINUS"}, HOLTER))
    print("  normal → sinus rhythm at all (short / Holter):  ", pooled({"N"}, {"SINUS"} | ex, SHORT), "/", pooled({"N"}, {"SINUS"} | ex, HOLTER))
    print("  normal (annotated) → extra beats reported:", pooled({"N"}, ex, ("mitdb",) + HOLTER[1:]))
    print("  early beats reported (V,S,VS → sinus + extra beats):", pooled({"V", "S", "VS"}, ex))
    print("  ventricular early beats named V (V → kind with V):  ", kind("V", lambda k: "V" in k))
    print("  supraventricular named S (S → kind S only):         ", kind("S", lambda k: k == "S"))
    print("  early beats → inconclusive:              ", pooled({"V", "S", "VS"}, {"INC"}))
    print("  early beats → AF:                        ", pooled({"V", "S", "VS"}, {"AF"}))
    print("  flutter → AF alert (FL → AF):            ", pooled({"FL"}, {"AF"}), " → sinus + extra beats:", pooled({"FL"}, {"SINUS+X", "FREQ"}))
    print("  sinus bradycardia → low rate (SB → LOW): ", pooled({"SB"}, {"LOW"}))
    print("  other rhythms → AF:                      ", pooled({"O"}, {"AF"}))
    print("  inconclusive overall (N,A,O,V,S,VS):     ", pooled({"N", "A", "O", "V", "S", "VS"}, {"INC"}))


def export(model, features, classes, thresholds, info):
    """HistGradientBoosting trees in RhythmModel.kt's format: leaf values already include the
    learning rate, so learningRate is 1 and init is the baseline raw score. A binary model has one
    tree per round (the "yes" logit); the "no" class gets a constant 0 logit."""
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
    init = [float(v) for v in np.ravel(model._baseline_prediction)]
    if len(classes) == 2 and len(init) == 1:
        init = [0.0] + init
        trees = [[{"feature": [0], "threshold": [0.0], "left": [-1], "right": [-1], "value": [0.0]}] + stage for stage in trees]
    return {
        "features": features,
        "classes": classes,
        "learningRate": 1.0,
        "init": init,
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


def make():
    return HistGradientBoostingClassifier(max_iter=300, learning_rate=0.06, max_depth=4, l2_regularization=1.0, random_state=0)


def train(X, y, g, n_classes=len(CLASSES), max_weight=10.0):
    """Patient-grouped 5-fold out-of-fold probabilities and the final model on everything. Classes
    are balanced, but no recording counts more than max_weight times (noisy ones are rare)."""
    counts = np.bincount(y, minlength=n_classes)
    w = np.array([min(len(y) / (n_classes * counts[c]), max_weight) for c in y])
    oof = np.zeros((len(y), n_classes))
    for tr, te in StratifiedGroupKFold(5, shuffle=True, random_state=0).split(X, y, g):
        oof[te] = make().fit(X[tr], y[tr], sample_weight=w[tr]).predict_proba(X[te])
    return oof, make().fit(X, y, sample_weight=w)


def sinus_beneath_threshold(pb, oof, rows, t):
    """Lowest threshold at which ≤ 1 % of all AFib recordings and ≤ 10 % of flutter, SVT and VT
    recordings end up "sinus with extra beats" through this model, counting only recordings that
    reach it (not already AFib, flutter, SVT, high or low rate, or sinus). "Other" segments (sinus
    mixed with short runs of other rhythms; CinC's "other rhythm") are reported, not limited."""
    labels = np.array([r["label"] for r in rows])
    groups = np.array([r["group"] for r in rows])
    reach = np.array([decide(p, float(r["bpm"]), r["reason"], dict(t, sinus_beneath=1.0), r, 0.0) == "INC" and bool(ectopy(r)[0])
                      for p, r in zip(oof, rows)])
    af = labels == "A"
    other = np.isin(labels, ["O"]) & (groups != "cinc2017")
    runs = np.isin(labels, ["FL", "SVT", "VT"])
    ectopic = np.isin(labels, ["V", "S", "VS"])
    print("-- sinus beneath early beats: threshold trade-off (share of all recordings of each kind)")
    for th in (0.3, 0.5, 0.7, 0.8, 0.9, 0.95):
        print(f"  {th:.2f}: early beats {100 * np.mean(reach[ectopic] & (pb[ectopic] >= th)):.0f}%  "
              f"AFib {100 * np.mean(reach[af] & (pb[af] >= th)):.1f}%  flutter/SVT/VT {100 * np.mean(reach[runs] & (pb[runs] >= th)):.1f}%  "
              f"other {100 * np.mean(reach[other] & (pb[other] >= th)):.1f}%")

    def ok(th):
        return np.mean(reach[af] & (pb[af] >= th)) <= 0.01 and np.mean(reach[runs] & (pb[runs] >= th)) <= 0.10

    return round(float(next((th for th in np.arange(0.05, 0.99, 0.01) if ok(th)), 0.99)), 2)


def trainable(rows):
    return [r for r in rows if r["label"] in LABEL and r["group"] not in ("nstdb", "ptbxl", "chapman")]


def with_beat_shares(rows, path):
    """Out-of-fold beat counts from train_beats.py --oof-out (recordings without beats: none)."""
    shares = {r["id"]: r for r in csv.DictReader(open(path))}
    for r in rows:
        b = shares.get(r["id"])
        r["s_beat_share"] = b["s_beat_share"] if b else "0"
        r["v_beat_share"] = b["v_beat_share"] if b else "0"
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("features", nargs="+")
    ap.add_argument("--beat-shares")
    ap.add_argument("--out", help="rhythm model json")
    ap.add_argument("--sinus-out", help="sinus-with-early-beats model json")
    a = ap.parse_args()
    names, rows = load(a.features)
    if a.beat_shares:
        rows = with_beat_shares(rows, a.beat_shares)
    train_rows = trainable(rows)
    X = np.array([[float(r[n]) for n in names] for r in train_rows])
    y = np.array([CLASSES.index(LABEL[r["label"]]) for r in train_rows])
    g = np.array([group(r) for r in train_rows])
    oof, final = train(X, y, g)
    # Limits are set on recordings without early beats (N, SB): those with early beats are "other".
    t = thresholds(oof, train_rows, y)

    # Sinus beneath early beats: trained where the beat model reports some.
    flagged = np.array([bool(ectopy(r)[0]) for r in train_rows])
    yb = np.array([r["label"] in SINUS_BENEATH for r in train_rows]).astype(int)
    ambiguous = np.array([r["group"] == "cinc2017" and r["label"] == "O" for r in train_rows])
    fi = np.where(flagged & ~ambiguous)[0]
    pb = np.zeros(len(train_rows))
    ob, final_b = train(X[fi], yb[fi], g[fi], n_classes=2)
    pb[fi] = ob[:, 1]
    t["sinus_beneath"] = sinus_beneath_threshold(pb, oof, train_rows, t)
    summary(report("cross-validated", train_rows, oof, t, pb))

    held = [r for r in rows if r["group"] == "nstdb" or (r["label"] in ("X", "XF", "P") and r["group"] != "cinc2017")]
    if held:
        Xh = np.array([[float(r[n]) for n in names] for r in held])
        report("never trained on (NSTDB noise; partial AFib/flutter; paced)", held, final.predict_proba(Xh), t,
               final_b.predict_proba(Xh)[:, 1])
    imp = sorted(zip(np.abs(oof - oof.mean(0)).mean(0), CLASSES), reverse=True)
    print("class spread", [(c, round(float(v), 3)) for v, c in imp])
    if a.out:
        info = (f"HistGBT 300x{len(CLASSES)} depth 4, trained on {len(y)} recordings "
                f"({dict(Counter(LABEL[r['label']] for r in train_rows))}); CV grouped by patient")
        m = export(final, names, CLASSES, t, info)
        assert np.abs(predict_exported(m, X[::50]) - final.predict_proba(X[::50])).max() < 1e-6
        with open(a.out, "w") as f:
            json.dump(m, f, separators=(",", ":"))
        print("wrote", a.out, f"({len(json.dumps(m)) // 1024} kB)")
    if a.sinus_out:
        info = f"HistGBT 300 depth 4 on {len(fi)} recordings with early beats reported; CV grouped by patient"
        m = export(final_b, names, ["no", "yes"], {"yes": t["sinus_beneath"]}, info)
        assert np.abs(predict_exported(m, X[fi][::20]) - final_b.predict_proba(X[fi][::20])).max() < 1e-6
        with open(a.sinus_out, "w") as f:
            json.dump(m, f, separators=(",", ":"))
        print("wrote", a.sinus_out, f"({len(json.dumps(m)) // 1024} kB)")


if __name__ == "__main__":
    main()
