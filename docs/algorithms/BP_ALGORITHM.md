# Blood pressure on the watch — algorithms 3, 4 and 5

Heartline estimates blood pressure (BP) from the watch's green PPG pulse wave. It uses the same
approach as Samsung Health Monitor: calibrated pulse-wave analysis (PWA). This document explains
what the watch measures, how the estimate is made, and what it can and cannot do.

## What changed in algorithm 3 (and why)

Algorithm 2 refused to show a number ("Outside your calibration") whenever today's pulse wave or
estimate was far from the calibration. That hid exactly the readings that matter: a pressure
that has really gone up or down. It also fired on noise, because a single noisy shape feature
(APG d/a, area ratio) crossing its limit was enough. Research on calibrated cuffless devices
shows the real failure is the opposite of refusing: readings are pulled towards the calibration
(Galaxy Watch Active2: proportional bias slope ≈ 0.56, SD 15.5 mmHg; Falter et al. 2022), and
changes need re-calibration at the change point (Tae et al. 2026). Algorithm 3:

1. **Never hides a real change.** A reading beyond the calibration is shown, with a wider ±,
   flagged "beyond your calibration range", and the user is asked to measure again. A second
   reading within 10 minutes that points the same way marks it **confirmed**.
2. **Safety wording.** ≥ 180 and/or ≥ 120 (very high) or < 90/60 (low) adds a check-with-a-cuff
   message and, when confirmed, a phone notification. Wellness wording, never a diagnosis.
3. **Refuses only bad signal.** A shape no real pulse has (upstroke longer than 60 % of the beat,
   pulse width longer than the beat, heart rate outside 30–200) or a marginal recording (quality
   < 0.7) whose core shape is also far off → "Unsteady signal, try again". Arm movement measured
   by the accelerometer (> 0.6 m/s² SD) → "Keep your arm still". Noisy shape features that jump
   on their own are simply ignored for that reading.
4. **Learns from cuff checks.** Every "compare with cuff" becomes a calibration point (up to 12).
   The calibration then spans a real pressure range, and its baseline follows the user's drift.
5. **Detects drift.** Three readings in a row beyond the calibration in the same direction ask
   for a cuff check; cuff checks that keep disagreeing the same way (CUSUM) ask to recalibrate.
6. **Learns each user's normal spread** of the features from their recent in-range readings.
7. **New feature:** the reflected-wave delay (systolic peak → diastolic peak/inflection, the
   stiffness-index timing; Millasseau 2002), which shortens as pressure rises.

Algorithm 4 is the phone's personal learned model, described below.

## What changed in algorithm 5 (and why)

A user with a usual pressure of 104/70 felt dizzy and short of breath right after using the
toilet (a vasovagal / orthostatic drop). The watch showed **147/93 ±13, pulse 120, beyond
calibration**: the opposite of what was happening. The cause was in the model, not the sensor:

1. **The pulse rate drove the estimate.** The prior sensitivity was +0.45 mmHg per bpm, linear
   and unbounded. Pulse 120 against a calibration at about 70 added about 22 mmHg by itself. Within
   a person, the rate is a weak pressure signal. After standing up, in a vasovagal episode, with
   dehydration, anaemia, fever, blood loss, POTS or AF, the pulse races while pressure stays the
   same or falls.
2. **The rate was counted twice.** Ejection shortens as the rate rises (LVET ≈ 413 − 1.7·HR ms,
   Weissler 1968). Upstroke, pulse width and reflection delay therefore shorten with the rate
   alone, and the model read that as "stiffer, higher pressure".
3. **Sympathetic vasoconstriction at the wrist** narrows the wave even more, although central
   pressure is low. Its signature is a small pulse relative to the light level (low perfusion
   index), which was not measured.
4. **There was no notion of state.** A pulse still settling after standing, a pulse amplitude
   still recovering, and an irregular rhythm were all treated as a steady resting recording.

Algorithm 5 rests on one principle, which the ESH 2023 recommendations, ISO 81060-3 and the
2015–2025 cuffless reviews all point to: a calibrated cuffless reading is only valid in the
steady state it was calibrated in, so first decide whether the body is in that state.

