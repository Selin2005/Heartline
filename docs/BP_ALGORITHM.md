# Blood pressure on the watch — algorithm 2

Heartline estimates blood pressure (BP) from the watch's green PPG pulse wave. It uses the same
approach as Samsung Health Monitor: calibrated pulse-wave analysis (PWA). This document explains
what the watch measures, how the estimate is made, and what it can and cannot do.

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

## What is measured (PpgFeatures, 100 Hz PPG_ON_DEMAND green, 20 s)

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

## Estimate (BpEstimator)

`BP = mean cuff + w · (features − mean calibration features)`

`w` is fitted per user with Bayesian (ridge-to-prior) regression:

- **Prior:** population sensitivities, e.g. SBP +0.45 mmHg/bpm and −0.12 mmHg per ms of upstroke,
  with a prior SD of 2.5 × each weight.
- **Data:** the user's 3 calibration points, with cuff noise of 4 mmHg.

With 3 points the prior dominates unless the calibration happens to span a real pressure range.
When it does, the fit follows the user (tested).

There is no clamp. Instead:
- **Uncertainty** is shown with every reading (±, about one SD). It combines cuff and model
  error (5 mmHg), the calibration fit residual, drift of 0.15 mmHg per day since calibration, and
  extrapolation that grows with distance from the calibration.
- **Out-of-range readings are refused.** If any feature is more than 3 typical day-to-day spreads
  from the calibration, or the estimate moves more than 35/25 mmHg, the watch shows "Outside your
  calibration" (sit quietly and try again, or recalibrate). It shows no number.

## Honest limits

- Calibrated cuffless BP devices mainly track the user's **baseline**. They follow slow, moderate
  changes, but are poor at large or fast changes, especially long after calibration
  (Mukkamala et al., *Hypertension* 2022/2023). Readings that stay close to the calibration are
  partly inherent to the method, not always a bug.
- The population sensitivities are literature-informed approximations. They are not fitted on a
  clinical dataset, and the 3-point personal fit can only correct them partly.
- Heartline is a wellness app. BP estimates don't diagnose or rule out hypertension.

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
- ISO 81060-2:2018, non-invasive sphygmomanometers — clinical investigation of intermittent automated measurement type.
