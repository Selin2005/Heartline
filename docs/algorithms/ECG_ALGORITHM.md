# ECG on the watch — algorithm 3

Heartline records a 30 s single-lead ECG (watch key + wrist electrode, 500 Hz, Samsung Health
Sensor SDK `ECG_ON_DEMAND`) and gives a wellness rhythm label with Samsung Health Monitor's
categories: sinus rhythm, signs of AFib, high / low heart rate, inconclusive, poor recording.
Heartline is a wellness app, not a medical device: none of this is a diagnosis.

## What was wrong with algorithm 2

1. **The recording started without a finger.** Any `LEAD_OFF` value other than 5 (including a
   missing value) counted as contact, and a single 20 ms packet started the countdown.
2. **Abnormal rhythms became "poor recording".** Quality was judged by beat shape and seconds'
   amplitude: ventricular beats (another shape, taller) were "noise" and "motion", AFib's f waves
   raised the beat noise, a pause was "no contact", and contact stretches were spliced together so
   intervals across a splice were bogus.
3. **Rhythm rules** had no ectopic-beat handling (extra beats → false AFib), classified AFib only
   up to 120 bpm, and counted tall T waves as beats.

Measured on public data (below): only 66 % of AFib recordings were recognised, 9 % of them came
out "poor", and 12–16 % of recordings with extra beats or other arrhythmias were called AFib.

## Algorithm 3

### Contact (`EcgRecorder`, `SdkEcgSource`)

- Contact per SDK batch from `LEAD_OFF` (`batchContact`): **no contact only when a point says 5**,
  as in Samsung's ECG sample. Algorithm 3 first required 0 (the documented "contact" value); on a
  Galaxy Watch8 the ECG then never started, so the rule is back to the one that worked, and whether
  a finger is really there is decided by the recorder below (debounce, settling, ECG-shape check).
  Samples beyond the SDK's `MIN/MAX_THRESHOLD_MV` are saturated and don't count. Raw values are
  logged under `Heartline/EcgRaw` and the recorder's decisions under `Heartline/EcgRec`
  (docs/DEVICE_TESTING.md).
- States: **waiting → arming → recording ⇄ paused**. Contact must hold 500 ms; the next 1 s is
  electrode settling and is dropped; then the last 3 s must **look like an ECG**
  (`EcgContactCheck`: 0.05–5 mV, kurtosis ≥ 4, ≥ 2 QRS with plausible intervals and similar
  heights) before the countdown starts (those 3 s are kept). After 6 s of unbroken contact a
  lenient check takes over (kurtosis ≥ 3.5, no height check), so an unusual wrist ECG — tall
  extra beats, a big T wave — can't block the start for good; flat signal, noise, drift and hum
  still don't pass. A lift under 300 ms doesn't pause;
  every gap starts a new **segment**. A short buzz marks the real start; the screen says
  "Hold still, starting…" while arming and asks for a lighter touch if it can't confirm an ECG.

### Signal quality, independent of the rhythm (`EcgQuality`, `RecordQuality`)

- Each segment is filtered and searched for beats on its own; no interval spans a splice.
- Per second, on a 4 s window: **bSQI** (agreement of Pan-Tompkins with a gradient detector) and
  **kSQI** (kurtosis). On CinC 2017 these two separate noise from ECG; pSQI did not.
- Per recording, a logistic model on the distribution of those indices (fitted on CinC 2017,
  5-fold AUC 0.94) decides "too noisy". Poor is **only** a signal verdict: too short, too much
  lead-off, flat, too noisy, too few clean beats.
- A second without a beat inside an interval between two clean beats is a **pause**, not lost
  contact. Pauses over 2 s are reported.
- Pan-Tompkins got T-wave discrimination and recovers after an artefact (thresholds are re-learnt
  after 2.5 s without a beat; before, one movement burst could blind it for the rest of the
  recording). The gradient detector rejects T waves too.

### Beats and rhythm (`BeatClusters`, `RhythmClassifier`, `RhythmModel`)

- Beats are clustered by QRS shape. The dominant cluster is the narrowest large one; a cluster
  of ≥ 3 beats that is clearly different (correlation < 0.7, width ± 30 ms or height outside
  0.6–1.6×) is a second morphology (ventricular-like), never noise.
- Early beats: short–long intervals, or an early beat of another shape. They and their
  compensatory interval are removed before judging irregularity (Petrėnas 2015); a Lorenz plot
  that falls into tight clusters is a patterned irregularity (Sensors 2023).
- The final decision comes from a **learned model** over 24 recording features (RR irregularity
  before and after removing early beats, P-wave ratio, early/other-shape beat shares, Lorenz
  shape, noise indices…): gradient-boosted trees (150 × 4 trees, depth 3), 373 kB JSON, pure
  Kotlin, so it runs on the watch. Trained on CinC 2017, MIT-BIH, the MIT-BIH AF Database and
  CPSC 2021 lead I (≈ 18 000 recordings, `tools/ecg-eval`), with the exact features the app
  computes (checked: identical). Thresholds: AFib only where ≤ 1 % of short single-lead normal
  recordings (CinC, MIT-BIH) would be called AFib (≤ 4 % on each Holter database, whose "normal"
  stretches are noisier); noisy where ≤ 3 % of normal ones would be.