| Step | What | Where |
|---|---|---|
| Rhythm gate | Premature beats (a short interval followed by a compensatory pause) are detected. Each premature beat, its pause and the stronger post-extrasystolic beat are left out of the ensemble. An irregular rhythm (interval CV > 0.15, ≥ 3 premature beats or > 40 % of beats rejected) gives **no number**, like validated cuffs do in AF. This applies only when the pulses themselves are clean (noise also gives irregular spacing). | `PpgFeatures.rhythm`, `HemodynamicStateClassifier` |
| State features (extractor v4) | Interval CV, premature-beat count, rejected share, pulse-rate trend (bpm/s), **perfusion index** (AC/DC of the raw light level) and the amplitude trend over the recording. | `PpgFeatureVector` v4 |
| State classifier | **COMPENSATORY**: pulse > 25 bpm above calibration **and** perfusion index < 0.6 × calibration (15 bpm and 0.75 with POTS). **TRANSIENT**: rate changing > 0.5 bpm/s, amplitude changing > 35 %, or the arm more than 35° from every calibration position (gravity vector from the accelerometer). None of these give a number. When a compensating pattern also shows a falling rate or a changing pulse, "a drop in pressure fits this pattern" advice is shown instead. | `HemodynamicState.kt` |
| Rate decoupling | Timing features are moved to 70 bpm (0.5, 1.2 and 0.9 ms/bpm for upstroke, width and reflection delay: below the LVET slope, because only part of each interval is ejection). The rate term is bounded with tanh at ±6 / ±4 mmHg. | `BpEstimator.corrected`, `HR_CAP_*` |
| Honest output | If the rate term is ≥ 4 mmHg and outweighs the shape change, the reading is *rate-dominated*: ± widens, it is flagged, it is shown as a **range without a category**, and it is never "very high". A reading with ± > 12 is also shown as a range. The ± also grows with a changed perfusion index, premature beats and AF. | `BpEstimate.rangeOnly`, `BpSafety` |
| Health profile | Stored with the calibration and editable on the phone. Beta blocker or pacemaker: the rate is left out of the fit and the estimate. POTS / orthostatic hypotension: the same, plus a more sensitive compensatory check. AF: a 45 s recording, no rhythm gate, wider ±. Pregnancy: "not validated, use a cuff". Diabetes, kidney disease or age ≥ 65: the calibration is valid for 14 days and the priors are wider. | `BpProfile` |
| Standing round | An optional 4th calibration reading while standing (watch arm at heart level). The fit then sees how this user's pulse and pressure respond to standing, which is where the rate misleads most. | `CalibrationViewModel`, `BpCalibration.STANDING_ROUND` |
| Evaluation | The replay report counts unsteady refusals separately, and gives the MAE of readings whose pulse was > 15 bpm above calibration. | `BpEvaluationReport` |

**Trade-off, stated plainly.** Without the rate term, the shape features alone move the estimate
less (the old model got much of its sensitivity from the rate). A genuine rise in pressure still
shows in the right direction, and it is tracked in full once cuff checks have taught the fit this
user's slopes (`BpAlgorithm5Test.aRealRiseInPressureIsStillShown`). A confident wrong number is
worse than an honest "measure again".

**Why not only a neural network?** For heart rate the signal labels itself (R–R intervals). For
pressure the label must come from a cuff, and public datasets (MIMIC, VitalDB, PulseDB) are
finger PPG from ICU and surgery. On other people and wrist PPG they fall to about 14/8.5 mmHg MAE,
and they contain almost no wrist vasovagal or orthostatic episodes. A network would learn the
same "fast pulse = high pressure" shortcut on exactly the out-of-distribution case above. So the
learned part stays personal and gated (algorithm 4). It is used only on steady, single-number
readings (never on range-only ones), and the v4 state features are now stored with every reading
so a wrist model can later be trained and replayed on real data.

## Why the first version kept giving the same numbers

The watch test showed the same reading every time. Three causes combined:

