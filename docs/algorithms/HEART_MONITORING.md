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

Activity info and steps are requested only when the *Sleep and exercise awareness* setting is on
and `ACTIVITY_RECOGNITION` is granted. If Health Services refuses the registration with activity
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

## 4. High and low heart rate (`HeartRateAlertRules`)

Each minute is judged by the limits of what the wearer was doing:

| Activity | High notification | Low notification |
|---|---|---|
| Rest | above `highBpm` (default 120) | below `lowBpm` (default 40) |
| Sleep | above `highBpm` | below `sleepLowLimit` (default `lowBpm` − 5) |
| Exercise / moving | above the exercise limit: `exerciseMaxBpm`, or the age-based maximum 208 − 0.7 × age (Tanaka 2001; 190 without an age) | never |

- **"Held"** means every reading over a span is beyond the limit:
  - Rest and sleep: ≥ 10 minutes covered, ≥ 3 readings, no gap over 10 minutes.
  - Exercise: ≥ 3 minutes, gaps ≤ 2 minutes.

  This is time covered, not a count of back-to-back minutes. Passive heart rate at rest comes
  every few minutes, so "10 back-to-back minutes" in practice only happened during workouts. The
  old rule therefore alerted mostly during exercise, the opposite of its intent.
- **Recovery.** Minutes within 15 minutes after exercise, or within 5 minutes after moving, are
  not judged by the rest limits.
- **Cooldown.** One notification per kind every 3 hours. Exercise notifications have their own
  cooldown key (`HIGH_HEART_RATE/EXERCISE`).
- **Notification.** `HealthAlert` carries the limit (`threshold`) and the activity (`context`).
  The text names both, for example "While you were resting, your heart rate stayed above 120 bpm
  for over 10 minutes (up to 131 bpm)."

## 5. Irregular rhythm

### Scheduling and wearing (`IrnWindowWorker`)
- Every 15, 30 or 60 minutes (setting), WorkManager runs one window of about 75 s on the Samsung
  tracker. Without the background sensor permission, a silent notification shows for that minute.
- **The window is skipped when the watch is not worn:**
  - when the all-day heart rate is on and there has been no passive heart rate for an hour (or
    ever since install);
  - when Android's off-body sensor says the watch is off the wrist (checked 1.5 s after it
    starts, and during the window).
- If a readable window was irregular and the interval is over 15 minutes, one extra check runs
  15 minutes later (`scheduleFollowUp`).

### Is the window readable? (`IbiWindowQuality`)
A window of about 60 s is analysed only if all of these hold. Otherwise it is *unreadable*: never
counted as irregular, and not as regular either.

| Check | Limit |
|---|---|
| On the wrist | no off-body sample |
| Still | no steps and no arm movement (accelerometer > 1 m/s² from its average) in the last minute |
| Signal | ≤ 5 % of samples not `reliable` |
| Rejected intervals | ≤ 10 % (tracker-flagged, out of 300–2000 ms, or from unreliable samples) |
| Coverage | the intervals add up to ≥ 85 % of the window (no gaps) |
| Rate | 40–150 bpm, and within 15 % of the tracker's own median heart rate |

The reason a window was skipped is logged (`IRN window done: … skipped=…`).

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
| Resting heart rate | 10th percentile of today's `REST` minutes (sleep and exercise left out); ≥ 5 minutes needed |
| Sleep | average and lowest of `SLEEP` minutes |
| Exercise | number of `EXERCISE` minutes and the highest rate |
| HRV | median RMSSD of still minutes (rest or sleep) that carry intervals |
| Zones | minutes in 50–60, 60–70, 70–80, 80–90, 90+ % of the maximum (age-based, or the exercise limit setting) |
| Resting week | the resting heart rate of each of the last 7 days |

The day chart can be filtered by activity. The notification list shows each alert's limit and
activity, and the ECG follow-up for rhythm notifications.

## 7. Settings (`MonitorSettings`, synced, the newer copy wins)

- All-day heart rate; sleep and exercise awareness.
- Irregular rhythm on/off, interval, sensitivity.
- High and low heart rate notifications: a master switch, separate switches and limits for high
  and low, a sleep low limit, and a switch and limit for exercise.
- Show heart notifications on the watch and/or the phone.

Every new field has a default, so settings from older versions still decode.

## 8. Tests

- `shared`:
  - `HeartRateAlertRulesTest`: exercise vs rest, sparse readings, recovery, sleep limits,
    toggles, cooldown keys, old JSON.
  - `IrnQualityTest`: off-wrist noise, unreliable readings, premature beats, AF-like rhythm,
    spread over an hour, ECG follow-up.
  - `HeartTest`.
- `wear`:
  - `HeartMonitorTest`.
  - `BackgroundHeartTest`: alert history survives a new process, exercise doesn't alert, the
    last minute is closed, rhythm minutes carry HRV.
- `phone`:
  - `HeartRepositoryTest`: minute merge, resting without sleep/exercise, zones, ECG follow-up.
  - `MigrationTest`: database v4 → v5.
- On a device: [DEVICE_TESTING.md](../DEVICE_TESTING.md), checklist item 15.

## 9. Known limits

- The limits for rest and sleep are fixed numbers that the user chooses. People's normal resting
  heart rate differs by up to 70 bpm (Quer 2020), so one fixed limit is too loose for some and
  too tight for others. See the plan below.
- The age-based maximum has a spread of about ±10 bpm. Fit people often exceed it in hard
  workouts.
- If Samsung Health's continuous heart rate is off, passive heart rate may hardly arrive. Then
  rhythm windows are skipped as "not worn".
- Activity recognition is the watch's own. A workout that isn't started on the watch is only
  seen through steps.

## 10. Next: limits from the wearer's own normal (planned)

Not built yet. The design:
- A 28-day **personal baseline** on the watch: robust median and spread (MAD) of the wearer's own
  `REST` and `SLEEP` minutes, learnt over a first week (fixed limits until then). Minutes around
  notifications are not learnt from.
- **Personal limits** as a percentage of that baseline, for example high = resting median + 45 %,
  low = sleep median − 25 %. They are kept between fixed safety bounds, and an absolute safety
  limit always applies, so a baseline that is itself abnormal can't hide a problem.
- A **learned exercise maximum** from the wearer's own hardest workouts, never below the
  age-based value.
- A **trend notice** when the overnight resting heart rate stays well above the baseline for two
  or more nights (Mishra 2020, Alavi 2022). This is wellness wording, not a diagnosis.
- The phone shows "your normal" and the limits currently in use.

## References

- Tanaka H, Monahan KD, Seals DR. Age-predicted maximal heart rate revisited. *J Am Coll Cardiol* 2001.
- Quer G et al. Inter- and intraindividual variability in daily resting heart rate… 92,457 adults. *PLOS ONE* 2020. https://doi.org/10.1371/journal.pone.0227709
- Avram R et al. Real-world heart rate norms in the Health eHeart study. *npj Digit Med* 2019. https://www.nature.com/articles/s41746-019-0134-9
- Mishra T et al. Pre-symptomatic detection of COVID-19 from smartwatch data. *Nat Biomed Eng* 2020. https://www.nature.com/articles/s41551-020-00640-6
- Alavi A et al. Real-time alerting system for COVID-19 and other stress events using wearable data. *Nat Med* 2022. https://www.nature.com/articles/s41591-021-01593-2
- Dash S et al. Automatic real time detection of atrial fibrillation. *Ann Biomed Eng* 2009.
- Petrėnas A et al. Low-complexity detection of atrial fibrillation in continuous long-term monitoring. *Comput Biol Med* 2015.
