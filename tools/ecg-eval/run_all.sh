#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
# Full ECG evaluation and model training (algorithm 4), reproducible end to end.
#   tools/ecg-eval/run_all.sh <work dir> <python with numpy scipy wfdb scikit-learn>
# 1. downloads CinC 2017, MIT-BIH Arrhythmia, NSTDB, the AF Database, CPSC 2021, SVDB, INCART,
#    LTAFDB, PTB-XL (+ PTB-XL+) and Chapman-Shaoxing/Ningbo from PhysioNet's open-data mirror
#    (PN=https://physionet.org/files to use the main site; ~12 GB); resumes and skips what's there,
# 2. converts them (convert.py),
# 3. exports features, beats and median beats with the app's own Kotlin code (EcgDatasetReport),
# 4. trains the beat model, the rhythm and sinus-beneath-early-beats models and the long-PR model
#    with patient-grouped cross-validation and writes them into shared/ (results/*.txt),
# 5. re-runs the app with the new models and evaluates it on the 10 s databases.
set -euo pipefail
W=${1:?work dir}; PY=${2:-python3}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PN=${PN:-https://physionet-open.s3.amazonaws.com}
export PN
# get <path> <file>: resumable (a broken transfer continues from its .part) and retried. A file the
# mirror doesn't have (NSTDB) comes from physionet.org; one that doesn't exist at all (MIT-BIH has
# no .qrs) is skipped.
get() {
  [ -s "$2" ] && return 0
  mkdir -p "$(dirname "$2")"
  local base
  for base in "$PN" https://physionet.org/files; do
    for i in 1 2 3 4 5 6 7 8; do
      curl -sS -f --max-time 900 -C - -o "$2.part" "$base/$1" 2>/dev/null && mv "$2.part" "$2" && return 0
      if [ ! -s "$2.part" ]; then
        rm -f "$2.part"
        [ "$(curl -sS -o /dev/null -w '%{http_code}' "$base/$1")" = 404 ] && break
      fi
      sleep $i
    done
  done
  [ -s "$2" ] || [ ! -e "$2.part" ] || { echo "download failed: $1" >&2; return 1; }
}
export -f get
# Prints "<path> <file>" for every file of a database's records.
files() { # db version exts...
  local db=$1 v=$2; shift 2
  get $db/$v/RECORDS $db/RECORDS
  for r in $(cat $db/RECORDS); do for e in "$@"; do echo "$db/$v/$r.$e $db/$(basename $r).$e"; done; done
}
mkdir -p "$W/raw"
cd "$W/raw"
get challenge-2017/1.0.0/REFERENCE-v3.csv REFERENCE-v3.csv
[ -d training2017 ] || { get challenge-2017/1.0.0/training2017.zip training2017.zip && unzip -q training2017.zip; }
for f in ptbxl_database.csv scp_statements.csv RECORDS; do get ptb-xl/1.0.3/$f ptbxl/$f; done
get ptb-xl-plus/1.0.1/features/12sl_features.csv ptbxl/plus_12sl_features.csv
get ecg-arrhythmia/1.0.0/ConditionNames_SNOMED-CT.csv chapman/ConditionNames_SNOMED-CT.csv
get ecg-arrhythmia/1.0.0/RECORDS chapman/RECORDS
{
  files mitdb 1.0.0 hea dat atr
  files nstdb 1.0.0 hea dat atr
  files afdb 1.0.0 hea dat atr qrs
  files cpsc2021 1.0.0 hea dat atr
  files svdb 1.0.0 hea dat atr
  files incartdb 1.0.0 hea dat atr
  files ltafdb 1.0.0 hea dat atr
  # PTB-XL at 500 Hz (its RECORDS lists the 100 Hz copies).
  sed 's/records100/records500/; s/_lr$/_hr/' ptbxl/RECORDS | while read -r r; do
    for e in hea dat; do echo "ptb-xl/1.0.3/$r.$e ptbxl/$r.$e"; done
  done
  for d in $(cat chapman/RECORDS); do
    get ecg-arrhythmia/1.0.0/${d}RECORDS chapman/${d}RECORDS
    for r in $(cat chapman/${d}RECORDS); do for e in hea mat; do echo "ecg-arrhythmia/1.0.0/$d$r.$e chapman/$d$r.$e"; done; done
  done
} | xargs -P 16 -n 2 bash -c 'get "$0" "$1"'

cd "$ROOT/tools/ecg-eval"
R=$W/raw
[ -f "$W/cinc/index.csv" ] || $PY convert.py --cinc "$R" --out "$W/cinc"
[ -f "$W/mit/index.csv" ] || $PY convert.py --mitdb "$R/mitdb" --out "$W/mit"
[ -f "$W/nstdb/index.csv" ] || $PY convert.py --nstdb "$R/nstdb" --out "$W/nstdb"
[ -f "$W/afdb/index.csv" ] || $PY convert.py --afdb "$R/afdb" --out "$W/afdb"
[ -f "$W/cpsc/index.csv" ] || $PY convert.py --cpsc "$R/cpsc2021" --out "$W/cpsc"
[ -f "$W/holter/index.csv" ] || $PY convert.py --svdb "$R/svdb" --incart "$R/incartdb" --ltafdb "$R/ltafdb" --out "$W/holter"
[ -f "$W/ptbxl/index.csv" ] || $PY convert.py --ptbxl "$R/ptbxl" --out "$W/ptbxl"
[ -f "$W/chapman/index.csv" ] || $PY convert.py --chapman "$R/chapman" --out "$W/chapman"

LONG="mit cpsc holter afdb cinc nstdb"
SHORT="ptbxl chapman"
cd "$ROOT"
# report <set> <outputs...>: the app's analysis of one converted database.
report() {
  local set=$1; shift
  local min=20
  case $set in ptbxl|chapman) min=8;; esac
  env ECG_MIN_SEC=$min ECG_DATASET="$W/$set" "$@" ./gradlew :shared:test --tests '*EcgDatasetReport*' --rerun -q
}
for set in $LONG; do report $set ECG_FEATURES_OUT="$W/feat-$set.csv" ECG_BEATS_OUT="$W/beats-$set.csv"; done
for set in $SHORT; do report $set ECG_FEATURES_OUT="$W/feat-$set.csv" ECG_MORPH_OUT="$W/morph-$set.csv"; done
RES=shared/src/main/resources/ecg
OUT=tools/ecg-eval/results
$PY tools/ecg-eval/train_beats.py $(for s in $LONG; do echo "$W/beats-$s.csv"; done) \
  --features $(for s in $LONG; do echo "$W/feat-$s.csv"; done) \
  --out $RES/beat_model.json --oof-out "$W/beat-shares.csv" | tee $OUT/train-beats-cv.txt