1. **Demo calibration.** Debug phone builds seeded a *synthetic* calibration and sent it to the
   watch. Real pulse waves were nowhere near its features, so every estimate hit the limit below.
2. **Hidden clamp.** Estimates were clamped to ±25/±15 mmHg around the calibration mean. When the
   features are far off, the clamp returns the same value every time.
3. **Upside-down PPG.** Raw Galaxy Watch green PPG is light intensity. It *falls* when blood
   volume rises, so the pulse is upside down. The extractor assumed upright pulses, so on real data
   it measured troughs as "systolic peaks".

Algorithm 2 fixes all three:
- the demo calibration is never seeded, and existing copies are purged on phone and watch;
- the clamp is removed;
- polarity is detected automatically.

## What is measured (PpgFeatures v3, 100 Hz PPG_ON_DEMAND green, 20 s)

1. Band-pass 0.5–8 Hz, zero phase.
2. **Polarity:** arterial pulses rise quickly and fall slowly. If the steepest slopes are negative,
   the signal is flipped.
3. Systolic peaks, then each pulse foot (the minimum in the 350 ms before the peak).
4. Beats of plausible length (0.33–1.6 s). Only beats within 20 % of the median length are kept, so
   an ectopic beat or a missed foot doesn't smear the average.
5. Each beat is normalised (foot 0, peak 1), stretched to the median length at 4× resolution, and
   combined into a **median ensemble beat**. This is far less noisy than per-beat values.
6. Features on the ensemble beat:

| Feature | Meaning | Literature |
|---|---|---|
| Heart rate | 60 / median beat length | HR–BP coupling |
| Upstroke time (ms) | foot → systolic peak | shorter with stiffer arteries and higher pressure (Elgendi 2012) |
| Width at 50 % / 25 % (ms) | pulse width | narrows as pressure rises (Awad 2007) |
| Area ratio | area after / before the systolic peak (beat detrended) | wave reflection, like the inflection point area (Wang 2009); noisy, so weighted lightly |
| APG b/a, d/a | second-derivative wave ratios, on the smoothed beat | vascular ageing and stiffness (Takazawa 1998); d/a is noisier on wrist PPG, so weighted lightly |
| Reflection delay (ms), v3 | systolic peak → diastolic peak, or the inflection where they merge | stiffness-index timing, shorter with stiffer arteries / higher pressure (Millasseau 2002) |
| Reflection index, RMSSD, skewness, 32-point beat shape, v3 | stored for the learned model and signal quality; not in the classical fit | Elgendi 2016 (skewness SQI) |

7. **Quality:** the median correlation of each beat with the ensemble beat, times the share of
   beats that correlate above 0.9. A reading needs quality ≥ 0.55 and at least 10 beats.

## Calibration (3 cuff readings, valid 28 days)

As in Samsung Health Monitor, the watch never measures without a valid calibration. The user
sits still and takes 3 upper-arm cuff readings on the phone. At the same time the watch records
20 s of PPG for each one; the phone opens the watch screen and each round starts by itself.

Each round stores:
- the features;
- the cuff values;
- the **raw PPG**, so the calibration can be re-analysed if the algorithm changes.

Calibrations made with algorithm 1 are no longer valid: the user is asked to calibrate again.
Algorithm 2 calibrations stay valid: the phone recomputes their features from the stored raw PPG
(`BpCalibration.upgraded`), and until then the estimator simply leaves out the v3 feature.

**Cuff checks (algorithm 3).** Blood pressure → Accuracy check → "Compare latest reading with a
cuff" stores the pair for the accuracy statistics *and* adds it to the calibration (features from
the reading's pulse wave, which the watch now sends with every reading). The phone re-sends the
calibration to the watch. At most 12 are kept, newest first.

## Estimate (BpEstimator)

`BP = reference cuff + w · (features − reference features)`

`w` is fitted per user with weighted Bayesian (ridge-to-prior) regression:

- **Prior:** population sensitivities, e.g. SBP +0.45 mmHg/bpm, −0.12 mmHg per ms of upstroke,
  −0.04 mmHg per ms of reflection delay, with a prior SD of 2.5 × each weight.
