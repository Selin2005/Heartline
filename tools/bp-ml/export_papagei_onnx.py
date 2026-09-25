"""Exports the PaPaGei-S PPG encoder (Nokia Bell Labs, BSD-3-Clause) to ONNX for the phone.

    git clone https://github.com/Nokia-Bell-Labs/papagei-foundation-model papagei
    curl -L -o papagei_s.pt "https://zenodo.org/records/13983110/files/papagei_s.pt?download=1"
    python export_papagei_onnx.py --repo papagei --weights papagei_s.pt \
        --out ../../phone/src/main/assets/papagei_s_int8.onnx

Only the 512-d embedding is exported (the MoE heads are dropped). The fp32 graph is checked
against PyTorch, then weights are quantized to int8 (dynamic) and checked again.
"""
import argparse
import sys

import numpy as np
import onnxruntime as ort
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic


class Encoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):
        return self.model(x)[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--weights", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    sys.path.insert(0, a.repo)
    from models.resnet import ResNet1DMoE

    model = ResNet1DMoE(in_channels=1, base_filters=32, kernel_size=3, stride=2, groups=1, n_block=18, n_classes=512, n_experts=3)
    state = torch.load(a.weights, map_location="cpu")
    model.load_state_dict({(k[7:] if k.startswith("module.") else k): v for k, v in state.items()})
    enc = Encoder(model).eval()

    x = torch.randn(2, 1, 1250)
    fp32 = a.out.replace(".onnx", "_fp32.onnx")  # intermediate; not bundled
    torch.onnx.export(enc, x, fp32, input_names=["ppg"], output_names=["embedding"],
                      dynamic_axes={"ppg": {0: "batch"}, "embedding": {0: "batch"}}, opset_version=17, dynamo=False)
    with torch.inference_mode():
        ref = enc(x).numpy()
    got = ort.InferenceSession(fp32, providers=["CPUExecutionProvider"]).run(None, {"ppg": x.numpy()})[0]
    print("fp32 max abs diff", float(np.abs(ref - got).max()))

    quantize_dynamic(fp32, a.out, weight_type=QuantType.QInt8)
    q = ort.InferenceSession(a.out, providers=["CPUExecutionProvider"]).run(None, {"ppg": x.numpy()})[0]
    cos = (ref * q).sum(1) / (np.linalg.norm(ref, axis=1) * np.linalg.norm(q, axis=1))
    print("int8 cosine similarity to fp32", cos)


if __name__ == "__main__":
    main()