- AFib is classified up to 150 bpm. Inconclusive comes with a reason (`EcgNote`): extra beats,
  frequent extra beats, irregular but not like AFib, no clear P wave, rate above 150, pauses,
  too noisy to call AFib.

## Results

Cross-validated (5 folds, grouped by patient: all segments of one recording or CPSC patient stay
in one fold; `tools/ecg-eval/results/train-cv.txt`). CinC 2017 recordings shorter than 20 s are
left out (the watch always records 30 s). AFDB and CPSC 2021 segments are 30 s samples of Holter
recordings; segments with AFib for only part of the 30 s are left out of training. "Old" is
algorithm 2 on the same recordings (`results/baseline-algorithm2-*.txt`).

| | Old | Algorithm 3 (watch) | + ECGFounder (phone) |
|---|---|---|---|
| CinC AFib recognised | 66 % | 81 % | **82 %** |
| CinC normal → called AFib | 1 % | 1 % | 1 % |
| CinC normal → sinus rhythm | 78 % | 83 % | **85 %** |
| CinC other rhythms → called AFib | 16 % | 7 % | 6 % |
| CinC noisy → poor | 85 % | 80 % | **94 %** |
| MIT-BIH AFib recognised | 96 % | **100 %** | **100 %** |
| MIT-BIH normal → sinus rhythm | – | 83 % | **91 %** |
| MIT-BIH ectopic beats → called AFib | 12 % | 4 % | **1 %** |
| MIT-BIH other rhythms → called AFib | 19 % | 8 % | 10 % |
| AF Database AFib recognised | – | 93 % | **94 %** |
| CPSC 2021 (lead I) AFib recognised | – | 89 % | **91 %** |
| CPSC 2021 normal → called AFib | – | 3 % | **1 %** |
| CPSC 2021 ectopic beats → called AFib | – | 8 % | **2 %** |
| NSTDB (noise stress, never trained on) → called AFib, 12–24 dB | 6–12 % | 2–4 % | **0 %** |

- Before AFDB and CPSC 2021 were added, the watch model (trained on CinC + MIT-BIH only) found
  87 % of AFDB's and 86 % of CPSC's AFib and called 5 % of CPSC normals AFib; the extra Holter
  data made it generalise better (93 %, 89 %, 3 %) at the cost of 2 points on CinC.
- Results at wrist amplitude (signal × 0.3) are the same as at full amplitude.
- QRS detection F1 (MIT-BIH, ± 75 ms) is 0.994–0.997 on normal, AFib and ectopic recordings, and
  0.95 on other rhythms (paced and flutter records); 0.95–0.99 on AFDB and CPSC 2021. Under NSTDB
  noise it is 0.94 at 12 dB and 0.70 at 6 dB; those recordings are called poor instead of AFib.
- **Trade-off, stated plainly:** recordings with extra beats mostly come out *inconclusive
  with "extra beats"* (MIT-BIH 87 %), where algorithm 2 said sinus (35 %) or AFib (12 %).
  That is deliberate: such a recording isn't normal sinus rhythm, and "not AFib, extra beats seen"
  is the useful answer.

### Second opinion on the phone (ECGFounder)

The phone runs the ECGFounder foundation model (NEJM AI 2025, MIT licence; single-lead, fp16,
62 MB in the APK) on every synced ECG and feeds its 150 label probabilities, with the app's own
features, into a second tree model (`phone/src/main/assets/ecg/second_opinion_model.json`). Its
result is shown as "Second opinion (phone AI)" next to the watch's, never instead of it. On CinC +
MIT-BIH alone it lost AFib on MIT-BIH (100 → 89 %: five AFib patients are too few to learn from);
with AFDB and CPSC 2021 it is better than the watch model on every dataset above. Details:
`tools/ecg-ml/README.md`.

## Re-running

```bash
tools/ecg-eval/run_all.sh /tmp/ecg-work python3   # downloads, converts, evaluates, retrains
ECG_DATASET=/tmp/ecg-work/cinc ./gradlew :shared:test --tests '*EcgDatasetReport*' --rerun -i
```

The model must be retrained whenever feature code changes (`RhythmFeatures`, detectors,
quality), otherwise the app computes different features than the model was trained on.

## Limits

- Wellness only: no diagnosis. Public databases are clinical recordings, not wrist lead I from a
  Galaxy Watch; real watch recordings (Share → export) are needed to confirm these numbers.
- Pulse arrival time is measured during ECG (see `docs/algorithms/BP_ALGORITHM.md`).

## References

Pan & Tompkins 1985 (QRS); Li, McSharry & Clifford 2008 and Clifford et al. 2012 (SQIs); Zhao &
Zhang 2018 (single-lead SQI fusion); Makowski et al. 2021 (NeuroKit2 gradient detector); Petrėnas
et al. 2015 (ectopic filtering); "Regularity within irregularity", Sensors 2023;23:9283; Clifford
et al. 2017 (PhysioNet/CinC Challenge); Moody & Mark 2001 (MIT-BIH); Moody, Muldrow & Mark 1984
(NSTDB); Moody & Mark 1983 (MIT-BIH AF Database); Wang et al. 2021 (CPSC 2021, paroxysmal AF
challenge); Li et al. 2025 (ECGFounder, NEJM AI).