- **Data:** the 3 calibration rounds plus any cuff checks, cuff noise 4 mmHg. Older points weigh
  less (half-life 14 days, never below 0.25). A point taken long after the base calibration also
  gets extra variance for baseline drift (random walk, 4 mmHg² per day), so drift is not
  mistaken for sensitivity.
- **Baseline:** the reference pressure follows the most recent cuff points (half-life 5 days),
  each moved to the reference features along the fitted slopes.

A feature is used only when every calibration point and the current reading have it.

**Uncertainty** (±, about one SD) combines cuff and model error (5 mmHg), the fit residual, drift
of 0.15 mmHg per day since the latest cuff point, and extrapolation that grows with distance from
the calibration.

**Beyond the calibration:** a core feature (HR, upstroke, width, reflection delay) more than 2.5
typical spreads away, a change of more than 20/14 mmHg, or a pressure outside the cuff range the
calibration has seen (± 20). The reading is shown and flagged. Output is limited to 60–250 /
35–150 mmHg (physiological limits, not a clamp to the calibration).

## Personal learned model on the phone (algorithm 4)

The watch sends every reading's raw pulse wave to the phone. Once there are at least 6 cuff
checks, the phone trains a small **ridge regression on the residual** (cuff − watch estimate)
from an embedding of the pulse wave, with the penalty chosen by leave-one-out (LOO) error and the
bias shrunk towards 0 (`HybridBpModel`). Two embedders compete:

- **Pulse shape** (pure Kotlin): the 32-point ensemble beat, its slope and the timing features.
- **PaPaGei-S** (Nokia Bell Labs, ICLR 2025, BSD-3-Clause): a PPG foundation model pretrained
  on 57,000 h of PPG, exported to ONNX and quantized to int8 (5.7 MB, `tools/bp-ml`), run with
  ONNX Runtime. Its 512-d embedding of two 10 s windows (125 Hz, z-scored) is averaged.

The one with the lower LOO error is used, and **only if it beats the watch's estimate by at least
10 % on this user's own checks**. The correction is limited to ±25 mmHg. A refined reading shows
"Refined by your personal model (watch showed …)". The accuracy card also shows the ± that
covered 80 % of the user's cuff checks (split-conformal), once there are 5.

Why no population BP model: models trained on ICU/surgery finger PPG (PulseDB, VitalDB,
MIMIC) lose much of their accuracy on other people and devices (calibration-free ≈ 14/8.5 mmHg
MAE; calibrated ≈ 9/5.8), and wrist PPG differs. The pretrained network is used only as a
feature extractor; the regression is always personal and gated.

## Pulse arrival time (in validation)

The Galaxy Watch reports a green PPG sample with every ECG sample (`EcgSet.PPG_GREEN`, 500 Hz).
Each ECG recording now computes the median time from the R peak to the wrist pulse's steepest
upstroke (`PulseArrival`) and stores it in the ECG metrics (`pulseArrivalMs`). PAT tracks
systolic changes better than shape alone (with the pre-ejection-period caveat; Mukkamala 2015).
It isn't used in the estimate until device tests confirm the channel carries a full pulse wave
on each model (docs/DEVICE_TESTING.md).

## Honest limits

- Algorithm 5's thresholds (CV 0.15, 25 bpm, perfusion index 0.6, 0.5 bpm/s, 35°) and the rate
  slopes are literature-informed starting points, checked on synthetic scenarios
  (`SyntheticPpg.scenario`, `BpAlgorithm5Test`). They must be confirmed on exported real data
  before they are tightened.
- The perfusion index needs the raw light level; readings without it (older calibrations until
  they are upgraded from their raw PPG) skip the compensatory check.
- The rate-dependent pulse shortening differs between people. It is corrected with population
  slopes, not fitted per user.

- Calibrated cuffless BP devices mainly track the user's **baseline**. They follow slow, moderate
  changes, but are poor at large or fast changes, especially long after calibration
  (Mukkamala et al., *Hypertension* 2022/2023). Readings that stay close to the calibration are
  partly inherent to the method, not always a bug.
