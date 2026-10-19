# Heart rate after getting up

Heartline looks for large heart-rate rises after getting up from rest. When they repeat on
several days, the phone notifies and offers a report the wearer can show a doctor. The pattern is
the one a standing test looks for in postural orthostatic tachycardia syndrome (POTS).

Heartline is a wellness app, not a medical device. It never names a condition, and a watch can't
do that test: there is no blood pressure, no posture and no clinician. Everything is worded as a
pattern, never as a diagnosis.

Code:
- `shared/.../pots`:
  - `StandProbe.kt`: `StandProbe`, `StandProbePolicy`.
  - `Orthostatic.kt`: `OrthostaticEventDetector`, `PosturalPatternRules`, `PosturalStatus`.
- `wear/.../monitor/StandProbes.kt`: `StandTrigger`, `StandProbeWorker`.
- `phone/.../data/HeartRepository.kt`: `checkPosturalRise`.
- `phone/.../ui/model/PosturalModels.kt` and `phone/.../ui/heart/PosturalScreens.kt`: the report.
- `phone/.../report/PosturalReport.kt`: the PDF.

## 1. What a standing test measures, and what a watch can

| Standing test (clinic) | Heartline |
| --- | --- |
| Lying still for 5 minutes or more, then standing | Still for 5 minutes or more (`REST` minutes), then the motion sensor sees the wearer start moving |
| Heart rate rises ≥ 30 bpm within 10 minutes (≥ 40 bpm from 12 to 19 years) | The same thresholds; the age comes from the profile |
| Rise sustained, not a single beat | Second-highest of the probe's slices (or of two passive readings) |
| No drop in blood pressure (≥ 20/10 mmHg rules out POTS) | Not measured: one reason this is never a diagnosis |
| Symptoms for ≥ 6 months, and other causes ruled out | A clinician's job. Symptoms the wearer tags describe the report |

The watch has no posture sensor. Its gravity vector (`MotionMeter.gravity()`) gives the forearm's
angle, which says nothing about whether the body is lying, sitting or standing. Getting up is
estimated from movement after a still stretch.

## 2. On the watch: a stand probe (`StandTrigger`, `StandProbeWorker`)

Passive heart rate at rest comes every 5–10 minutes (HEART_MONITORING.md, section 1), so most
getting-ups have no reading in the minutes that matter. The watch therefore measures on purpose.

1. **The trigger.** Android's significant-motion sensor is low-power and one-shot. It wakes the
   watch when the wearer starts walking.
   - It is armed again after each trigger.
   - A new process forgets it, so it is also re-armed on every passive heart-rate delivery,
     every rhythm window and every settings sync.
   - Watches without the sensor use the passive step counts instead
     (`StandProbePolicy.gettingUpFromSteps`): steps of 20 a minute or more after 10 still minutes,
     if they began less than 3 minutes before. Those counts arrive minutes late, so fewer probes
     start.
2. **Whether to probe** (`StandProbePolicy.decide`):
   - not asleep, not in a workout, and not in the usual sleep hours;
   - no movement fired the sensor for 10 minutes before (still before);
   - at most 6 probes a day, at least 45 minutes apart, so the first getting-ups of the day are
     measured.
3. **The probe:** an expedited one-time work.
   - The heart-rate tracker (`HEART_RATE_CONTINUOUS`) runs in three slices, starting about 15 s,
     2.5 minutes and 6 minutes after the motion.
   - Each slice listens until its good readings cover 40 s (75 s at most), then asks the tracker
     for the readings it still holds.
   - The sensor is off between slices. About 2 minutes of sensor in all; the probe ends about
     7.5 minutes after the motion, inside WorkManager's 10 minutes.
   - As with the rhythm windows, it never runs on the charger, off the wrist, during a
     measurement, or while another background measurement has the sensors.
   - A wake lock and an allow-while-idle alarm keep its timers running with the screen off.
   - A rhythm window that falls during a probe is skipped (the next comes in 15 minutes).
4. **What is sent:** `StandProbe{startMs, readings, stepsPerMinute}` in `HrBatch.stands`.
   - The readings are 5-second medians of the good, on-wrist readings, put by their own time.
   - Older phones ignore the field.

