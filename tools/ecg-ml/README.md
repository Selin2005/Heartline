# tools/ecg-ml: ECGFounder as the phone's ECG second opinion

Question: does a large pretrained ECG model add anything to the app's own rhythm model
(`docs/ECG_ALGORITHM.md`), and can it run on the phone?

## Model

[ECGFounder](https://github.com/PKUDigitalHealth/ECGFounder) (Li et al., *NEJM AI* 2025, MIT
licence), trained on 10.8 M ECGs with 150 labels; the single-lead checkpoint
(`1_lead_ECGFounder.pth`, 30.9 M parameters) from
[Hugging Face](https://huggingface.co/PKUDigitalHealth/ECGFounder). In the FOUND-AF benchmark
(arXiv 2608.03597) it was the best of nine ECG foundation models for AFib.

## Result (CinC 2017 subset of 2842 recordings, 5-fold patient-grouped CV)

`ecgfounder_features.py` → `evaluate_founder.py` / `train_second_opinion.py`. Thresholds hold
false AFib on normal recordings at 1 %.

| | App features | App + ECGFounder |
|---|---|---|
| AFib recognised | 80 % | **86 %** |
| Normal → sinus rhythm | 83 % | **86 %** |
| Noisy → poor | 82 % | **87 %** |
| Other rhythms → called AFib | 7 % | 8 % |

ECGFounder's own "atrial fibrillation" output alone, with no training on this data: AUC 0.98.

## On the phone

| Export | Size | Max output error vs PyTorch (real ECG) |
|---|---|---|
| fp32 ONNX | 123 MB | 0.05 |
| int8 dynamic | 32 MB | 0.72 (AFib up to 0.15) |
| int8 dynamic, per channel | 32 MB | 0.73 |
| int8 static, calibrated on 200 windows | 32 MB | 0.83 (AFib up to 0.55) |
| **fp16** | **62 MB** | **0.08 (AFib ≤ 0.02, mean 0.002)** |

This network doesn't survive 8-bit quantization; fp16 does. The phone code is complete
(`phone/.../ecg/EcgSecondOpinion.kt`, model `assets/ecg/second_opinion_model.json`) and runs as
soon as `assets/ecg/ecgfounder_1lead_fp16.onnx` exists: every synced ECG then gets a
"Second opinion (phone AI)" row in its details, PDF and share text. The 62 MB file isn't committed
(it would enlarge the APK and the repository history for good); add it with:

```bash
python export_ecgfounder_onnx.py --repo ecgfounder --out ../../phone/src/main/assets/ecg/ecgfounder_1lead_fp16.onnx
```

A smaller route is distilling ECGFounder into a compact network, best done once real watch
recordings are available to check it on.

Preprocessing: 500 Hz, 50 Hz notch (Q 30), up to three 10 s windows, z-scored, mean of sigmoid
outputs (`EcgFounderInput` in `shared` mirrors this).
