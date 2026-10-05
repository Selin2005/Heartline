# Background heart monitoring and heart notifications

Heartline watches the heart rate all day in the background. It sends notifications when the
rate stays unusually high or low, and when the rhythm looks irregular. It also gives the phone
trends: resting heart rate, sleep, exercise, heart-rate zones and HRV. Heartline is a wellness
app, not a medical device: none of this is a diagnosis, and the notifications are worded that
way.

Code: `wear/.../monitor` (watch side), `shared/.../hr` and `shared/.../irn` (rules, shared and
unit tested), `phone/.../data/HeartRepository.kt` and `phone/.../ui/model/HeartModels.kt` (phone).

## 1. Data sources

| Source | What it gives | Used for |
|---|---|---|
| Health Services passive `HEART_RATE_BPM` (`PassiveHeartRateService`) | Heart rate every few minutes at rest, every second during a workout. No foreground service, no notification. | Trends, high/low notifications |
| Health Services passive `STEPS` | Step counts over intervals | Labelling minutes as *moving* |
| Health Services user activity info | `PASSIVE`, `EXERCISE` (a workout is running), `ASLEEP` | Labelling minutes as *exercise* or *sleep* |
| Samsung `HEART_RATE_CONTINUOUS` (`SdkHrSource`), ~75 s windows (`IrnWindowWorker`) | 1 Hz heart rate with beat-to-beat intervals (IBI) and their status | Irregular rhythm check, HRV |
| Android step detector, accelerometer, off-body sensor (`StepMotionMonitor`, `WristState`) | Steps, arm movement, watch on/off the wrist | Rejecting rhythm windows |

Activity info and steps are requested while heart monitoring is on and `ACTIVITY_RECOGNITION` is
granted. If Health Services refuses the registration with activity
info, it is registered again for heart rate only, so heart rate is never lost.

**Samsung tracker status.** `HEART_RATE_STATUS` 1 is a good reading. Other values mean the tracker
is still searching, the signal is weak, the arm is moving, or (−3) the watch is off the wrist. A
sample is `reliable` only with status 1. An interval is kept only when its `IBI_STATUS` is 0. Older
trackers send no status list, and then a reliable reading keeps all its intervals. Before
this rule, a watch lying on a table produced weak-signal "intervals" that looked exactly like an
irregular rhythm.

## 2. Minutes and what the wearer was doing

`MinuteAggregator` turns samples into `HrMinute`s: average (rounded), min, max, RMSSD (only when
the minute has intervals) and an **activity** (`HrContext`):

| Activity | Rule |
|---|---|
| `EXERCISE` | Health Services says a workout was running at the middle of the minute |
| `SLEEP` | Health Services says the wearer was asleep |
| `ACTIVE` (moving) | ≥ 20 steps in the minute (`ActivityTimeline.ACTIVE_STEPS_PER_MINUTE`), or a sample flagged as moving |
| `REST` | otherwise: awake and still |

`ActivityTimeline` keeps state changes for 48 h and step spans for 3 h on the watch. The old
`resting` flag is still sent (`activity == REST`) for older phone versions. An `HrMinute` from an
older watch, which has no `activity`, decodes as `REST` or `ACTIVE` from that flag.

## 3. State, batches and sync

- **The watch process can be new on every passive delivery.** So `HeartMonitor` keeps the last
  90 minutes and the time of each alert in `MonitorState`, which `WatchSettingsStore` persists.
  Without this, the cooldown reset and alerts repeated.
- Each passive delivery closes its last minute too, so the newest minute is not held back. A
  minute that later gets more samples is merged (`MinuteAggregator.merge`).
- Minutes go to the phone in `HR_BATCH` messages. The minutes of the rhythm windows go too,
  because only they carry HRV. The phone stores every minute once (`hr_minutes`, keyed by its
  start time). `HeartRepository.saveBatch` combines the two copies: it keeps the RMSSD, the wider
  range, and the more specific activity.

## 4. Personal limits (`HeartBaseline`)

### Why personal
The normal resting heart rate differs between people by up to 70 bpm (40–109 bpm). Within one
person it is very steady: the day-to-day standard deviation is about 3 bpm (Quer 2020, 92,457
adults). One fixed limit for everyone, such as 120 and 40, is therefore too loose for some and
too tight for others. The simulation (section 9) shows both.

