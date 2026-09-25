#!/usr/bin/env bash
# Full ECG evaluation and model training, reproducible end to end.
#   tools/ecg-eval/run_all.sh <work dir> <python with numpy scipy wfdb scikit-learn>
# 1. downloads CinC 2017, MIT-BIH Arrhythmia and NSTDB from PhysioNet (skips what's there),
# 2. converts them (convert.py), 3. exports features with the app's own Kotlin code,
# 4. trains the rhythm model with patient-grouped cross-validation and writes it into shared/,
# 5. re-runs the report with the new model.
set -euo pipefail
W=${1:?work dir}; PY=${2:-python3}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
mkdir -p "$W/raw"
cd "$W/raw"
[ -f REFERENCE-v3.csv ] || curl -sSO https://physionet.org/files/challenge-2017/1.0.0/REFERENCE-v3.csv
[ -d training2017 ] || { curl -sSO https://physionet.org/files/challenge-2017/1.0.0/training2017.zip && unzip -q training2017.zip; }
for db in mitdb nstdb; do
  mkdir -p $db
  for r in $(curl -sS https://physionet.org/files/$db/1.0.0/RECORDS); do
    for ext in hea dat atr; do
      [ -s $db/$r.$ext ] || curl -sS -f -o $db/$r.$ext https://physionet.org/files/$db/1.0.0/$r.$ext || true
    done
  done
done
cd "$ROOT/tools/ecg-eval"
[ -f "$W/cinc/index.csv" ] || $PY convert.py --cinc "$W/raw" --out "$W/cinc"
[ -f "$W/mit/index.csv" ] || $PY convert.py --mitdb "$W/raw/mitdb" --nstdb "$W/raw/nstdb" --out "$W/mit"
cd "$ROOT"
for set in cinc mit; do
  ECG_DATASET="$W/$set" ECG_FEATURES_OUT="$W/feat-$set.csv" ./gradlew :shared:test --tests '*EcgDatasetReport*' --rerun -q
done
$PY tools/ecg-eval/train_rhythm.py "$W/feat-cinc.csv" "$W/feat-mit.csv" --out shared/src/main/resources/ecg/rhythm_model.json | tee "$W/train.txt"
for set in cinc mit; do
  ECG_DATASET="$W/$set" ECG_REPORT_OUT="$W/report-$set.txt" ./gradlew :shared:test --tests '*EcgDatasetReport*' --rerun -q
  cat "$W/report-$set.txt"
done
