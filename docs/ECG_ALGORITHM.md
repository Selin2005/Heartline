# ECG on the watch — algorithm 3

Heartline records a 30 s single-lead ECG (watch key + wrist electrode, 500 Hz, Samsung Health
Sensor SDK `ECG_ON_DEMAND`) and gives a wellness rhythm label with Samsung Health Monitor's
categories: sinus rhythm, signs of AFib, high / low heart rate, inconclusive, poor recording.
The plan and the research behind this version are in `docs/ECG_ACCURACY_PLAN.md`.

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

- Contact only when every point reports `LEAD_OFF == 0` (the SDK documents 0 = contact,
  5 = none); samples beyond the SDK's `MIN/MAX_THRESHOLD_MV` are saturated and don't count. The
  LEAD_OFF values seen are logged per session.
- States: **waiting → arming → recording ⇄ paused**. Contact must hold 500 ms; the next 1 s is
  electrode settling and is dropped; then the last 3 s must **look like an ECG**
  (`EcgContactCheck`: 0.05–5 mV, kurtosis ≥ 4, ≥ 2 QRS with plausible intervals and similar
  heights) before the countdown starts (those 3 s are kept). A lift under 300 ms doesn't pause;
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
  Kotlin, so it runs on the watch. Trained on CinC 2017 + MIT-BIH (`tools/ecg-eval`), with the
  exact features the app computes (checked: identical). Thresholds: AFib only where ≤ 1 % of
  normal recordings would be called AFib; noisy where ≤ 3 % of normal ones would be.
- AFib is classified up to 150 bpm. Inconclusive comes with a reason (`EcgNote`): extra beats,
  frequent extra beats, irregular but not like AFib, no clear P wave, rate above 150, pauses,
  too noisy to call AFib.

## Results

Cross-validated (5 folds, grouped by patient; `tools/ecg-eval/results/train-cv.txt`). CinC 2017
recordings shorter than 20 s are left out (the watch always records 30 s). "Old" is algorithm 2
on the same recordings (`results/baseline-algorithm2-*.txt`).

| | Old | Algorithm 3 |
|---|---|---|
| CinC AFib recognised | 66 % | **83 %** |
| CinC AFib → poor | 9 % | 6 % |
| CinC normal → called AFib | 1 % | 1 % |
| CinC normal → sinus rhythm | 78 % | **84 %** |
| CinC other rhythms → called AFib | 16 % | **9 %** |
| CinC noisy → poor | 85 % | 81 % |
| MIT-BIH AFib recognised | 96 % | **100 %** |
| MIT-BIH ectopic beats → called AFib | 12 % | **3 %** |
| MIT-BIH other rhythms → called AFib | 19 % | 17 % |
| NSTDB (noise stress, never trained on) → called AFib, 12–24 dB | 6–12 % | **0–2 %** |

- Results at wrist amplitude (signal × 0.3) are the same as at full amplitude.
- QRS detection F1 (MIT-BIH, ± 75 ms) is 0.994–0.997 on normal, AFib and ectopic recordings, and
  0.95 on other rhythms (paced and flutter records). Under NSTDB noise it is 0.94 at 12 dB and
  0.70 at 6 dB, the same as before; those recordings are now called poor instead of AFib.
- **Trade-off, stated plainly:** recordings with extra beats now mostly come out *inconclusive
  with "extra beats"* (MIT-BIH 88 %), where algorithm 2 said sinus (35 %) or AFib (12 %).
  That is deliberate: such a recording isn't normal sinus rhythm, and "not AFib, extra beats seen"
  is the useful answer.

### Second opinion on the phone (ECGFounder)

Adding the ECGFounder foundation model's 150 label probabilities (NEJM AI 2025) to the features
raises AFib recognition from 80 % to 86 % at the same 1 % false AFib (CinC subset, same CV). It
only runs in fp16 (62 MB; 8-bit versions distorted its outputs), so the phone uses it when the
model file is added to its assets, and shows the result as "Second opinion (phone AI)" next to
the watch's result. Details: `tools/ecg-ml/README.md`.

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
- Pulse arrival time is measured during ECG (see `docs/BP_ALGORITHM.md`).

## References

Pan & Tompkins 1985 (QRS); Li, McSharry & Clifford 2008 and Clifford et al. 2012 (SQIs); Zhao &
Zhang 2018 (single-lead SQI fusion); Makowski et al. 2021 (NeuroKit2 gradient detector); Petrėnas
et al. 2015 (ectopic filtering); "Regularity within irregularity", Sensors 2023;23:9283; Clifford
et al. 2017 (PhysioNet/CinC Challenge); Moody & Mark 2001 (MIT-BIH); Moody, Muldrow & Mark 1984
(NSTDB); Li et al. 2025 (ECGFounder, NEJM AI).