### The normal
- `HeartHistory`, kept on the watch, holds one `DayStats` per day. Each has histograms of minute
  averages:
  - `rest`: awake and still;
  - `sleep`: the night that ends on that day (18:00–18:00);
  - `exercise`.

  It keeps 90 days, of which rest and sleep use the last 28 (the window of Mishra 2020 and
  Alavi 2022). Moving-about minutes are not learnt from.
- **Learning starts at once.** The normal begins as a population guess: 65 bpm, women +3 (Quer
  2020). It moves to the wearer's own median by weight n / (n + 24), where n is the number of
  readings (24 readings is about 4 hours of passive heart rate). After about a day it is the
  wearer's own (`learning` ends at 80 %). The sleep normal comes from the first night.
- **Not learnt from:**
  - minutes outside the current limits (an episode never becomes the normal);
  - "still" minutes in the 15 minutes after moving or a workout (still recovering);
  - nights marked unusual by the trend notice.

  Statistics are robust: median and percentiles from the histograms.

### From the normal to the limits
Every clinical number is defined for an average adult whose resting heart rate is 65. A limit
is that number's ratio to 65, applied to the wearer's own median (57 asleep: the median sleep
dip is 12.7 %).

| Sensitivity | High, at rest or asleep | Low, awake | Low, asleep |
|---|---|---|---|
| Low | × 120/65 = +85 %: Apple Watch / Fitbit default | × 40/65 = −38 %: Apple Watch default | × 35/57 = −39 % |
| **Standard** (default) | **× 100/65 = +54 %: tachycardia at rest** | **× 45/65 = −31 %: middle of the lowest 2nd percentile of adults, 40–55 (ACC/AHA/HRS 2018)** | **× 40/57 = −30 %** |
| High | × 95/65 = +46 %: 95th percentile of real-world heart rate over 60 (Avram 2019) | × 50/65 = −23 %: sinus bradycardia (ACC/AHA/HRS 2018) | × 45/57 = −21 % |

Then:
1. **Outside the wearer's own range.** With ≥ 60 readings, high is at least the wearer's 99th
   percentile + 5, and low at most their 1st percentile − 5.
2. **Safety bounds.** High stays within 90–130, low awake within 35–50, and low asleep within
   30–45. An abnormal "normal" can't hide a problem: at rest, above 130 always notifies (the 95th
   percentile of real-world heart rate is at most 110 at any age, Avram 2019).
   - Exception: when the wearer's own nights go below 30 (common in athletes), their own 1st
     percentile − 5 is used, down to 28. Readings under 25 are dropped as implausible, so the
     limit stays above that.
3. **While learning,** the limits are mixed with safe defaults by the same weight: 120 high,
   and 35 / 30 low (the safety floors). The earlier 40 / 35 gave an athlete false low alerts
   on the first night in the simulation.
4. **Exercise maximum:**
   - the age formula 208 − 0.7 × age (Tanaka 2001; 190 without an age);
   - raised to the wearer's own hardest workouts (99th percentile over 90 days, with ≥ 30
     minutes of exercise on ≥ 3 days). Only earlier days count: today's peak, such as an
     overexertion happening now, never raises today's limit;
   - at most 15 bpm above the formula (its spread is about ±10).

Examples (Standard):

| Wearer | Normal | High | Low awake | Low asleep |
|---|---|---|---|---|
| Typical | 65 (asleep 56) | 100 | 45 | 39 |
| High normal | 85 | 130 (bound) | 50 (bound) | — |
| Athlete | 48 (asleep 38) | 90 (bound) | 35 (bound) | 28–30 |

### Notifications (`HeartRateAlertRules`)
Each minute is judged by the limits of what the wearer was doing:

| Activity | High | Low |
|---|---|---|
| Rest | above the high limit | below the low limit |
| Sleep | above the high limit | below the sleep low limit |
| Exercise / moving | above the exercise maximum | never |

- **Before the normal is learnt** (under half of it from the wearer's own readings, about 24
  readings or a few hours of wearing), there is no "usual" to compare with. Right after install,
  the watch also can't yet tell rest from moving about. So at rest and asleep only a safety net
  notifies: 150 bpm, the rate above which a fast rhythm is likely the cause of symptoms (AHA ACLS
  tachycardia algorithm). The low limits are already the safety floors. The notice names no
  usual. A user report showed the bug this fixes: right after install, a notice said the heart
  rate was "X % above your usual 65", where 65 was the population guess.

