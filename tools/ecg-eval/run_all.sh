#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
# Full ECG evaluation and model training, reproducible end to end.
#   tools/ecg-eval/run_all.sh <work dir> <python with numpy scipy wfdb scikit-learn>
# 1. downloads CinC 2017, MIT-BIH Arrhythmia, NSTDB and the AF Database from PhysioNet, plus the
#    CPSC 2021 headers/annotations (its signal is streamed per sampled segment); skips what's there,
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
for db in mitdb nstdb afdb; do
  mkdir -p $db
  for r in $(curl -sS https://physionet.org/files/$db/1.0.0/RECORDS); do
    for ext in hea dat atr qrs; do
      [ -s $db/$r.$ext ] || curl -sS -f -o $db/$r.$ext https://physionet.org/files/$db/1.0.0/$r.$ext || true
    done
  done
done
mkdir -p cpsc
[ -s cpsc/RECORDS ] || curl -sS -o cpsc/RECORDS https://physionet.org/files/cpsc2021/1.0.0/RECORDS
for r in $(cat cpsc/RECORDS); do
  for ext in hea atr; do
    [ -s cpsc/$(basename $r).$ext ] || curl -sS -f -o cpsc/$(basename $r).$ext https://physionet.org/files/cpsc2021/1.0.0/$r.$ext || true
  done
done
cd "$ROOT/tools/ecg-eval"
[ -f "$W/cinc/index.csv" ] || $PY convert.py --cinc "$W/raw" --out "$W/cinc"
[ -f "$W/mit/index.csv" ] || $PY convert.py --mitdb "$W/raw/mitdb" --nstdb "$W/raw/nstdb" --out "$W/mit"
[ -f "$W/afdb/index.csv" ] || $PY convert.py --afdb "$W/raw/afdb" --out "$W/afdb"
[ -f "$W/cpsc/index.csv" ] || $PY convert.py --cpsc "$W/raw/cpsc" --out "$W/cpsc"
cd "$ROOT"
for set in cinc mit afdb cpsc; do
  ECG_DATASET="$W/$set" ECG_FEATURES_OUT="$W/feat-$set.csv" ./gradlew :shared:test --tests '*EcgDatasetReport*' --rerun -q
done
$PY tools/ecg-eval/train_rhythm.py "$W"/feat-{cinc,mit,afdb,cpsc}.csv --out shared/src/main/resources/ecg/rhythm_model.json | tee tools/ecg-eval/results/train-cv.txt
for set in cinc mit afdb cpsc; do
  ECG_DATASET="$W/$set" ECG_REPORT_OUT="$W/report-$set.txt" ./gradlew :shared:test --tests '*EcgDatasetReport*' --rerun -q
  cat "$W/report-$set.txt"
done