The phone judges the probe. The still minutes before are already there, or arrive later in the
passive batches.

## 3. A rise (`OrthostaticEventDetector`)

Everything is about time covered, not back-to-back minutes.

1. **Still before:** the 15 minutes before getting up hold at least two readings, all `REST` (no
   moving about, workout or sleep).
   - The first is at least 5 minutes before, and the last at most 10 minutes before.
   - A rate still coming down from moving is not a resting rate, and waking up is not getting
     up from rest.
   - The resting rate is the median of these readings.
2. **Held after:**
   - **From a probe:** at least two slices in the 10 minutes after. The held rate is the
     second-highest slice median.
   - **From passive readings only:** the first `ACTIVE` minute after the still stretch, and at
     least two readings in the 10 minutes after. The held rate is the second highest, and the
     window must have passed by the newest data.
3. **Not a workout:**
   - no `EXERCISE` or `SLEEP` minute in the window;
   - not 100 or more steps a minute, by the probe's own count or the minutes' counts. Brisk
     walking and stairs raise the rate by themselves.
4. **A rise** is a held rate at least 30 bpm above the resting rate (40 bpm from 12 to 19 years).
5. **One getting-up is one rise.** A passive rise within 10 minutes of a probe is dropped, since
   the probe measured the same getting-up better.

## 4. Pattern, notice and report

- **Pattern** (`PosturalPatternRules`): at least 3 rises on 3 different days (local dates) within
  14 days. A single busy morning, or two days, is not a pattern.
- **When it is checked:** the phone checks with every `HR_BATCH`, over the stored minutes and
  probes of the last 14 days, as of the newest data stored. A probe can reach the phone before the
  still minutes it needs, and then counts once they arrive. The watch keeps only 90 minutes, so it
  could never see a pattern of days.
- **The notice:** at most one every 14 days.
  - It is stored like any heart alert: kind `HIGH_HEART_RATE` with `trend = POSTURAL_RISE`.
  - `bpm` is the median held rate, `normal` the median resting rate, `value` the median rise and
    `threshold` the rise that counts.
  - It is the phone's own notice: no watch version, and no watch receives it.
  - Tapping it opens the report.
  - Symptoms tagged on a rise, or on an ECG within 30 minutes after one, go to the log with the
    notice. They never lower the bar.
- **When the report exists** (`PosturalStatus`):
  - `PATTERN` while the rises make a pattern.
  - `EASING` after a notice, once the rises have stopped, for 60 days ("seen less often").
  - `NONE` otherwise. Single rises happen to everyone, so before a pattern there is nothing on
    any screen.

### Screens

- **Heart rate screen.** Only in `PATTERN` or `EASING`, a card at the top links to the report.
  In `PATTERN` it reads "Heart rate rises a lot after getting up: on N of the last 14 days, usually
  from about X to Y bpm".
- **After getting up** (the report):
  - **Summary:** the status, the typical rise, days with rises out of 14, the number of rises,
    and the 14 days before.
  - **Chart:** the last 30 days, one bar per day from the lowest resting rate to the highest held
    rate.
  - **Talk to a doctor,** with when to get help right away, and Share report (PDF).
  - **Rises:** newest first, each with how the wearer felt.
  - **What else can raise it.**
  - **How it's measured.**
- **A rise:**
  - the curve from 15 minutes before to 10 after, with "Got up" marked and dashed guides for the
    resting rate and the rise that counts;
  - resting and held rate, and whether the watch measured it or the all-day heart rate showed it;
  - "How did you feel?": dizziness, palpitations, fatigue, shortness of breath or fainting,
    saved with the rise (`rise_symptoms`).
- **PDF:** one A4 page for a doctor.
  - The period, the summary, the 30-day chart, and a table of the rises: date, time, resting,
    held, rise, how the wearer felt, and what measured it.
  - What a rise means, and the disclaimer.
  - Dates are printed in full, never "Today". The report is also shareable as text.

## 5. Settings