- **"Held"** means every reading over a span is beyond the limit:
  - Rest and sleep: ≥ 10 minutes covered (as Apple, Fitbit and Google do), ≥ 3 readings, no gap
    over 10 minutes.
  - Exercise: ≥ 3 minutes, gaps ≤ 2 minutes.

  This is time covered, not back-to-back minutes. Passive heart rate at rest comes every few
  minutes, so the old "10 back-to-back minutes" rule only ever fired during workouts.
- **Recovery.** Minutes within 15 minutes after exercise, or within 5 minutes after moving, are
  not judged by the rest limits.
- **Cooldown.** One notification per kind every 3 hours. Exercise has its own cooldown key.
- **Notification.** `HealthAlert` carries the limit (`threshold`), the activity (`context`) and
  the wearer's normal (`normal`). For example: "While you were resting, your heart rate stayed
  high for over 10 minutes: up to 108 bpm, 66 % above your usual 65 bpm."

### Resting heart rate trend (`HeartBaseline.restingTrend`)
A gentle notice, not an alarm. It fires when the heart rate in sleep is higher than usual on
**3 of the last 5 nights**, the latest included. "Higher" means more than 2 standard deviations
(at least 6 bpm) above the wearer's own average of the 28 nights before. This is Quer 2020's
"unusual increase", on the baseline of Mishra 2020 and Alavi 2022. It often comes with an
infection, fever, too little sleep, stress or overtraining, sometimes before symptoms. Those
nights are marked unusual and not learnt from.
- It is checked once a day from 10:00, and notifies at most every 3 days.
- If the usual resting heart rate itself has been above 100 for a week, a separate notice
  suggests talking to a doctor, at most weekly.
- Both are sent as `HIGH_HEART_RATE` with `trend` set, so older phone versions still show them.

### Where it runs
On the watch, in `HeartMonitor`, so notifications don't need the phone. The limits in use go to
the phone with every `HR_BATCH` (`HrBatch.limits`), so the phone shows exactly what the watch
uses. With an older watch, the phone computes the same limits from its own minutes
(`HeartSummaries.history`).

## 5. Irregular rhythm

### Scheduling and wearing (`IrnWindowWorker`)
- Every 15 minutes, WorkManager runs one window of about 75 s on the Samsung
  tracker. Without the background sensor permission, a silent notification shows for that minute.
- **The window is skipped when the watch is not worn:**
  - when the all-day heart rate is on and there has been no passive heart rate for an hour (or
    ever since install);
  - when Android's off-body sensor says the watch is off the wrist (checked 1.5 s after it
    starts, and during the window).
- If a readable window was irregular, one extra check runs 7 minutes later (`scheduleFollowUp`).

### Background windows: batches, flush and the readings' own times (`BackgroundWindow`)
A real two-day log (Galaxy Watch8 Classic, October 2026) had **91 windows and none read**: no
rhythm check and no stress reading in two days. The raw readings showed why:

- **The tracker sends batches.** While the screen is off, `HEART_RATE_CONTINUOUS` holds its
  readings and sends them minutes late. A new listener even gets readings from before it started
  (up to 5 minutes earlier), and one reading can carry 10 minutes of beat intervals.
  - The old window was timed by the wall clock from its first reliable reading (`WindowGate`).
    When the first batch came after 45 s, it ended with **0 samples**.
  - That happened to the one perfect window of the log: 598 readings at 1:58 at night, every
    interval trusted, logged as "0 samples, warming up".
- **Movement was matched to the wrong time.** A reading counted as "moving" if the arm moved at
  any time after it, even at the end of the window. One movement then spoiled every late reading.
- **The skip reason could be stale:** a window too short to judge logged the previous window's
  reason.
- **Windows ran 7–13 minutes** with the sensor on, as the processor slept through the timers.

The window now works like this (`IrnWindowWorker.window`):
1. **Listen** for 90 s, with a partial wake lock (at most 2.5 minutes in all).
2. **Ask for the held readings** with the SDK's `HealthTracker.flush()`, and wait for
   `onFlushCompleted` (at most 10 s).
