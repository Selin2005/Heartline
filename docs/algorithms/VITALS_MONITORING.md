# Background blood oxygen and skin temperature

Besides heart rate, Heartline's health monitoring measures blood oxygen (SpO2) and skin
temperature in the background:
- It learns the wearer's usual values.
- It gives a notice when a reading is confirmed low, or when nights change from the usual.
- The phone shows the readings, the usual values and the last 28 nights.

Heartline is a wellness app, not a medical device. A wrist oximeter is not a medical
oximeter, and wrist temperature is not body temperature. Notices therefore say "lower than
usual" or "warmer than usual", never a diagnosis.

Code:
- `shared/.../vitals/Vitals.kt`: rules, baselines and notices; pure and unit tested.
- `wear/.../monitor/VitalsWorker.kt`: scheduling and measuring.
- `phone/.../data/HeartRepository.kt`, `phone/.../ui/model/MetricModels.kt`
  (`BackgroundVitals`) and `MetricDetailScreen.kt`: the phone side.

The heart-rate side and the settings structure are in [HEART_MONITORING.md](HEART_MONITORING.md).

## 1. Research and key numbers

| Source | Finding | Used for |
|---|---|---|
| Apple Heart & Movement Study (33,080 people) | SpO2 averages 96.2 % by day and 95.4 % at night (0.8 lower). Small drop with age. About 2.5 % of all readings are under 90 | Starting guesses; a low reading always checked again |
| BTS oxygen guideline (O'Driscoll 2017) | Adult normal 96–98 %; over 70, 92–94 % is common | Limits; age shift |
| Sleep studies in healthy adults | The lowest night value averages about 90 %; dips of 1–3 points are normal | Only sustained, confirmed lows notify |
| Sjoding 2020 (NEJM); FDA safety communication 2021 | Pulse oximeters are less accurate on darker skin; occult hypoxaemia is SaO2 < 88 % with a normal reading | Never notify on one reading; low sensitivity limit 88 % |
| Apple wrist temperature | Measured in sleep; reported as the change from a baseline after about 5 nights | Method for temperature |
| Smarr 2020 (Sci Rep, TemPredict) | A rise in night skin temperature goes with reported fever, sometimes before symptoms | "Warmer than usual" notice |
| Maijala 2019 (BMC Womens Health) | Skin temperature rises about 0.3–0.5 °C in the luteal phase | Notice limit well above the cycle |
| Mishra 2020, Alavi 2022 | Resting heart rate and temperature together show illness earlier than either alone | Combined notice |

## 2. Measuring (`VitalsWorker`, watch)

Health Services gives no background SpO2 or temperature. The worker therefore runs the Samsung
Health Sensor SDK's on-demand trackers itself (`SPO2_ON_DEMAND`, `SKIN_TEMPERATURE_ON_DEMAND`).
It uses the same sources as a manual measurement, through `VitalsMeasurer`.

- **When it runs.** WorkManager runs it every 30 minutes, only while the master switch and at
  least one of the two parts are on. Each part is measured only when it is active.
- **Skin temperature** (about 10 s) is measured every 30 minutes asleep and hourly by day.
- **SpO2** (about 30–40 s) is measured every hour, awake and asleep. By day it is skipped on a
  battery under 15 %; asleep it is always measured.
- **Conditions,** the same as the rhythm windows:
  - The watch is worn: background heart rate in the last hour, and the off-body sensor.
  - The wearer is not exercising.
  - The wearer is still: not marked active, and no steps in the last 3 minutes (20 a minute
    asleep, 5 a minute awake).
  - Awake, the arm is also watched for 15 s before SpO2, and any movement puts it off. In a real
    two-day log most by-day tries ended "hold still" (status −4) after 30–40 s: only 2 of about
    16 gave a value.
- **Logged reasons.** A try without a result logs why (`SpO2 no result after 32 s: timed out
  (HOLD_STILL)`), as does a rejected temperature and SpO2 skipped for the battery or the sleep
  setting.
- **Moving.** When the wearer is moving, SpO2 is tried again 15 minutes later, twice at most.
  After that the hour is left out.
- **One sensor at a time.** The worker and the rhythm windows share one lock
  (`BackgroundSensors.lock`), so they never measure at the same time.
- **Permission.** Without the background sensor permission, the worker runs as a silent
  foreground service.
- **Cost.** About 24 SpO2 readings a day, each with a red and infrared light that may be seen
  in the dark. Battery use is checked on the device (see DEVICE_TESTING, item 15).

## 3. Reading quality (`VitalsQuality`)

**SpO2.** A reading is kept only when:
- it completed (SDK status 2), within 70–100 %;
- there was no movement during it;
- the pulse it reports is within 15 % of the recent background heart rate. A mismatch means the
  sensor locked onto noise.

**Skin temperature.** A reading is kept only when:
- it completed (status 0), with skin between 30 and 40 °C. A watch lying on a table cools to
  the room, below 30 °C, while covered wrist skin stays at 30–35 °C;
- the off-body sensor says the watch is worn (checked by the worker);
- the "ambient" value has not changed by more than 5 °C since the last reading (a shower, or
  going outside).

The ambient value is **not** compared with skin. An earlier rule required skin at least 1.5 °C
warmer than ambient. A real Galaxy Watch8 Classic log showed that on Samsung watches the
ambient sensor sits inside the case and reads warmer than skin when worn (skin 34.7 °C,
ambient 35.9 °C), so that rule rejected every reading.

## 4. The usual values (`VitalsBaseline`)

The history is one `VitalsDay` per day, keeping 35 days:
- SpO2 histograms for the day (at rest) and for the night that ends that morning;
- the number of night readings below 90 %;
- the night's counted temperatures.

Readings in sleep belong to the day the night ends.

**SpO2 usual.**
- It is the median of the last 28 days, by day and asleep.
- It starts from the population (96.2 % by day, 95.4 % asleep), minus 0.043 a year over 40.
- It moves to the wearer's own median with the weight n / (n + 6) readings. One night of
  7 readings is already more than half the wearer's own.
- Low readings (below the limit) are never learnt.

**Night temperature** (as Apple and Oura):
- The value for each night is the median of the readings taken asleep after the first hour
  (`counted`), which is when the wrist has warmed up. It needs at least 4 readings.
- The **usual night** is the median of the last 28 nights.
- The **spread** is 1.4826 × the median absolute deviation (MAD), at least 0.2 °C. For normal
  nights this equals the standard deviation. Unlike the SD, it is not moved by the first warm
  night of an illness or by a few nights under a warmer blanket. The simulation showed that
  this matters (section 8).
- The change is shown from the 3rd night. Notices start from the 5th.

**Not learnt:** nights behind a notice are marked `unusual` and left out of every baseline, as
for heart rate.

## 5. Notices

Every notice comes under its part's switch and the master switch. Sensitivity applies to all.
They go to the phone as an existing `AlertKind` plus a `vital` field (`SPO2_LOW`,
`SPO2_NIGHTS`, `TEMPERATURE`, `COMBINED`). The watch resends a notice until the phone
acknowledges it, and an older phone can't decode a new kind, so it would fail on every resend.
An older phone shows these notices with a heart-rate title instead. Update both apps together.

### Confirmed low blood oxygen (`onSpo2`)

| Sensitivity | Limit (readings **below** it count) | Why |
|---|---|---|
| Low | 88 % | Occult hypoxaemia (Sjoding 2020); BTS target for people at risk |
| **Standard** | **90 %** | Clinical hypoxaemia |
| High | 92 %, if the wearer's usual by day is at least 95 %; otherwise usual − 3, never below 90 % | Lowest stable value in healthy older adults (BTS); guarded for wrists that read low |

- **Clinical, not personal.** The limit is the same for everyone, so a low usual value (an
  older wearer at 93 %) can't hide a real fall.
- **Three low in a row.** A reading below the limit is measured again 2 minutes later, at most
  twice, while it stays low. Only three readings in a row below the limit notify.
  - One artefact is common, because wrist oximeters are off by 2–4 %.
  - Two in a row was still too many false notices in the simulation (section 8).
- **Cooldown:** at most one such notice in 6 hours.

**Why high sensitivity is guarded.** A real Galaxy Watch8 Classic read 90–92 % at rest for a
healthy wearer, within a wrist oximeter's 2–4 % error of 92 %. A flat 92 % limit would re-check
(with the red light) most hours and give false notices. So 92 % applies only to wearers whose own
usual by day is at least 95 %. For the others the limit is their usual − 3 %, never below the
clinical 90 %, and it is 90 % until their usual is learnt (20 readings). The simulation checks
this (`highSensitivityOnAWristThatReadsLow`): no false notices for a 93 % wrist or a typical
97 % one, and a sustained 88 % still caught.

### Lower in sleep than usual (`afterNight`, SPO2_NIGHTS)

This is checked once a day, at or after 10:00. It notifies when either holds:
- the night median is at least **3 points** (low 4, high 2) below the wearer's usual night on
  **2 of the last 3 nights**. "Usual" is computed without those 3 nights, and needs at least
  **5 of the wearer's own nights**;
- **3 or more readings below 90 % in one night, each confirmed by its re-check.** A low reading
  whose re-check is back above 90 % was an artefact and does not count.

At high sensitivity (a 2-point drop), all 3 of the last 3 nights must be lower, not 2: a
2-point drop over 2 nights is within a wrist oximeter's noise.

A trip to high altitude gives this notice too. The wording names altitude, illness and sleep,
and diagnoses nothing.

### Warmer nights (`afterNight`, TEMPERATURE)

| Sensitivity | Rise above the usual night (both) |
|---|---|
| Low | ≥ +1.2 °C and ≥ 3 × spread |
| **Standard** | **≥ +1.0 °C and ≥ 3 × spread** (over twice the menstrual cycle's 0.3–0.5 °C) |
| High | ≥ +0.7 °C and ≥ 2.5 × spread |

- **Two nights in a row.** Their average must pass the rise, and each night must reach at least
  70 % of it. Averaging halves the night-to-night noise, while one warm night alone (a blanket)
  can't do it.
- **Cooler nights** never notify. They depend too much on the room.
- **Cooldown:** 3 days.

### Combined: warmer night and raised heart rate (COMBINED)

- **When.** One night at least **+0.5 °C** above the usual, and a sleeping heart rate raised the
  same night (`HeartBaseline.nightRaised`): above the 28-night average by 2 SD, at least 6 bpm.
- **Instead, and sooner.** It replaces the separate notices and comes after **one** night.
- **No repeats.** No temperature notice follows while a combined notice is recent.
- **Needs both parts.** The heart part must be on as well as the temperature part.

The texts stay at the wellness level, for example "Your skin was about 1.1 °C warmer than usual
for two nights. Rest, and if you feel unwell, check your temperature with a thermometer."

### Health answers (monitoring setup)

- **Lung condition** with usually low oxygen: the limit is 88 % at every sensitivity, the start
  of the BTS target range of 88–92 %, and night readings count as low below 88 %.
- **Pregnancy:** skin temperature is still measured, but there are no temperature or combined
  notices, since skin is warmer anyway.
- **Blood oxygen in sleep** can be turned off in the setup (for people the red light bothers).
- **Usual sleep hours** stand in for sleep when the watch has no activity recognition.

## 6. Sync and phone

- **Batches.** `HrBatch` carries `spo2`, `skinTemp` and `vitals` (the limits in use). An older
  phone ignores these fields.
- **Storage.** The phone database (version 7, `MIGRATION_6_7`) keeps them in `spo2_samples` and
  `skin_temp_samples`. "Delete all" removes them too.
- **Blood oxygen screen.** A *Measured by your watch* card:
  - usual awake and asleep;
  - last night (median and lowest);
  - the last 28 nights;
  - the latest readings.
- **Skin temperature screen.** The usual night, last night's change (or *Learning*), and the last
  28 nights.
- **Settings.** *Your limits* adds the oxygen limit, the usual night and the temperature rise.

## 7. Switches

See [HEART_MONITORING.md section 7](HEART_MONITORING.md#7-settings-a-recording-switch-a-monitoring-switch-and-its-parts-monitorsettings).

- **Master and parts.** *Health monitoring* is the master switch. Under it, *Blood oxygen* and
  *Skin temperature* each have their own switch (on by default), next to *Heart*. A wearer who
  wants only the heart turns the two off.
- **Master off.** Background SpO2 and temperature stop.
- **Without all-day heart rate.** The parts keep working, but without sleep detection: readings
  then count as by day, and no night values are made.
- **Confirmation.** Turning either part off asks nothing.

## 8. Simulation (`VitalsSimulationTest`)

**Method.**
- **Duration and pace.** 60 days per wearer through the real `VitalsMonitor`, driven as the worker
  drives it: SpO2 every hour with 15 % of hours missed, re-checks 2 minutes apart, temperature
  every 30 minutes asleep, and the check at 10:00.
- **SpO2 noise:**
  - reading noise with an SD of 1 point;
  - day-to-day drift with an SD of 0.4;
  - nights 0.8 lower;
  - 3 % of readings an artefact 4–8 points low.
- **Temperature noise:**
  - night-to-night SD 0.2 °C;
  - reading noise 0.15 °C;
  - 1 night in 10 under a warmer blanket (+0.4 °C).
- **Pass mark:** every episode caught, at most 1 false notice per wearer, and none in 35 further
  random histories (5 per wearer).

**Results** (standard sensitivity, seed 7):

| Wearer | Episode | Notices | False |
|---|---|---|---|
| Typical adult, 97 %, sensor artefacts only | – | none | 0 |
| Older adult, 93 % | 3 hours around 88 % by day, day 40 | day 40 confirmed low (88 %) | 0 |
| Menstrual cycle, +0.35 °C luteal | – | none | 0 |
| Trip to 2,500 m, days 30–34 | SpO2 −2.5 by day, −3 more in sleep | day 30 confirmed low; days 32 and 35 lower in sleep | 0 |
| Illness, nights 40–42 | +1.2 °C and +9 bpm in sleep | day 40 combined (one night) | 0 |
| Warmer nights only, 40–42 | +1.3 °C | day 41 warmer nights | 0 |
| Breathing trouble in sleep, 45–47 | nights around 91 % | day 46 lower in sleep | 0 |

35 further random histories: **25 of 25 episodes caught, 0 false notices.**

**What the simulation changed:**

| Problem found | Fix |
|---|---|
| Older adult at 93 %: 12 false "confirmed low" notices; 51 across the random histories. Two artefacts in a row, or a reading of exactly 90, were enough | Count only readings strictly *below* the limit; three in a row (two re-checks) |
| "Lower in sleep" fired in the first days, against the population guess | Needs 5 of the wearer's own nights |
| Illness gave a combined notice and then a temperature notice for the same nights | No temperature notice while a combined one is recent |
| Warmer nights missed in one random history. The first warm night entered the baseline before any notice and pushed the SD from 0.26 to 0.40, so the needed rise grew to 1.2 °C | Median and MAD baseline (robust to one or two unusual nights); two-night average with a 70 % floor per night |

The report is written to `shared/build/reports/vitals-simulation.md`.

## 9. Tests

- `VitalsTest`: reading quality; limits per sensitivity; three in a row (one or two never
  notify); the cooldown; population start and fast learning; lower nights (2 of 3; one is not
  enough; once a day after 10:00); the night temperature median without the first hour; the
  cycle not notifying; two warm nights notifying; the combined notice (and none without the heart
  part); the five-night minimum; old JSON and the switches.
- `VitalsSimulationTest`: section 8.
- `HeartRepositoryTest.watchVitalsAreStoredOnceAndDeleted` and `MigrationTest.migrate6To7…`
  (phone).
- Screenshots: the oxygen and temperature screens with the background card, settings with the
  parts and limits, and the watch parts.

## 10. Known limits

- **Hourly SpO2 is not continuous.** It can't find sleep apnoea or short dips. The night notices
  only say the nights were lower than usual.
- **Skin tone.** Wrist oximeters read less accurately on darker skin (Sjoding 2020, FDA 2021).
  The re-checks lower false notices but can't fix the bias.
- **Skin temperature depends on the room and the bedding.** Only the change from the usual night
  means anything, and cooler nights are never notified.
- **Battery and light.** These are not measured yet (device checklist).
- **No validation on real people.** The limits come from the literature and the simulation; real
  wearers' data will tune them.

## References

- Apple Heart & Movement Study: blood oxygen in 33,080 adults (PMC10374661).
  https://pmc.ncbi.nlm.nih.gov/articles/PMC10374661/
- O'Driscoll BR et al. BTS guideline for oxygen use in adults in healthcare and emergency
  settings. *Thorax* 2017.
- Sjoding MW et al. Racial bias in pulse oximetry measurement. *N Engl J Med* 2020.
- U.S. FDA. Pulse oximeter accuracy and limitations: safety communication, 2021.
- Smarr BL et al. Feasibility of continuous fever monitoring using wearable devices. *Sci Rep*
  2020.
- Maijala A et al. Nocturnal finger skin temperature in menstrual cycle tracking. *BMC Womens
  Health* 2019.
- Mishra T et al. Pre-symptomatic detection of COVID-19 from smartwatch data. *Nat Biomed Eng*
  2020.
- Alavi A et al. Real-time alerting system for COVID-19 and other stress events using wearable
  data. *Nat Med* 2022.
- Apple: About wrist temperature on Apple Watch. https://support.apple.com/en-us/102674
