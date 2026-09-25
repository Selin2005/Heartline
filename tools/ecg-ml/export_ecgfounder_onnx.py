"""Exports single-lead ECGFounder (MIT licence) to ONNX for the phone, as fp16 (62 MB).

    python export_ecgfounder_onnx.py --repo ecgfounder --out ../../phone/src/main/assets/ecg/ecgfounder_1lead_fp16.onnx

8-bit variants (dynamic, per-channel, static calibrated) distorted the outputs too much (see
README), which is why the phone uses fp16.

Input "ecg": (batch, 1, 5000) = 10 s at 500 Hz, 50 Hz-notched and z-scored per window.
Output "probabilities": (batch, 150) sigmoid label probabilities (labels in tasks.txt).
"""
import argparse
import os
import sys
import tempfile

import numpy as np
import onnxruntime as ort
import torch


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    sys.path.insert(0, a.repo)
    from net1d import Net1D

    model = Net1D(in_channels=1, base_filters=64, ratio=1, filter_list=[64, 160, 160, 400, 400, 1024, 1024],
                  m_blocks_list=[2, 2, 2, 3, 3, 4, 4], kernel_size=16, stride=2, groups_width=16,
                  verbose=False, use_bn=False, use_do=False, n_classes=150)
    model.load_state_dict(torch.load(os.path.join(a.repo, "1_lead_ECGFounder.pth"), map_location="cpu", weights_only=False)["state_dict"])
    model.eval()

    class Probabilities(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, x):
            return torch.sigmoid(self.m(x))

    wrapped = Probabilities(model).eval()
    x = torch.randn(2, 1, 5000)
    fp32 = os.path.join(tempfile.mkdtemp(), "ecgfounder_fp32.onnx")
    torch.onnx.export(wrapped, x, fp32, input_names=["ecg"], output_names=["probabilities"],
                      dynamic_axes={"ecg": {0: "batch"}, "probabilities": {0: "batch"}}, opset_version=17, dynamo=False)
    import onnx
    from onnxconverter_common import float16
    onnx.save(float16.convert_float_to_float16(onnx.load(fp32), keep_io_types=True), a.out)
    with torch.inference_mode():
        ref = wrapped(x).numpy()
    for path in (fp32, a.out):
        got = ort.InferenceSession(path, providers=["CPUExecutionProvider"]).run(None, {"ecg": x.numpy()})[0]
        print(os.path.basename(path), f"{os.path.getsize(path) / 1e6:.1f} MB", "max |Δp| vs PyTorch", float(np.abs(ref - got).max()))


if __name__ == "__main__":
    main()
