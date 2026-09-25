"""Does a pretrained PPG encoder beat the app's own pulse-shape embedding on *your* data?

Input: the JSON the phone exports (Blood pressure → Share → BP data). For every cuff-checked
reading the target is cuff − what the watch showed; each embedder's ridge correction is scored
by leave-one-out, exactly like HybridBpModel in the app (penalty chosen by LOO, bias shrunk).

    python evaluate_embedders.py heartline-bp.json --onnx ../../phone/src/main/assets/papagei_s_int8.onnx
"""
import argparse
import json

import numpy as np

from ppg_prep import FS_WATCH, papagei_segments, upright

LAMBDAS = [0.3, 1.0, 3.0, 10.0, 30.0, 100.0]


def ridge_fit(X, y, lam):
    mean = X.mean(0)
    C = X - mean
    b = y.sum() / (len(y) + lam)
    K = C @ C.T + lam * np.eye(len(y))
    w = C.T @ np.linalg.solve(K, y - b)
    return lambda e: b + (e - mean) @ w


def loo(X, y):
    best = None
    for lam in LAMBDAS:
        errs = [abs(y[i] - ridge_fit(np.delete(X, i, 0), np.delete(y, i), lam)(X[i])) for i in range(len(y))]
        if best is None or np.mean(errs) < np.mean(best[1]):
            best = (lam, errs)
    return best


def shape_embedding(ppg):
    """Approximation of MorphologyEmbedder (median beat, 32 points + slope)."""
    y = upright(ppg)
    from scipy.signal import find_peaks
    peaks, _ = find_peaks(y, distance=int(0.35 * FS_WATCH))
    feet = [p - int(0.35 * FS_WATCH) + int(np.argmin(y[max(0, p - int(0.35 * FS_WATCH)):p])) for p in peaks if p > int(0.35 * FS_WATCH)]
    beats = [np.interp(np.linspace(a, b, 128), np.arange(len(y)), y) for a, b in zip(feet, feet[1:]) if 33 <= b - a <= 160]
    if len(beats) < 5:
        return None
    t = np.median([(b - b[0] - (b[-1] - b[0]) * np.linspace(0, 1, 128)) / max(np.ptp(b), 1e-9) for b in beats], axis=0)
    t = (t - t.min()) / max(np.ptp(t), 1e-9)
    s = t[np.linspace(0, 127, 32).astype(int)]
    return np.concatenate([s, np.gradient(s)[::2] * 4])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset")
    ap.add_argument("--onnx")
    a = ap.parse_args()
    data = json.load(open(a.dataset))
    rows = [e for e in data["entries"] if e.get("watchSystolic") is not None]
    print(f"{len(rows)} cuff-checked readings with the watch's value")
    if len(rows) < 6:
        print("Need at least 6 (the app's minimum) to say anything.")
        return
    y = np.array([e["cuffSystolic"] - e["watchSystolic"] for e in rows], dtype=float)
    print(f"watch as is: MAE {np.abs(y).mean():.1f}  mean diff {-y.mean():+.1f} ± {y.std(ddof=1):.1f} (watch − cuff)")

    shapes = [shape_embedding(e["ppg"]) for e in rows]
    keep = [i for i, s in enumerate(shapes) if s is not None]
    lam, errs = loo(np.array([shapes[i] for i in keep]), y[keep])
    print(f"pulse-shape ridge: LOO MAE {np.mean(errs):.1f} (λ={lam}, n={len(keep)})")

    if a.onnx:
        import onnxruntime as ort
        sess = ort.InferenceSession(a.onnx, providers=["CPUExecutionProvider"])
        emb = np.array([sess.run(None, {"ppg": papagei_segments(e["ppg"])[:, None, :]})[0].mean(0) for e in rows])
        lam, errs = loo(emb, y)
        print(f"PaPaGei ridge: LOO MAE {np.mean(errs):.1f} (λ={lam})")
    print("The app uses a correction only if its LOO MAE is at least 10 % below 'watch as is'.")


if __name__ == "__main__":
    main()
