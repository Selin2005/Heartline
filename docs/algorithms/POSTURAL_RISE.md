# Heart rate after getting up

When the wearer turns it on, Heartline looks for large heart-rate rises after getting up from
rest. If the rises repeat on several days, the phone shows a notice suggesting a talk with a
doctor. The pattern is the one a standing test looks for in postural orthostatic tachycardia
syndrome (POTS). Heartline is a wellness app, not a medical device. It never names a condition,
and a watch can't do that test: there is no blood pressure, no posture and no clinician.
Everything here is worded as a check, never as a diagnosis.

Code: `shared/.../pots/Orthostatic.kt` (`OrthostaticEventDetector`, `PosturalPatternRules`),
`phone/.../data/HeartRepository.kt` (`checkPosturalRise`), and
`phone/.../ui/model/HeartModels.kt` (`HeartSummaries.postural`). The watch side is the steps in
each minute (`HrMinute.steps`).

## 1. What a standing test measures, and what a watch can

| Standing test (clinic) | Heartline |
| --- | --- |
| Lying still for 5 minutes or more, then standing | Still for 5 minutes or more (`REST` minutes), then moving about (`ACTIVE`, at least 20 steps a minute) |
| Heart rate rises ≥ 30 bpm within 10 minutes (≥ 40 bpm from 12 to 19 years) | The same thresholds; the age comes from the profile |
| Rise sustained, not a single beat | Second-highest reading of at least two in the 10 minutes |
| No drop in blood pressure (≥ 20/10 mmHg rules out POTS) | Not measured: one reason this is never a diagnosis |
| Symptoms for ≥ 6 months, and other causes ruled out | A clinician's job. Symptoms the wearer tags on an ECG only describe the notice |

The watch has no posture sensor. Its gravity vector (`MotionMeter.gravity()`) gives the
forearm's angle, which says nothing about whether the body is lying, sitting or standing.
"Getting up" is therefore estimated from the first moving-about minute after a still stretch.

## 2. A rise (`OrthostaticEventDetector`)

Passive heart rate at rest comes every few minutes, not every minute (HEART_MONITORING.md,
section 1). All the rules are therefore about time covered, not back-to-back minutes:

1. **The start** is an `ACTIVE` minute.
2. **Still before:**
   - the 15 minutes before hold at least two readings, all `REST` (no moving about, workout or
     sleep);
   - the first of them is at least 5 minutes before the start;
   - the last is at most 10 minutes before.

   A rate still coming down from moving is not a resting rate, and waking up is not getting up
   from rest. The resting rate is the median of these readings.
3. **Held after:** at least two readings in the 10 minutes from the start. The held rate is the
   second highest, so one spike doesn't count.
4. **Not a workout:**
   - no `EXERCISE` or `SLEEP` minute in the window;
   - no minute with 100 or more steps. Brisk walking and stairs raise the rate by themselves.

   Minutes from older watches carry no steps and are judged by their context alone.
5. **A rise** is a held rate at least 30 bpm above the resting rate (40 bpm from 12 to 19 years).
   After a rise, the next 10 minutes are not looked at again, so one getting-up is one rise.
6. **Only finished windows:** a start is judged only once its 10 minutes have passed by the
   newest minute. A rise is never found from half its window.

## 3. A pattern and the notice (`PosturalPatternRules`, `HeartRepository`)

- At least **3 rises on 3 different days** (local dates) within **14 days**. A single busy
  morning, or two days, is not a pattern.
- It runs on the phone with every `HR_BATCH`, over the stored minutes of the last 14 days. The
  watch keeps only 90 minutes, so it could never see a pattern of days. It is judged as of the
  newest minute in the batch, so a late batch is judged by its own data.
- **One notice per 14 days.** The notice is stored like any heart alert:
  - kind `HIGH_HEART_RATE` with `trend = POSTURAL_RISE`;
  - `bpm` is the median held rate, `normal` the median resting rate;
  - `value` is the median rise and `threshold` is the rise that counts.

  It is the phone's own notice: it carries no watch version, and no watch ever receives it.
