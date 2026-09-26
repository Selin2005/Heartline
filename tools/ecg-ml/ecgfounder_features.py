# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Does ECGFounder (Li et al., NEJM AI 2025; MIT licence) add to the app's rhythm model?

Runs the single-lead ECGFounder on recordings converted by tools/ecg-eval/convert.py (500 Hz,
mV) and writes its 150 label probabilities per recording (mean over up to three 10 s windows).
evaluate_founder.py then compares the app's features alone with app + ECGFounder features in the
same patient-grouped cross-validation as train_rhythm.py.

    git clone https://github.com/PKUDigitalHealth/ECGFounder ecgfounder
    curl -L -o ecgfounder/1_lead_ECGFounder.pth https://huggingface.co/PKUDigitalHealth/ECGFounder/resolve/main/1_lead_ECGFounder.pth
    python ecgfounder_features.py --repo ecgfounder --data <converted dir> --ids <feature csv> --out founder.csv
"""
import argparse
import csv
import os
import sys

import numpy as np
import torch
from scipy.signal import filtfilt, iirnotch

FS = 500
WIN = 10 * FS


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--data", required=True)
    ap.add_argument("--ids", required=True, help="feature csv whose ids to process (first column)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    sys.path.insert(0, a.repo)
    from net1d import Net1D

    model = Net1D(in_channels=1, base_filters=64, ratio=1, filter_list=[64, 160, 160, 400, 400, 1024, 1024],
                  m_blocks_list=[2, 2, 2, 3, 3, 4, 4], kernel_size=16, stride=2, groups_width=16,
                  verbose=False, use_bn=False, use_do=False, n_classes=150)
    state = torch.load(os.path.join(a.repo, "1_lead_ECGFounder.pth"), map_location="cpu", weights_only=False)["state_dict"]
    model.load_state_dict(state)
    model.eval()
    torch.set_num_threads(os.cpu_count() or 4)
    labels = [l.strip() for l in open(os.path.join(a.repo, "tasks.txt")) if l.strip()]

    ids = [r[0] for r in list(csv.reader(open(a.ids)))[1:]]
    if a.limit:
        ids = ids[: a.limit]
    b, bn = iirnotch(50, 30, FS)
    with open(a.out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["id"] + ["ef_" + l.lower().replace(" ", "_").replace(",", "")[:40] for l in labels])
        for n, rid in enumerate(ids):
            x = np.fromfile(os.path.join(a.data, rid + ".f32"), dtype="<f4").astype(np.float64)
            x = filtfilt(b, bn, x)
            wins = [x[i:i + WIN] for i in range(0, min(len(x), 3 * WIN) - WIN + 1, WIN)]
            batch = np.stack([(s - s.mean()) / (s.std() + 1e-8) for s in wins])[:, None, :]
            with torch.inference_mode():
                p = torch.sigmoid(model(torch.tensor(batch, dtype=torch.float32))).mean(0).numpy()
            w.writerow([rid] + [f"{v:.4f}" for v in p])
            if n % 200 == 0:
                print(n, "/", len(ids), flush=True)


if __name__ == "__main__":
    main()
