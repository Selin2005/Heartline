# tools/ecg-ml: ECGFounder as the phone's ECG second opinion

Question: does a large pretrained ECG model add anything to the app's own rhythm model
(`docs/ECG_ALGORITHM.md`), and can it run on the phone?

## Model

[ECGFounder](https://github.com/PKUDigitalHealth/ECGFounder) (Li et al., *NEJM AI* 2025, MIT
licence), trained on 10.8 M ECGs with 150 labels; the single-lead checkpoint
(`1_lead_ECGFounder.pth`, 30.9 M parameters) from
[Hugging Face](https://huggingface.co/PKUDigitalHealth/ECGFounder). In the FOUND-AF benchmark
(arXiv 2608.03597) it was the best of nine ECG foundation models for AFib.

## Result

First on a CinC subset (2842 recordings, `evaluate_founder.py`), then on everything: all CinC 2017
recordings ≥ 20 s and all MIT-BIH segments, NSTDB kept out for the noise check
(`train_second_opinion.py`, `tools/ecg-eval/results/second-opinion-cv.txt`). Both 5-fold, grouped
by patient, thresholds holding false AFib on normal recordings at 1 %.

| | Subset: app | Subset: + ECGFounder | All: app | All: + ECGFounder |
|---|---|---|---|---|
| CinC AFib recognised | 80 % | 86 % | 83 % | 84 % |
| MIT-BIH AFib recognised | – | – | **100 %** | 89 % |
| CinC normal → sinus | 83 % | 86 % | 84 % | 86 % |
| MIT-BIH normal → sinus | – | – | 78 % | **87 %** |
| CinC other rhythms → AFib | 7 % | 8 % | 9 % | 7 % |
| MIT-BIH other rhythms → AFib | – | – | 17 % | **8 %** |
| MIT-BIH extra beats → AFib | – | – | 3 % | 0 % |
| Noisy → poor | 82 % | 87 % | 81 % | 84 % |
| NSTDB noise → AFib | – | – | 0–2 % | 0 % |

With the app's model trained on all the data, ECGFounder no longer finds more AFib (CinC +1,
MIT-BIH −11 points); it mainly cuts false AFib on other arrhythmias and recognises more normal
recordings. ECGFounder's own "atrial fibrillation" output alone, untrained on this data: AUC 0.98.

**Decision:** not bundled. The gain doesn't justify 62 MB, and it would lose AFib on MIT-BIH.
Worth revisiting with real watch recordings (their noise and amplitude differ from these
databases) or as a distilled small model.

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

Preprocessing: 500 Hz, 50 Hz notch (scipy `iirnotch`, Q 30; `Biquad.iirNotch` matches its coefficients exactly), up to three 10 s windows, z-scored, mean of sigmoid
outputs (`EcgFounderInput` in `shared` mirrors this).
