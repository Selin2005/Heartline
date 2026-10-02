# Installing and testing on a device

This guide covers installing Heartline from GitHub Releases or CI builds, getting the Samsung
sensors to work, and collecting the logs we need for bug reports.

## 1. Requirements
- A **Galaxy Watch4 or newer** running Wear OS Powered by Samsung (One UI Watch), paired with an
  **Android phone** (Android 8.0 or newer) through the Galaxy Wearable app.
- `adb` on a computer ([Android platform tools](https://developer.android.com/tools/releases/platform-tools)).

## 2. Install
1. Download **both** APKs of the same version from
   [Releases](https://github.com/selin2005/heartline/releases): `Heartline-phone-<version>.apk` and
   `Heartline-watch-<version>.apk`. The two apps only talk to each other when they come from the
   same release, because they must share the signing key.
2. **Phone:** open the APK on the phone (allow "Install unknown apps" for your browser or file
   manager), or run `adb install -r Heartline-phone-<version>.apk`.
3. **Watch:** turn on Settings → Developer options → **ADB debugging** and **Wireless debugging**
   on the watch, then:
   ```bash
   adb pair <ip>:<pairing-port>     # use the code shown on the watch
   adb connect <ip>:<port>
   adb -s <ip>:<port> install -r Heartline-watch-<version>.apk
   ```
4. **Samsung sensor access:** until Heartline is registered as a Samsung Health partner, the
   sensors only work in *developer mode*. On the watch, open Settings → Apps → **Health Sensor
   Service** (called *Health Platform* on older watches), tap the title about 10 times, then turn
   on **Developer mode**. The phone app has a step-by-step guide under Help.

After that, the **phone app** keeps both apps up to date: it tells you when a new release is out
and installs the phone update itself. For the watch it shows the download link and these steps.

> If you installed an earlier build that used the application ID `com.heartline.app`, uninstall it
> from both devices first. Newer builds use `io.github.selin2005.heartline`.
>
> Dev builds up to 0.0.2.104 were signed with a test key. If Android says the update conflicts
> with the installed app, uninstall that one build once on both devices; every build after it
> installs over the previous one.

### CI builds
Every run of the **Build** workflow (GitHub → Actions → Build) keeps its APKs as downloadable
artifacts, even when no release is published. Dev, beta and stable builds are the same app
(only the version differs), so a dev build installs over a beta and the other way round. A local
phone build made with `-Pheartline.demoData=true` fills the app with sample data on first launch, so the UI can be
explored without a watch. Remove it with Settings → Delete all data.

## 3. Collect logs
**From the app (everyone):** Settings → Help & diagnostics → **Export logs**. Pick a folder and
the phone saves one file, `heartline-logs-….zip`. Attach it to your bug report. For problems that
happened earlier, *Keep diagnostic logs* must be on (it is by default in beta versions).

| In the zip | Contents |
|---|---|
| `phone.log`, `watch.log` | The whole text log of each app, oldest first, and its process logcat |
| `sessions/<time>-<kind>-<id>/` | Every sensor value of one measurement (`ecg`, `bp`, `spo2`, `skin_temp`, `bia`, `stress`, `heart_rate`, …), a background window (`irn_window`) or background values (`other`, every 10 minutes): `header.json` (device, versions, events, results, every intermediate value) and one CSV per sensor stream, one row per sample with its timestamp |
| `records/` | `records.csv` (every saved measurement and its result) and each one's stored wave |
| `bp/` | `calibrations.json` (every blood-pressure calibration: each round's cuff reading, features and `sessionId`) and `cuff-checks.csv` (each cuff check: watch vs cuff, with the reading's `sessionId`). A `bp` session's `header.json` also carries its own cuff reading (`cuffSystolic` / `cuffDiastolic`), and a session left half-way is kept with `result` = `cancelled` or `error: …` |

In a session, `ECG_ON_DEMAND.csv`, `PPG_ON_DEMAND.csv`, `HEART_RATE_CONTINUOUS.csv`,
`SPO2_ON_DEMAND.csv`, … hold every value the Samsung tracker gave (all its keys: values, statuses,
LEAD_OFF, sequence, …); `HEART_RATE_CONTINUOUS.IBI_LIST.csv` the beat-to-beat intervals;
`android.*.csv` the Android sensors (accelerometer, gyroscope, rotation); `healthServices.*.csv`
the background heart rate. Blood-pressure sessions come from the BP flow's own complete log.

**Storage:** the text log is kept compressed in 1 MB segments (20 MB on the watch, 150 MB on the
phone) and the watch's raw sessions take up to 30 MB. The watch moves every finished segment and
session to the phone in the background and deletes it once the phone confirms it has it, so the
watch keeps room; the phone keeps up to 300 MB of them. When the phone is away the files wait on
the watch. *Export logs* then only fetches what's still on the watch, one piece at a time with
progress; a piece that stalls is asked for again, and one that still doesn't arrive is marked.

**With adb (developers):**
```bash
tools/device/collect-logs.sh live      # start before testing, Ctrl+C when done
tools/device/collect-logs.sh dump      # or dump afterwards
```
The logs are saved in `logs/<timestamp>/` (ignored by git): `phone-heartline.log`,
`watch-heartline.log` and the full logcat of each device. **Review them before sharing: they can
contain personal and health data.** All app logs use `Heartline/*` tags:

| Tag | Contents |
|---|---|
| `Heartline/Sync`, `Heartline/Link` | Data Layer: finding the other device, hello, status, sending and receiving |
| `Heartline/Setup` | Watch setup gate steps |
| `Heartline/Sensor` | Health Sensor Service connection, capability probe, developer mode |
| `Heartline/ECG` | Summary of each ECG: usable time, noise, amplitude, quality, reason for "poor", pulse arrival time |
| `Heartline/BP` | PPG features, polarity and the estimate |
| `Heartline/Monitor` | Background monitoring (passive heart rate, irregular rhythm windows) |
| `Heartline/Update` | Update checks and downloads (phone) |

Raw sensor values, in logcat and in the exported log:

| Tag | Contents |
|---|---|
| `Heartline/EcgRaw` | ECG tracker start; the first 20 batches in full, and every batch where contact changes (lead-off per sample, mV range, SDK thresholds, sequence, PPG); per-second summaries |
| `Heartline/EcgRec` | Recording phases (waiting, arming, recording, paused) and the signal check result every second while arming |
| `Heartline/BiaRaw` | The profile sent to the sensor and every value of each body composition data point |
| `Heartline/QuickRaw` | Raw SpO₂ and skin temperature values |

To capture them, enlarge the buffer first: `adb logcat -G 16M && adb logcat -c`, then
`adb logcat -v time -s Heartline/EcgRaw Heartline/EcgRec Heartline/BiaRaw Heartline/QuickRaw Heartline/Sensor > heartline.log`.

## 4. Test checklist

### Quick check for every build (5 minutes)
The build workflow already checks the APKs themselves (`tools/ci/check-apks.py`). On the devices:
1. **Install** the phone and watch APKs of the same version over the installed ones (no
   uninstall). Both show that version in Settings → About.
2. **Connection:** open Heartline on the watch; within a few seconds the phone's home screen shows
   the watch as connected, and the watch leaves *Connecting to phone*.
3. **ECG:** record one on the watch; it appears on the phone with its result and the second
   opinion.
4. **Blood pressure:** one quick measurement gives a number on the watch and on the phone.
5. **Updates:** Settings → Updates → *Check now* answers (up to date, or offers the newer build).

Something fails? Export the logs (section 3) from the phone, which includes the watch's, and
attach them to a bug report.

### Full checklist
1. **Terms and onboarding:** the app asks you to accept the Terms of Use and Privacy Policy
   before anything else. Enter an invalid value on purpose (for example a height of 40 cm); the
   error must appear under that field.
2. **Connect the watch:** the "Connect your watch" step shows the watch state. Open Heartline on
   the watch: *Connecting to phone* → *Finish setup on phone* (if the profile or terms are
   missing) → permissions → sensor check → launcher.
3. **Developer mode off:** the watch shows the step-by-step guide and a *Check again* button.
4. **Measure from the phone:** *Measure on watch* for ECG, blood pressure, heart rate, SpO₂,
   temperature, body composition and stress opens that screen directly on the watch.
5. **Blood pressure calibration:** start it on the phone. The watch opens the calibration screen
   once and runs each round itself. Without a calibration, the watch sends you to the phone.
6. **ECG:** record three times. A poor recording explains why, on the watch and in *Recording
   details* on the phone, with the noisy seconds shaded. Keep your hand away from the key for a
   minute: the countdown must not start. Lift the finger for a second mid-recording: it pauses and
   resumes.
7. **Settings sync:** change an option on the watch and check the phone, and the other way round.
8. **No permanent notification:** without background sensor permission, a short notification
   only appears during rhythm checks (about one minute every 15 minutes).
9. **Sharing:** *Share PDF* on an ECG lets you edit the file name; AI app buttons only appear for
   installed apps that accept shares.
10. **Phone widgets:** add Heartline widgets, resize them, stack two 2×2 widgets, change the widget
    style, and check that *Measure* buttons open the watch screen and that widgets refresh within
    seconds after a measurement.
11. **Watch tiles and complications:** add Heartline cards (small or large on One UI 9 Watch /
    Wear OS 7, full-screen tiles on older versions) and complications. Tapping a card opens its
    screen; Back returns to the tiles. Without a Wear OS 7 watch, an emulator can add a card with
    `adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component io.github.selin2005.heartline/com.heartline.wear.tile.HeartTileService --ei type 2`
    (type 2 = small, 1 = large, 0 = full screen).
12. **Blood pressure beyond calibration:** after calibrating, measure at rest, then right after
    climbing stairs: the second reading shows a number with *Beyond your calibration range* and
    *Measure again*. Moving your arm shows *Keep your arm still*.
13. **Body composition:** if the watch can't detect your fingers, try Samsung Health's own body
    composition first. Heartline tells you which key doesn't sense a finger (upper, lower or both).
14. **Updates:** Settings → Updates → *Check now* finds the newest stable release; with the update
    channel set to Beta it also offers betas, and on Development every new build. The *Update
    channel* setting is shown only in beta and dev builds. After updating, *What's new* shows the changelog.
15. **Heart notifications and activity.** Capture `adb logcat -v time -s Heartline/Monitor`.
    - **Activity recognition:** start a workout in Samsung Health; within a few minutes the log
      shows `activity: EXERCISE`, and on the phone the heart-rate chart offers an *Exercise*
      filter. After a night's sleep it shows `activity: ASLEEP` and *Today by activity* shows sleep.
    - **No alerts during normal exercise:** a workout at a high but normal rate gives no high heart
      rate notification. Settings → *Very high heart rate during exercise* set to a low limit (for
      example 140 bpm) gives one after about 3 minutes above it.
    - **Watch not worn:** leave the watch on a table or charger for two hours. The log shows
      `IRN window skipped` (no background heart rate, or off the wrist), never an irregular rhythm
      notification.
    - **Rhythm windows:** while wearing it and sitting still, `IRN window done` lines show
      `irregular=false` (or a `skipped=` reason for moving or weak signal).
16. **Blood pressure sensors (algorithm 6).** Every session writes a raw log. Capture
    `adb logcat -v time -s Heartline/BP Heartline/BpRaw Heartline/Sensor` and export
    Phone → Blood pressure → Share → **BP raw sessions (zip)**, or use Settings → Help & diagnostics →
    **Export logs**, which has every raw sensor value of every session plus the calibrations and cuff
    checks (`bp/`).
    Then check each item below and report what you see, so the algorithm can be tuned on real data:
    - **IR and red PPG:** the `Heartline/Sensor` line `PPG channels:` lists GREEN, IR and RED, and
      the `ppg` stream in the log has finite `ir` / `red` values. If only GREEN is listed, the
      watch doesn't offer them to third-party apps.
    - **Accelerometer rate:** `Heartline/BpRaw` prints `accel=N@RHz`. The ballistocardiogram needs
      R ≥ 100 Hz (at least 80). Note the value for your watch model.
    - **Ballistocardiogram:** after a quick measurement at rest (arm on a table), the values
      `bcg.quality` (≥ 0.5 is usable) and `bcg.pttMs` (typically 80–250 ms) are in the log.
    - **PPG inside ECG:** on a Galaxy Watch8 Classic it carries a value in only 1 of 5 samples (the
      rest −1) and jumps level when the sensor changes gain; Heartline repairs it (`PpgRepair`).
      Calibration always runs in quick mode (no ECG); precise mode is offered only once precise
      rounds exist, and its values are `precise.patMs` / `precise.pepMs`.
    - **Arm raise:** the `hydro.sign` value is 1 or −1, `hydro.maxOffset` is about 40 mmHg with the
      arms fully raised, and `hydro.patSlope` is negative (−0.3 to −2 mmHg/ms).
    - **Clocks:** PPG timestamps (SDK) and accelerometer timestamps (Android sensor clock, moved to
      wall-clock) must line up within a few ms; the transit channels depend on it.
    - **Skin temperature / EDA:** `skinTemp=true` (Watch5+) and `eda=true` (Watch8+) in the
      capabilities; the preparation step before the recording lasts about 10 s.
    - **Bathroom scenario (carefully, sitting):** after standing up quickly, a quick measurement
      shows a number with the note *Fast pulse: weighted towards transit time*, never a much
      higher reading than a cuff taken right after.

    Send what you found with the
    [Device report](https://github.com/selin2005/heartline/issues/new?template=device_report.yml)
    form, so your watch gets a row in [TESTED_DEVICES.md](TESTED_DEVICES.md).
