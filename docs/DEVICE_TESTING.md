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

### CI builds
Every run of the **Build** workflow (GitHub → Actions → Build) keeps its APKs as downloadable
artifacts, even when no release is published. A debug phone build made with
`-Pheartline.demoData=true` fills the app with sample data on first launch, so the UI can be
explored without a watch. Remove it with Settings → Delete all data.

## 3. Collect logs
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

Debug builds also log raw sensor values:

| Tag | Contents |
|---|---|
| `Heartline/EcgRaw` | ECG tracker start; the first 20 batches in full, and every batch where contact changes (lead-off per sample, mV range, SDK thresholds, sequence, PPG); per-second summaries |
| `Heartline/EcgRec` | Recording phases (waiting, arming, recording, paused) and the signal check result every second while arming |
| `Heartline/BiaRaw` | The profile sent to the sensor and every value of each body composition data point |
| `Heartline/QuickRaw` | Raw SpO₂ and skin temperature values |

To capture them, enlarge the buffer first: `adb logcat -G 16M && adb logcat -c`, then
`adb logcat -v time -s Heartline/EcgRaw Heartline/EcgRec Heartline/BiaRaw Heartline/QuickRaw Heartline/Sensor > heartline.log`.

## 4. Test checklist
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
14. **Updates:** Settings → Updates → *Check now* finds the newest stable release; with *Receive
    beta versions* on, it also offers betas. After updating, *What's new* shows the changelog.