There is no switch of its own. It is part of heart monitoring (`posturalRiseActive`: the master
and the heart part on). The Heart part's summary in Settings names it. It never runs with atrial
fibrillation or a pacemaker or ICD (the setup's answers):
- a rate-responsive pacemaker raises the rate with movement by design;
- in AF the rate itself is irregular.

## 6. Data

- **`HrMinute.steps`:** the steps the watch counted in the minute (`ActivityTimeline.stepsPerMinute`).
  It is null when the watch has no step counts, and from older watches. Two copies of one minute
  keep the larger count.
- **`HrBatch.stands`:** the stand probes. It is a JSON field, so older phones ignore it.
- **Phone database:**
  - v12 adds `hr_minutes.steps INTEGER` (nullable).
  - v13 adds `stand_probes(startMs, readingsJson, stepsPerMinute)` and
    `rise_symptoms(startMs, symptoms)`.
  - Rises are not stored. They are found again from the minutes and probes, so baselines that
    arrive late still count.
- **Watch preferences:** `last_motion` (the last trigger) and `stand_probes` (the last two days'
  probe times).

## 7. Known limits

- **Posture is estimated.** Getting up and starting to walk counts. Standing still without
  walking, such as at a sink, is missed, and so is sitting up in bed.
- **Other causes raise the rate:**
  - coffee, alcohol and nicotine;
  - heat;
  - little sleep or not drinking enough;
  - illness or fever;
  - anxiety;
  - deconditioning;
  - some medicines.

  The report names the common ones.
- **Battery:** up to 6 probes a day of about 2 minutes of sensor each, next to the rhythm
  windows' 75 s every 15 minutes (2 hours a day): about a tenth more sensor time. It still needs
  measuring on a device over a day.
- **No blood pressure,** so an orthostatic drop can't be told apart.
- **Not validated** against a clinical standing test.

## 8. Tests

- **`shared`** (`OrthostaticTest`):
  - **Passive rises:**
    - a rise of 30 counts and 29 doesn't;
    - one spike doesn't count, and one reading after is not enough;
    - workouts and brisk walking are left out;
    - it needs still minutes before;
    - only finished windows count, and one getting-up is one rise;
    - teenagers need 40 bpm.
  - **Probes:**
    - a probe sees what sparse readings miss;
    - two slices and a held rise are needed;
    - rest before is needed, and brisk walking or a workout doesn't count;
    - a probed getting-up is counted once;
    - slices are told apart, and readings become 5-second medians;
    - the probe travels in `HrBatch`.
  - **Policy:**
    - a probe starts after a still stretch, but not asleep, in a workout or at night;
    - probes are rationed, and off without heart monitoring or with a pacemaker;
    - the step-count fallback.
  - **Patterns and status:** 3 days notify and 1 or 2 don't, old rises drop out, symptoms describe
    but don't count, and the report exists only after a pattern.
- **`wear`:**
  - `StandProbeWorkerTest` (Robolectric, fake tracker): a probe measures three slices and sends
    them; no probe when off, when the sensors are busy, or when it starts too late.
  - `BackgroundHeartTest.stepsGoToThePhoneWithEachMinute`.
- **`phone`:**
  - `HeartRepositoryTest`:
    - steps are stored and merged;
    - passive rises notify once, with the cooldown;
    - the watch's probes find rises the sparse readings miss;
    - a probe counts once its still minutes arrive;
    - nothing when off or with a pacemaker, and brisk walking doesn't count;
    - how the wearer felt is kept per rise;
    - the report exists only once there is a pattern (and stays as "seen less often").
  - `MigrationTest`: 11 → 12 and 12 → 13.
- **Screenshots:**
  - `heart_rate_postural` and `heart_rate_postural_easing`;
  - `heart_rate_after_standing`, `_rises`, `_more` and `_empty`;
  - `heart_rate_rise_detail` and `alerts_postural`;
  - `postural_report_page` (the PDF);
  - the settings screens.

## References

- Sheldon RS et al. 2015 Heart Rhythm Society expert consensus statement on the diagnosis and
  treatment of postural tachycardia syndrome. *Heart Rhythm* 2015;12:e41–e63.
- Freeman R et al. Consensus statement on the definition of orthostatic hypotension, neurally
  mediated syncope and the postural tachycardia syndrome. *Clin Auton Res* 2011;21:69–72.
- Vernino S et al. Postural orthostatic tachycardia syndrome (POTS): state of the science and
  clinical care from a 2019 NIH expert consensus meeting. *Auton Neurosci* 2021;235:102828.
