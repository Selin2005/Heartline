# tools/ecg-eval: ECG evaluation and model training

Reproduces the numbers in [docs/algorithms/ECG_ALGORITHM.md](../../docs/algorithms/ECG_ALGORITHM.md)
and trains the models the watch ships (`shared/src/main/resources/ecg/`): the rhythm model, the
sinus-beneath-early-beats model, the beat model and the long-PR model (algorithm 4).

```bash
tools/ecg-eval/run_all.sh <work dir> <python with numpy scipy wfdb scikit-learn>
```

| Script | What it does |
|---|---|
| `run_all.sh` | End to end: downloads CinC 2017, MIT-BIH Arrhythmia, NSTDB, the AF Database, CPSC 2021, SVDB, INCART, LTAFDB, PTB-XL (+ PTB-XL+) and Chapman-Shaoxing / Ningbo from PhysioNet's open-data mirror (resumable; skips what's already there), converts them, exports features with the app's own Kotlin code (`EcgDatasetReport` in `shared` tests), trains the four models, re-runs the app with them and evaluates it. |
| `convert.py` | Converts each database into the format `EcgDatasetReport` reads: 500 Hz single-lead recordings (30 s segments of the long recordings, lead I of the 12-lead ones), an `index.csv` of labels, reference beats and their classes (N / S / V) where annotated. |
| `train_beats.py` | Trains the beat model (normal / supraventricular / ventricular beats) on the annotated beats, picks its thresholds at the recording level and writes each recording's out-of-fold early-beat counts for the rhythm models. |
| `train_rhythm.py` | Trains the rhythm model and the sinus-beneath-early-beats model with patient-grouped 5-fold cross-validation, picks the thresholds and writes both JSONs. `decide()` mirrors `EcgAnalyzer.decide`. |
| `train_morphology.py` | Trains the median-beat models (long PR, which ships; wide QRS, which doesn't) on the 10 s databases. |
| `evaluate_short.py` | What the app shows on the 10 s databases (never trained on for rhythm), and its PR/QRS against PTB-XL+. |

## Results
| File | Contents |
|---|---|
| `results/train-cv.txt` | Cross-validated results of the shipped rhythm models (algorithm 4) |
| `results/train-beats-cv.txt` | Cross-validated beat model: beat and recording level, per database |
| `results/train-morphology-cv.txt` | Cross-validated long-PR and wide-QRS models |
| `results/short-records.txt` | The app on PTB-XL and Chapman (10 s, lead I) |
| `results/app-report.txt` | The app with its shipped models on every 30 s database (in sample), with QRS detection F1 |
| `results/second-opinion-cv.txt` | Cross-validated results with the phone's ECGFounder second opinion ([tools/ecg-ml](../ecg-ml/README.md)) |
| `results/baseline-algorithm3-cv.txt`, `results/baseline-algorithm2-*.txt` | The previous algorithms, for comparison |

The datasets are not stored in the repository.