- Symptoms (dizziness, palpitations, fatigue, fainting) tagged on an ECG within 30 minutes after
  a rise go to the log with the notice. They never lower the bar.
- Wording: "Heart rate rises a lot after getting up … This is a pattern, not a diagnosis.
  Consider talking to a doctor about it, especially if you feel dizzy, light-headed or faint when
  standing."

## 4. Settings

- `MonitorSettings.posturalRise`, which is **off** by default.
  - Settings → Health monitoring → Heart → Heart rate after getting up.
  - It is a narrow check most people don't need.
  - Older app versions ignore the field.
- It runs only when the heart part of monitoring is on (`posturalRiseActive`).
- It never runs with atrial fibrillation or a pacemaker or ICD (the setup's answers):
  - a rate-responsive pacemaker raises the rate with movement by design;
  - in AF the rate itself is irregular.
- The heart-rate screen shows a card "Heart rate after getting up" while the check is on:
  - rises in the last 14 days: none, a few (with how many days), or the repeated pattern;
  - the latest three rises (rest → held, and the rise).

## 5. Data

- `HrMinute.steps` is the steps the watch counted in the minute. They come from Health Services
  `STEPS` spans (`ActivityTimeline.stepsPerMinute`). It is null when the watch has no step
  counts, and from older watches.
  - The JSON adds a field, so older phones ignore it (`Protocol.json` ignores unknown keys).
  - Two copies of one minute keep the larger count.
- Phone database v12: `hr_minutes.steps INTEGER`, nullable (`MIGRATION_11_12`). Older rows stay
  null: unknown, not zero.

## 6. Known limits

- **Posture is estimated.** Getting up and starting to walk counts. Standing still without
  steps, such as at a sink, is missed. Sitting up in bed is missed too.
- **Other causes raise the rate:**
  - coffee, alcohol and nicotine;
  - heat;
  - little sleep or not drinking enough;
  - illness or fever;
  - anxiety;
  - deconditioning;
  - some medicines.

  The card's footer names the common ones.
- **Sparse readings.** At rest, passive heart rate can be 5–10 minutes apart. Many getting-ups
  have fewer than two readings in their window and are simply not judged. Fewer rises are found,
  but not false ones.
- **No blood pressure**, so an orthostatic drop can't be told apart.
- Not validated against a clinical standing test.

## 7. Tests

- `shared`, `OrthostaticTest`:
  - a held rise of 30 counts, 29 doesn't;
  - one spike doesn't count, and one reading after is not enough;
  - workouts, brisk walking or stairs (100 steps a minute or more) are left out, and minutes
    without steps are judged by context;
  - it needs still minutes before: not just after moving, not a short rest, not waking up, not
    after a long gap;
  - only finished windows count, and one getting-up is one rise;
  - teenagers need 40 bpm;
  - steps travel with the minute and merge;
  - patterns: 3 days notify, 1 or 2 days don't, rises older than 14 days drop out, and symptoms
    describe but don't count.
- `wear`, `BackgroundHeartTest.stepsGoToThePhoneWithEachMinute`.
- `phone`:
  - `HeartRepositoryTest`:
    - steps are stored and merged;
    - three days notify once, with the cooldown;
    - nothing when the check is off or with a pacemaker;
    - brisk walking doesn't count;
    - the heart screen's summary.
  - `MigrationTest.migrate11To12AddsStepsToHeartMinutes`.
- Screenshots: `heart_rate_postural`, `alerts_postural`, and the settings screens with the new
  switch.

## References

- Sheldon RS et al. 2015 Heart Rhythm Society expert consensus statement on the diagnosis and
  treatment of postural tachycardia syndrome. *Heart Rhythm* 2015;12:e41–e63.
- Freeman R et al. Consensus statement on the definition of orthostatic hypotension, neurally
  mediated syncope and the postural tachycardia syndrome. *Clin Auton Res* 2011;21:69–72.
- Vernino S et al. Postural orthostatic tachycardia syndrome (POTS): state of the science and
  clinical care from a 2019 NIH expert consensus meeting. *Auton Neurosci* 2021;235:102828.