3. **Judge every reading by its own timestamp.** It is moving if steps or arm movement (by the
   sensor event's own time) fall in the 30 s before it, or the watch marks that minute active.
4. **Rhythm** (`BackgroundWindow.rhythm`): the newest 60–75 s stretch that starts on a reliable,
   still, on-wrist reading and passes `IbiWindowQuality` is judged. The stretch can lie before
   the window started.
5. **Stress** (`BackgroundWindow.hrv`): see
   [STRESS_MONITORING.md](STRESS_MONITORING.md#2-measuring-no-extra-battery).

**One line per window** (`Heartline/Monitor`):

```
IRN window done in 92 s: 598 readings: 598 good, 0 moving, 0 weak, 0 off-wrist, trusted beats 100 %,
late median/max 95/187 s, flush=true; rhythm read 74 s from 01:55:04, irregular=false;
stress score 31 from 597 beat pairs (SLEEP)
```

**What to expect.** By day, wrist intervals are often untrusted: in that log, 57 % of the
intervals of good readings were flagged.
- Replayed on the same 91 windows, the rhythm can be read in only a few, mostly at night.
- Stress, which only needs 40 trusted pairs, could be read in about one in ten.

The rhythm check stays strict, since a false notice is worse than none.

**Tests.** `ReplayTest` replays real windows from that log (`tools/replay/sessions.py`). The 1:58
night window must be read as regular with a stress value. The off-wrist and weak-signal windows
must not be read, and no real window may be judged irregular.

### Is the window readable? (`IbiWindowQuality`)
A window of about 60 s is analysed only if all of these hold. Otherwise it is *unreadable*: never
counted as irregular, and not as regular either.

| Check | Limit |
|---|---|
| On the wrist | no off-body sample |
| Still | no steps, no arm movement (accelerometer > 1 m/s² from its average) in the 30 s before each reading, and not an active minute |
| Signal | ≤ 5 % of samples not `reliable` |
| Rejected intervals | ≤ 10 % (tracker-flagged, out of 300–2000 ms, or from unreliable samples) |
| Coverage | the intervals add up to ≥ 85 % of the window (no gaps) |
| Rate | 40–150 bpm, and within 15 % of the tracker's own median heart rate |

The reason a window was not read is logged (`IRN window done: … rhythm not read: …`).

### Is it irregular? (`IrnThresholds`, `RrFeatures`)
- With *Standard* sensitivity, isolated premature beats are removed first: runs of one or two
  intervals more than 20 % from the local median, between steady beats (Petrėnas 2015, as in the
  ECG algorithm). In an irregularly irregular rhythm the neighbours are not steady, so those
  intervals stay.
- Features (Dash 2009): normalised RMSSD, Shannon entropy, turning-point ratio.

| Sensitivity | nRMSSD | Entropy | Turning points | Min. beats | Premature beats removed |
|---|---|---|---|---|---|
| Standard (default) | > 0.12 | > 0.65 | 0.55–0.85 | 50 | yes |
| High (the earlier rule) | > 0.10 | > 0.55 | 0.45–0.95 | 40 | no |

### Notification (`IrregularRhythmDetector`)
- 5 of the last 6 *readable* windows are irregular, the irregular ones spread over ≥ 1 hour,
  within 48 hours. Then 24 hours of quiet.
- **ECG follow-up.** Every finished ECG reports its result to the watch's detector
  (`WatchSettingsStore.noteEcg`). If an ECG within 2 hours after a notification shows sinus
  rhythm, the next notification waits 48 hours, and for 7 days it needs all 6 windows irregular.
  The phone's notification list shows "Your ECG afterwards looked regular".
- The watch notification has a *Take an ECG* action.

## 6. Phone statistics (`HeartSummaries`)

| Value | How |
|---|---|
| Your normal | the personal normal and usual range (5th–95th percentile) at rest, the normal in sleep, the limits in use, and the last 28 nights |
| Resting heart rate | 10th percentile of today's `REST` minutes (sleep and exercise left out); ≥ 5 minutes needed |
| Sleep | average and lowest of `SLEEP` minutes |
| Exercise | number of `EXERCISE` minutes and the highest rate |
| HRV | median RMSSD of still minutes (rest or sleep) that carry intervals |
| Zones | Karvonen: 50, 60, 70, 80, 90 % of the reserve between the resting rate and the personal maximum |
| Resting week | the resting heart rate of each of the last 7 days |

The day chart can be filtered by activity. The notification list shows each alert's limit,
activity and the usual rate, trend notices, and the ECG follow-up for rhythm notifications.

## 7. Settings: a recording switch, a monitoring switch and its parts (`MonitorSettings`)

```
All-day heart rate          [on]   recording only; keeps going with monitoring off
Health monitoring           [on]   master switch; turning it off asks twice
   ├─ Heart                 [on]   high/low heart rate, irregular rhythm, resting trend
   ├─ Blood oxygen          [on]   hourly SpO2 and its notices (VITALS_MONITORING.md)
   ├─ Skin temperature      [on]   temperature in sleep and by day, and its notices
   ├─ Stress                [on]   from the rhythm windows (STRESS_MONITORING.md)
   │    └─ Stress notifications [on]
   ├─ Alert sensitivity  Standard  for all parts
   ├─ Your limits                  the limits in use (read only)
   └─ Your health answers          the monitoring setup's questions (below)
```

- **All-day heart rate** (`backgroundHeartRate`) records heart rate in the background. That feeds
  trends, sleep and exercise, and the personal normal, with no notification. It has its own
  switch and keeps recording when monitoring is off.
- **Health monitoring** (`heartMonitoring`, the JSON name kept from older versions) is the master
  switch. With it off, every notification and every background SpO2 and temperature
  measurement stops. The parts keep their own state, so turning it back on restores them.
- **Heart** (`heartAlerts`) turns these on or off together:
  - high and low heart rate;
  - irregular rhythm checks;
  - the resting trend.
- **Blood oxygen** (`spo2Monitoring`) and **Skin temperature** (`skinTempMonitoring`) are
  described in [VITALS_MONITORING.md](VITALS_MONITORING.md). Each part is active only while the
  master is on (`heartActive`, `spo2Active`, `skinTempActive`).
- **How they work together:**
  - The heart part needs the recording. Turning it on (with the master on) also turns all-day
    heart rate on.
  - Turning all-day heart rate off turns the heart part off. Oxygen and temperature go on
    without sleep detection. While the heart part is active, that asks first.
  - With monitoring off, the watch keeps learning the normal from the recording, so the limits
    are ready when it is turned back on.
  - The combined temperature and heart notice needs both the heart and temperature parts.
- **Turning off asks,** on the phone and on the watch:
  - the master asks twice: the first step lists what stops, the second asks "Are you sure?";
  - all-day heart rate while the heart part is active asks twice the same way;
  - the heart part asks once;
  - oxygen and temperature ask nothing, and turning anything on asks nothing.
- **Alert sensitivity** (Low, Standard, High) is shown only while monitoring is on. It sets the
  ratios above and the oxygen and temperature limits. High also uses the looser
  irregular-rhythm rule.
- **Your limits** (read only) shows the normal and the limits in use, or "learning", for each
  active part.

### The monitoring setup (`MonitoringSetupFlow`, phone)

The setup runs on first use, after the profile, and once after an update that asks something
new (`MonitorSettings.SETUP_VERSION` above the version the user went through). Until it is
answered after an update, monitoring keeps running as it was. Settings → *Your health answers*
opens it again. The watch shows "Finish setting up health monitoring on your phone" until the
questions are answered. Its pages:

1. **Health monitoring (required):** what it does, battery, the red light, not a diagnosis.
   Turn on, or not now.
2. **Parts:** heart, blood oxygen, skin temperature and stress, all suggested on.
3. **Health questions (required):** yes, no, or "not sure / rather not say" (counted as no).

   | Question | Effect |
   |---|---|
   | Medicine that slows the heart rate (beta blocker) | Exercise maximum from Brawner 2004 (164 − 0.7 × age) instead of Tanaka |
   | Diagnosed atrial fibrillation | No rhythm checks and no stress (HRV can't be read). High and low heart rate stay |
   | Pacemaker or ICD | No rhythm checks, no low heart rate notices and no stress. High heart rate stays |
   | Lung condition with usually low oxygen (COPD) | Oxygen limit 88 % at every sensitivity (BTS target 88–92 %), and night lows counted below 88 |

4. **Fine-tune (optional, can be skipped):**
   - sensitivity;
   - endurance training: the first guess of the resting rate is 50;
   - pregnancy: no temperature notices, and a 14-day heart-rate normal;
   - stress notifications;
   - blood oxygen in sleep;
   - usual sleep hours: these stand in when the watch can't recognise sleep;
   - quiet hours (default 22:00–7:00): no stress or trend notices then, while heart and oxygen
     notices always come.
5. **Summary:** what is on, and what the answers changed.

The answers are stored in `MonitorSettings.health` (`HealthContext`) on the phone and the watch
only. They are not in exports or diagnostic logs. The rhythm switch older watches read
(`irregularRhythmEnabled`) follows the answers too.

Older versions:
- `heartMonitoring` defaults to on unless both old notification parts (high/low alerts, rhythm)
  were off. The new parts default to on. Recording stays as it was.
- `withMonitoring` and `withHeartAlerts` also write the old part switches, so an older watch
  follows the heart part. An older watch ignores the oxygen and temperature parts.
- Fields that are no longer used stay in the JSON format.

### Checking a notice from a log

- **Watch.** Every notice is logged on the watch as `Heartline/Alert: fired kind=… vital=… bpm=…
  value=… limit=… normal=… context=… windows=…`. `normal=none` means the wearer's normal was not
  learnt yet.
- **Phone.** It logs `received … watch=<watch app version> notified` (or `duplicate, not
  notified`). The watch resends a notice until the phone acknowledges it, and only the first copy
  notifies.
- **Watch version.** Each alert keeps the watch app version that sent it (database v9). The
  alert list marks alerts from a watch app version other than the phone's, and Home shows
  *Update the watch app* while the versions differ: a watch on another version runs other
  checks.
- **The case behind these.** In a real log, the phone was updated an hour before the watch. The
  old watch app then sent an irregular-rhythm notice from status −10 readings, and it looked like
  the new version's.
- **Replay.** `tools/replay/extract.py` turns a log's raw tracker readings into a test fixture
  (`ReplayTest`). That window is one of them: the current checks reject it as unreadable.

## 8. Tests

- `shared`:
  - `HeartBaselineTest`:
    - the ratios equal the clinical numbers;
    - defaults before data;
    - personal after a day;
    - high normal, athlete and the bounds;
    - limits outside the wearer's own range;
    - sensitivity order;
    - the learned exercise maximum and its bounds;
    - the trend (3 of 5 nights, latest included);
    - raised days not learnt;
    - night assignment;
    - Karvonen zones.
  - `HeartRateAlertRulesTest`:
    - exercise vs rest;
    - sparse readings;
    - recovery;
    - sleep limits;
    - the two switches (monitoring off keeps recording);
    - cooldown keys;
    - old JSON.
  - `IrnQualityTest`, `HeartTest`.
- `wear`:
  - `HeartSimulationTest` (section 9);
  - `HeartMonitorTest`;
  - `BackgroundHeartTest`.
- `phone`:
  - `HeartRepositoryTest`: minute merge, limits kept, the phone's own baseline, summaries,
    zones, ECG follow-up;
  - `MigrationTest`: v4 → v5 → v6.
- On a device: [DEVICE_TESTING.md](../DEVICE_TESTING.md), checklist item 15.

## 9. Simulation

`wear/src/test/.../sim/HeartSimulation.kt` runs 60 days of a simulated wearer through the
watch's own `HeartMonitor`: minutes, activity, baseline, limits, rules and trend.

The simulated wearer has:
- a resting rate with day-to-day drift (SD 2.5 bpm, Quer 2020), minute noise, and a circadian
  swing peaking in the afternoon (Avram 2019);
- a sleep dip of 12–13 %;
- workouts at a share of the heart-rate reserve with warm-up, intervals and recovery;
- walks, two 6-minute stress spikes (+20) and a morning coffee (+8 for an hour) every day;
- hours and a whole day without the watch.

Passive heart rate arrives as from Health Services: every 5–10 minutes at rest and asleep, every
5 s in a workout, every minute on a walk, delivered every 15 minutes.

Abnormal episodes are injected. Each is clearly abnormal for that person, at least about 15 %
beyond the standard limit:
- 40 minutes of resting tachycardia or bradycardia;
- an hour of very low heart rate in sleep;
- overexertion above the true maximum;
- 4–5 nights of illness (+9 to +11 bpm).

An episode right at a limit may or may not notify, as with any threshold.

Results (`build/reports/heart-simulation.md`, seed 7; Standard unless named):

| Wearer | Episodes | Personal: caught / false alerts | Fixed 120/40/35: caught / false alerts |
|---|---|---|---|
| Typical adult (40, resting 65) | 4 | 4 / 0 | 4 / 0 |
| High normal (35, F, resting 84) | 2 | 2 / 0 | 2 / 0 |
| Athlete (28, resting 47) | 2 | 2 / 0 | 1 / **24** (low in sleep almost every few nights; resting 100 bpm missed) |
| Older adult (70, resting 72) | 2 | 2 / 0 | 1 / 0 (awake 42 bpm missed) |
| Runner without workout tracking (45, resting 60) | 2 | 2 / 0 | 1 / 0 (resting 108 bpm missed) |
| Quiet routine (55, resting 68), illness | 1 | 1 / 0 | 1 / 0 |
| **All** | **13** | **13 / 0** | **10 / 24** |

- **Other random histories.** Over five more random 60-day histories per wearer (30 runs),
  personal limits caught every episode with **0 false alerts**.
- **First two weeks.** Two weeks of normal life without episodes gave no notification for any
  wearer, learning included. A resting rate of ~150 on the first afternoon was caught before
  anything was learnt.
- **Sensitivity.**
  - *Low* notifies only clear changes. It missed two of the moderate episodes: the typical
    adult's ×1.8 and the older adult's ×0.58.
  - *High* caught every episode with no false alerts.

The simulation and the pipeline tests also changed the design. Four findings:
1. **Low limits while learning.** They start at the safety floors, not the old 40 / 35, which
   gave false low alerts on an athlete's first night.
2. **No learning from population-guess limits or recovery.** Limits come from the wearer's own
   data only, and recovery minutes after moving are not learnt. Otherwise an athlete's first
   night, and a runner's raised post-run minutes, pushed the limits the wrong way.
3. **Athletes' sleep.** Their own nights may set the sleep low limit below 30 (down to 28).
4. **Exercise maximum from earlier days only.** The pipeline tests found that an overexertion
   taught the exercise maximum during the episode itself (the 99th percentile of one session is
   its peak), so it no longer counts towards today's limit.

## 10. Known limits

- Simulated data is not real data. The model follows published numbers, but real wearers will
  differ. Device checks are in DEVICE_TESTING item 15.
- If Samsung Health's continuous heart rate is off, passive heart rate may hardly arrive. Then
  rhythm windows are skipped as "not worn", and learning is slower.
- Activity recognition is the watch's own. A workout that isn't started on the watch is only
  seen through steps (judged as "moving", with the same exercise maximum).
- The day-of-week and time-of-day pattern is not modelled separately: the 99th-percentile guard
  covers a normal afternoon rise.

## References

- Tanaka H, Monahan KD, Seals DR. Age-predicted maximal heart rate revisited. *J Am Coll Cardiol* 2001.
- Quer G et al. Inter- and intraindividual variability in daily resting heart rate… 92,457 adults. *PLOS ONE* 2020. https://doi.org/10.1371/journal.pone.0227709
- Avram R et al. Real-world heart rate norms in the Health eHeart study. *npj Digit Med* 2019. https://www.nature.com/articles/s41746-019-0134-9
- Mishra T et al. Pre-symptomatic detection of COVID-19 from smartwatch data. *Nat Biomed Eng* 2020. https://www.nature.com/articles/s41551-020-00640-6
- Alavi A et al. Real-time alerting system for COVID-19 and other stress events using wearable data. *Nat Med* 2022. https://www.nature.com/articles/s41591-021-01593-2
- Kusumoto FM et al. 2018 ACC/AHA/HRS Guideline on the Evaluation and Management of Patients With Bradycardia and Cardiac Conduction Delay. *Circulation* 2019. https://www.ahajournals.org/doi/10.1161/CIR.0000000000000628
- Blunted heart rate dip during sleep and all-cause mortality. *JAMA Intern Med*. https://jamanetwork.com/journals/jamainternalmedicine/fullarticle/486862
- Apple Watch heart notifications: https://support.apple.com/en-lb/guide/watch/apde39f5426c/watchos · Fitbit / Google: https://support.google.com/googlehealth/answer/14237938
- Karvonen MJ et al. The effects of training on heart rate. *Ann Med Exp Biol Fenn* 1957.
- Dash S et al. Automatic real time detection of atrial fibrillation. *Ann Biomed Eng* 2009.
- Petrėnas A et al. Low-complexity detection of atrial fibrillation in continuous long-term monitoring. *Comput Biol Med* 2015.