$PY tools/ecg-eval/train_rhythm.py $(for s in $LONG; do echo "$W/feat-$s.csv"; done) --beat-shares "$W/beat-shares.csv" \
  --out $RES/rhythm_model.json --sinus-out $RES/sinus_beneath_model.json | tee $OUT/train-cv.txt
# The long-PR model ships; the wide-QRS one is trained and reported, but stays out (too few found).
$PY tools/ecg-eval/train_morphology.py --morph "$W"/morph-{ptbxl,chapman}.csv --features "$W"/feat-{ptbxl,chapman}.csv \
  --measures "$W/ptbxl/measures.csv" --specificity 0.95 --out-dir "$W" | tee $OUT/train-morphology-cv.txt
cp "$W/long_pr_model.json" $RES/long_pr_model.json

# The app with its new models.
for set in $LONG $SHORT; do
  report $set ECG_RESULTS_OUT="$W/results-$set.csv" ECG_REPORT_OUT="$W/report-$set.txt"
done
$PY tools/ecg-eval/evaluate_short.py "$W"/results-{ptbxl,chapman}.csv --measures "$W/ptbxl/measures.csv" | tee $OUT/short-records.txt
cat $(for s in $LONG; do echo "$W/report-$s.txt"; done) > $OUT/app-report.txt
