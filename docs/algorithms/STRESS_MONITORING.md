# Background stress monitoring

Health monitoring reads stress from the heart, in the background:
- every 15 minutes it takes a heart-rate variability (HRV) and heart-rate reading while the wearer
  is awake and still;
- it scores each reading against the wearer's own usual;
- it gives a notice after about an hour of high stress, with a minute of guided breathing;
- the phone shows the day, the week and the HRV in sleep.

This is a wellness indicator, as Garmin, Samsung and Fitbit offer, not a diagnosis. HRV also
falls with illness, poor sleep, alcohol, caffeine or a hard workout.

Code:
- `shared/.../stress/Stress.kt`: score, normal and notices; pure and unit tested.
- `wear/.../monitor/StressWindows.kt` and the rhythm window worker (`IrnWindowWorker`): the
  watch side.
- `phone/.../ui/model/MetricModels.kt` (`BackgroundStress`) and `MetricDetailScreen.kt`: the
  phone side.

Settings and the monitoring setup are described in
[HEART_MONITORING.md section 7](HEART_MONITORING.md#7-settings-a-recording-switch-a-monitoring-switch-and-its-parts-monitorsettings).

## 1. Research

| Source | Finding | Used for |
|---|---|---|
| Task Force ESC/NASPE 1996; Shaffer and Ginsberg 2017 | RMSSD reflects vagal (rest-and-digest) activity and is valid even from about a minute of beats | The signal |
| Kim et al. 2018 (review of HRV and stress) | Mental stress lowers HRV and raises heart rate; levels differ a lot between people | Personal, not population, scoring; both signals |
| Taelman et al. 2009 | Heart rate rises consistently with mental load | Heart rate weighted in |
| Plews, Buchheit and others | ln RMSSD is tracked against a personal rolling baseline | The normal |
| Stanley et al. 2013 | Vagal activity takes up to an hour or more to come back after exercise | The hour after exercise is left out |
| Firstbeat / Garmin, Samsung, Fitbit | A 0–100 score from HRV, only at rest, with rest, low, medium and high levels | The score's shape |
| ESC 1996 and device makers | HRV means nothing in atrial fibrillation or with a pacemaker | Stress off for those wearers |

## 2. Measuring: no extra battery

- **Windows.** The watch already records a 75-second window of beat-to-beat intervals every
  15 minutes for the rhythm checks. Stress reuses those windows.
- **When the worker runs.** It runs while the rhythm checks or stress are active. A window is
  read for rhythm only with the heart part on, and for stress only with the stress part on.
- **Which windows count.** A window gives a stress reading only when:
  - it passes the rhythm checks' quality test (`IbiWindowQuality`): worn, still, a clean and
    complete series of beats, and a rate matching the tracker's;
  - it is not judged irregular;
  - after isolated extra beats are removed, RMSSD and heart rate are computed.
- **What the wearer was doing:**
  - exercise, and the hour after it, gives no score;
  - asleep, the window only feeds the night's HRV. Without activity recognition, the usual
    sleep hours from the setup stand in;
  - moving gives no score;
  - only awake and still gets a score.

## 3. The score (`StressBaseline`)

**The normal.**
- The window: the awake, still windows of the last 28 days, leaving out days of illness or with
  a notice.
- The centre: the median of ln RMSSD and of heart rate.
- The spread: 1.4826 × MAD, at least 0.25 for ln RMSSD (the usual within-person SD of
  ultra-short RMSSD) and 3 bpm for heart rate.
- High windows are not learnt, so a stressful spell never becomes the normal.

**The score.**

```
z     = (ln RMSSD − usual) / spread           (lower HRV → negative)
hrZ   = (heart rate − usual) / spread
raw   = −0.6 z + 0.4 hrZ
score = 30 + 18 raw, from 0 to 100
```

- At the wearer's usual, the score is about 30 (low). Levels: below 34 low, below 67 medium,
  67 or more high. High means about 2 SD worse than usual.
- The simulation chose the weights 0.6/0.4 over 0.75/0.25. Heart rate separated stress from
  noise better (section 6).

**Learning.**
- The first score is the earlier population score (`StressIndex`).
- It becomes the wearer's own with the weight windows / (windows + 20). About 30 windows a day
  qualify, so it is mostly personal after a day.
- Notices wait for 100 learnt windows, about 3 to 4 days.

**Manual measurement.** The one-minute stress measurement on the watch uses the same personal
score, so manual and background numbers agree. Skin conductance (Watch8+) still nudges it by up
to ±15.

## 4. Notices (`StressMonitor`)

**Sustained high stress** (about an hour) needs all of these:
- all the scored windows of the last 75 minutes: at least 4, spanning 45 minutes or more;
- their median high, and their average at least 62;
- the latest window high.

The median means one deep window (an artefact, a sneeze) can't make a spell, and one calmer
window can't hide one. The simulation chose this over "three high windows in a row" (section 6).

**Never:**
- before 100 learnt windows;
- in the quiet hours (default 22:00–7:00, chosen in the setup);
- more than once in 3 hours or twice a day;
- when illness explains it, that is, a night at least +0.5 °C warmer or a raised heart rate in
  sleep (`HeartBaseline.nightRaised`). The combined notice covers that, and the day is not learnt;
- with the stress notifications switch off.

**The notice:** "For about an hour your heart rate and its variability have looked stressed. A
minute of slow breathing can help." The watch notice has a **Breathe for a minute** button.

**Breathing.**
- One minute at six breaths a minute, 5 s in and 5 s out (resonance breathing).
- A soft vibration marks each change, and a circle grows and shrinks.
- At the end it offers a stress measurement.

**The week's insight.** This is shown on the phone, not sent as a notice. Both must hold:
- the last 7 days average at least 15 points above the usual of the 3 weeks before (illness days
  left out);
- the last nights' HRV in sleep is below the wearer's usual.

## 5. Phone

- **Sync.** Each window's reading goes to the phone in `HrBatch.stress` (`StressSample`) with
  `stressLimits`. An older phone ignores both.
- **Storage.** The phone keeps them in `stress_samples` (database version 8). Version 8 also
  stores each alert's `vital` and `value`, so the alert list names oxygen, temperature and
  stress notices correctly.
- **Stress screen.** A *Measured by your watch* card:
  - today's average and level, and minutes in high stress;
  - sleep HRV against the usual;
  - today in 15-minute slots (7:00–23:00);
  - the last 7 days;
  - the insight;
  - *Learning* until the score is personal.
- **Home tile.** It shows the latest background score when it is newer than the last
  measurement.
- **Settings.** *Stress* and *Stress notifications* sit under health monitoring, and *Your
  limits* shows the usual HRV.

## 6. Simulation (`StressSimulationTest`)

**Method.**
- **Duration and pace.** 60 days per wearer through the real `StressMonitor`, with a window
  every 15 minutes: awake 7:00–23:00, asleep otherwise.
- **Skipped windows.** 30 % of awake windows are skipped as moving, 10 % during a stressful
  spell at a desk.
- **Noise:**
  - ln RMSSD noise SD 0.25 between windows, 0.1 day to day, and −0.05 in the afternoon;
  - heart-rate noise SD 3 bpm;
  - a coffee at 9:00 every day: one window with RMSSD −33 % and +6 bpm.
- **Exercise.** Excluded, and the half hour after it still counts as rest while the body
  recovers (+15 bpm, RMSSD −25 %).
- **Stress spell.** 90 minutes with RMSSD −40 % and +10 bpm.

**Results** (seed 7):

| Wearer | Episodes | Notices | False |
|---|---|---|---|
| Typical adult, a coffee every morning | – | none | 0 |
| Stressful afternoons at work, days 20, 33, 47 | 3 | 3, on those afternoons | 0 |
| Exercise every other day | – | none | 0 |
| Illness, days 40–42 (temperature, heart rate and HRV) | – | none: the combined notice covers it | 0 |
| Endurance athlete (RMSSD 90, 48 bpm, daily training) | – | none | 0 |
| Beta blocker (RMSSD 35, 58 bpm), blunted spell on day 44 | 1, reported only | 1 | 0 |
| A stressful week, days 30–36 | – | 2 (on day 31), insight on days 33–37 | 0 |

**In 105 more random histories (15 per wearer, 6,300 days):**
- 41 of 45 spells caught (91 %);
- 2 false notices;
- blunted beta-blocker spells: 6 of 15 caught.

A second set of 105 histories that the tuning never saw gave 41 of 45 and 0 false notices.

**Pass mark.** Every spell caught and at most 1 false notice per wearer at seed 7; at least
90 % caught and at most 2 false notices in the 105 histories. A moderate spell is only a few
noise SDs above a calm day, so the result is statistical, not perfect.

**What the simulation changed:**

| First design | Problem found | Final |
|---|---|---|
| 3 high windows in a row (45 min) | One deep window made false notices, and one calmer window hid real spells | All windows of the last 75 min: ≥ 4, high median, average ≥ 62, latest high |
| Weights 0.75 HRV / 0.25 heart rate | Spells missed: heart rate separated them better | 0.6 / 0.4 |
| Notices from about a day | False notices in the first days: the spread of a few days misses day-to-day variation | Notices after 100 windows; spread at least 0.25 |
| Recovery after exercise counted | False notices 30–60 min after a workout | The hour after exercise not scored |
| Illness counted as stress | False notices and a false "stressful week" | Illness days not notified, learnt or counted in the week |

The report is written to `shared/build/reports/stress-simulation.md`.

## 7. Tests

- `StressTest`:
  - population start and personal score;
  - one notice for a sustained spell, none for two windows, while learning or in quiet hours;
  - none for illness, exercise or sleep;
  - high windows not learnt;
  - the health answers (Brawner, AF, pacemaker, lung, pregnancy, endurance);
  - quiet hours and old JSON;
  - the limits.
- `StressSimulationTest`: section 6.
- `StressWindowsTest` (watch): RMSSD and rate of a window, moving rejected, the hour after
  exercise, usual sleep hours.
- Phone:
  - `HeartRepositoryTest.stressReadingsAndVitalNoticesAreKept`;
  - `MigrationTest.migrate7To8…`;
  - `MonitoringSetupTest` (the setup's answers, and the stress card).
- Screenshots: the stress screen, settings, the setup pages, breathing and the watch settings.

## 8. Known limits

- **Not specific.** Caffeine, alcohol, poor sleep, illness and posture all move HRV. Illness is
  left out when the other parts see it; the rest is not.
- **Beta blockers** blunt the heart-rate rise and so the score: about half of the spells are
  caught (section 6).
- **AF or a pacemaker:** stress is off, since HRV can't be read.
- **Still windows only.** Stress while walking or moving about is not seen.
- **No validation on real people yet.** The weights and limits come from the literature and
  the simulation; real wearers' data will tune them.
- **Skin conductance** (Watch8+) is used in manual measurements only, not in the background.

## References

- Task Force of the ESC and NASPE. Heart rate variability: standards of measurement,
  physiological interpretation and clinical use. *Circulation* 1996.
- Shaffer F, Ginsberg JP. An overview of heart rate variability metrics and norms. *Front Public
  Health* 2017.
- Kim HG et al. Stress and heart rate variability: a meta-analysis and review of the
  literature. *Psychiatry Investig* 2018.
- Taelman J et al. Influence of mental stress on heart rate and heart rate variability. *IFMBE
  Proc* 2009.
- Plews DJ et al. Training adaptation and heart rate variability in elite endurance athletes.
  *Int J Sports Physiol Perform* 2013.
- Stanley J, Peake JM, Buchheit M. Cardiac parasympathetic reactivation following exercise.
  *Sports Med* 2013.
- Brawner CA et al. Predicting maximum heart rate among patients with coronary heart disease
  receiving beta-adrenergic blockade therapy. *Am Heart J* 2004.
- Lehrer PM, Gevirtz R. Heart rate variability biofeedback: how and why does it work? *Front
  Psychol* 2014.
