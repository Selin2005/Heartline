# tools/ecg-eval: ECG evaluation and rhythm model training

Reproduces the numbers in [docs/algorithms/ECG_ALGORITHM.md](../../docs/algorithms/ECG_ALGORITHM.md)
and trains the rhythm model the watch ships (`shared/src/main/resources/ecg/rhythm_model.json`).

```bash
tools/ecg-eval/run_all.sh <work dir> <python with numpy scipy wfdb scikit-learn>
```

| Script | What it does |
|---|---|
| `run_all.sh` | End to end: downloads CinC 2017, MIT-BIH Arrhythmia, NSTDB, the AF Database and CPSC 2021 from PhysioNet (skips what's already there), converts them, exports features with the app's own Kotlin code (`EcgDatasetReport` in `shared` tests), trains the model and re-runs the report. |
| `convert.py` | Converts each database into the format `EcgDatasetReport` reads: 500 Hz single-lead recordings (30 s segments of the Holter databases), an `index.csv` of labels and reference beats where known. |
| `train_rhythm.py` | Trains the rhythm model with patient-grouped 5-fold cross-validation, picks the thresholds and writes the model JSON. |

## Results
| File | Contents |
|---|---|
| `results/train-cv.txt` | Cross-validated results of the shipped model (algorithm 3) |
| `results/second-opinion-cv.txt` | Cross-validated results with the phone's ECGFounder second opinion ([tools/ecg-ml](../ecg-ml/README.md)) |
| `results/baseline-algorithm2-*.txt` | The previous algorithm on the same recordings, for comparison |

The datasets are not stored in the repository.