- The population sensitivities are literature-informed approximations. They are not fitted on a
  clinical dataset, and the 3-point personal fit can only correct them partly.
- Heartline is a wellness app. BP estimates don't diagnose or rule out hypertension.

## Evaluating changes on real data

Blood pressure → Share → **BP data (JSON)** exports the calibration and every cuff-checked
reading with its raw PPG. `BP_DATASET=file ./gradlew :shared:test --tests '*BpDatasetReport*' -i`
replays the current algorithm (with and without cuff checks) and prints mean difference ± SD,
MAE, % within 10 mmHg and the proportional-bias slope; `tools/bp-ml/evaluate_embedders.py`
compares the learned-model embedders. A change is kept only if it improves these on real data.

## Accuracy check (validation mode)

In **Blood pressure → Accuracy check** on the phone, the user can enter a cuff reading taken right
after (within 30 minutes of) a watch reading. Heartline then shows the watch-minus-cuff mean
difference and spread (Bland–Altman style), and the share of readings within 10 mmHg, as they are.
This is the honest way to judge accuracy for one person. ISO 81060-2 requires a mean difference
of ≤ 5 mmHg with SD ≤ 8 mmHg across many people. Heartline makes no such claim.

## References

- Elgendi M. On the analysis of fingertip photoplethysmogram signals. *Curr Cardiol Rev* 2012.
- Takazawa K. et al. Assessment of vasoactive agents and vascular aging by the second derivative of photoplethysmogram waveform. *Hypertension* 1998.
- Awad A. et al. The relationship between the photoplethysmographic waveform and systemic vascular resistance. *J Clin Monit Comput* 2007.
- Wang L. et al. Noninvasive cardiac output estimation using a novel photoplethysmogram index. *IEEE EMBC* 2009.
- Mukkamala R. et al. Cuffless blood pressure measurement: where do we actually stand? *Hypertension* 2022.
- Mukkamala R. et al. Toward ubiquitous blood pressure monitoring via pulse transit time. *IEEE TBME* 2015.
- Falter M. et al. Smartwatch-based blood pressure measurement demonstrates insufficient accuracy. *Front Cardiovasc Med* 2022.
- Stergiou G.S. et al. ESH recommendations for the validation of cuffless blood pressure measuring devices. *J Hypertens* 2023;41:2074.
- Tae Y. et al. Change point-aware evaluation and re-calibration of PPG-based blood pressure estimation. arXiv:2608.18639, 2026.
- Pillai A. et al. PaPaGei: open foundation models for optical physiological signals. *ICLR* 2025.
- Millasseau S.C. et al. Determination of age-related increases in large artery stiffness by digital pulse contour analysis. *Clin Sci* 2002.
- Elgendi M. Optimal signal quality index for photoplethysmogram signals. *Bioengineering* 2016.
- Wang W., Mohseni P. et al. / Moulaeifard M. et al. Generalizable deep learning for PPG-based blood pressure estimation: a benchmarking study, 2025.
- ISO 81060-2:2018, non-invasive sphygmomanometers — clinical investigation of intermittent automated measurement type.
- ISO 81060-3:2022, non-invasive sphygmomanometers — continuous automated measurement type (cuffless validation incl. positions, induced changes and recalibration).
- Weissler A.M. et al. Systolic time intervals in heart failure in man. *Circulation* 1968 (LVET–heart rate relation).
- Payne R.A. et al. Pulse transit time measured from the ECG: an unreliable marker of beat-to-beat blood pressure. *J Appl Physiol* 2006 (pre-ejection period confounding).
- Mukkamala R. et al. / AHA Scientific Statement. Cuffless devices for the measurement of blood pressure. *Hypertension* 2025.
- Wearable, cuffless, and portable devices for blood pressure monitoring (2015–2025): a scoping review. *Front Digit Health* 2026.
- A method for blood pressure hydrostatic pressure correction using wearable inertial sensors and deep learning. *npj Biosensing* 2025.
- Relationship between pulse transit time, PPG features, and blood pressure in atrial fibrillation. 2025 (PubMed 41337181).
