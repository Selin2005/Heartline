# tools/ecg-ml: ECGFounder as the phone's ECG second opinion

Question: does a large pretrained ECG model add anything to the app's own rhythm model
(`docs/algorithms/ECG_ALGORITHM.md`), and can it run on the phone?

## Model

[ECGFounder](https://github.com/PKUDigitalHealth/ECGFounder) (Li et al., *NEJM AI* 2025, MIT
licence), trained on 10.8 M ECGs with 150 labels; the single-lead checkpoint
(`1_lead_ECGFounder.pth`, 30.9 M parameters) from
[Hugging Face](https://huggingface.co/PKUDigitalHealth/ECGFounder). In the FOUND-AF benchmark
(arXiv 2608.03597) it was the best of nine ECG foundation models for AFib.

## Result

First on a CinC subset (2842 recordings, `evaluate_founder.py`); then on CinC 2017 + MIT-BIH;
then with two AF databases added: the MIT-BIH AF Database (23 ten-hour Holter recordings, 1304
sampled 30 s segments) and CPSC 2021 (lead I — the watch's lead — of 105 patients' Holter
recordings, 6236 segments). 5-fold CV grouped by patient, NSTDB kept out for the noise check
(`train_second_opinion.py`, `tools/ecg-eval/results/second-opinion-cv.txt`).

With CinC + MIT-BIH only, ECGFounder cut false AFib but lost AFib on MIT-BIH (100 → 89 %): the
database has only five AFib patients, and one of them (record 203: AFib with many ventricular
beats) made most of the misses. More AFib patients fixed that:

| | App model | App + ECGFounder |
|---|---|---|
| CinC AFib recognised | 81 % | **82 %** |
| MIT-BIH AFib recognised | 100 % | **100 %** |
| AFDB AFib recognised | 93 % | **94 %** |
| CPSC 2021 AFib recognised | 89 % | **91 %** |
| CinC normal → sinus | 83 % | **85 %** |
| MIT-BIH normal → sinus | 83 % | **91 %** |
| CPSC 2021 normal → AFib | 3 % | **1 %** |
| MIT-BIH extra beats → AFib | 4 % | **1 %** |
| CPSC 2021 extra beats → AFib | 8 % | **2 %** |
| Noisy (CinC) → poor | 80 % | **94 %** |
| NSTDB noise → AFib | 2–4 % | **0 %** |

Thresholds: false AFib on normal recordings ≤ 1 % on CinC and MIT-BIH, ≤ 4 % on each Holter
database (`af_threshold` in `train_rhythm.py`). Also fixed while doing this: 30 s segments with
AFib for only part of the time were labelled "other rhythm" (885 of CPSC's 950 "other" segments),
which taught the models to miss AFib; they are now label X and kept out of training.

**Decision:** bundled (62 MB fp16). Better on every dataset; the watch's own result stays the
primary one, the phone adds its second opinion.

## On the phone

| Export | Size | Max output error vs PyTorch (real ECG) |
|---|---|---|
| fp32 ONNX | 123 MB | 0.05 |
| int8 dynamic | 32 MB | 0.72 (AFib up to 0.15) |
| int8 dynamic, per channel | 32 MB | 0.73 |
| int8 static, calibrated on 200 windows | 32 MB | 0.83 (AFib up to 0.55) |
| **fp16** | **62 MB** | **0.08 (AFib ≤ 0.02, mean 0.002)** |

This network doesn't survive 8-bit quantization; fp16 does. The phone
(`phone/.../ecg/EcgSecondOpinion.kt`) runs `assets/ecg/ecgfounder_1lead_fp16.onnx` (stored
uncompressed, copied once to app storage and memory-mapped by ONNX Runtime) with
`assets/ecg/second_opinion_model.json`: every synced ECG gets a "Second opinion (phone AI)" row in
its details, PDF and share text. To regenerate the model file:

```bash
python export_ecgfounder_onnx.py --repo ecgfounder --out ../../phone/src/main/assets/ecg/ecgfounder_1lead_fp16.onnx
```

A smaller route is distilling ECGFounder into a compact network (it could then run on the watch
too), best done once real watch recordings are available to check it on.

Preprocessing: 500 Hz, 50 Hz notch (scipy `iirnotch`, Q 30; `Biquad.iirNotch` matches its coefficients exactly), up to three 10 s windows, z-scored, mean of sigmoid
outputs (`EcgFounderInput` in `shared` mirrors this).
