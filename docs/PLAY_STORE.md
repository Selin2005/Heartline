# Publishing on Google Play

Heartline is prepared for Google Play: the application ID is `io.github.selin2005.heartline`, the
Build workflow produces **Play bundles** (`Heartline-phone-X.aab` and `Heartline-watch-X.aab`,
run artifacts of stable and beta builds), and the `play` build type leaves out the GitHub
updater together with the `INTERNET` and `REQUEST_INSTALL_PACKAGES` permissions (Play doesn't
allow apps to update themselves). The store texts are in
[`fastlane/metadata/android/en-US`](../fastlane/metadata/android/en-US).

## ⚠️ Blockers to clear first
1. **Samsung Health partner approval.** Without it the watch sensors only work with developer
   mode on, so Play reviewers and ordinary users can't measure anything. Apply through
   [Samsung's partner process](https://developer.samsung.com/health/sensor/process.html) with
   the application ID and the SHA-256 of the **app signing key** Play uses (Play Console → Setup →
   App signing).
2. **Health claims.** Google Play treats apps that claim to detect or diagnose conditions (for
   example "detects AFib") as medical devices that need regulatory clearance. Keep the listing,
   screenshots and in-app text at the wellness level: *records*, *estimates*, *signs of*, *not a
   medical device*. Never *diagnose*, *detect disease* or *medical-grade*.
3. **Samsung SDK license.** Check that the Samsung Health Partner Service & SDK License allows
   distribution through Google Play for your account.

## Play Console setup
1. Create the app: name **Heartline**, default language English (US), app, free.
2. **App signing:** choose *Use Play App Signing* and upload the same release key the GitHub
   builds use (*Export and upload a key from Java keystore*). Then GitHub and Play builds are
   update-compatible, and the key registered with Samsung matches both.
3. **Wear OS:** Setup → Advanced settings → Form factors → add *Wear OS*, and upload the watch
   bundle to the Wear OS track. Opt in to Wear OS review. The watch app declares itself
   **not standalone** (`com.google.android.wearable.standalone = false`), because setup needs the
   phone app.
4. Upload both bundles from the Build run's `Heartline-play-<version>` artifact, first to
   **Internal testing**, then Closed testing (Play requires a closed test with testers for new
   personal developer accounts before production).

## App content declarations

| Declaration | Answer |
|---|---|
| Privacy policy | `https://github.com/selin2005/heartline/blob/main/legal/PRIVACY_POLICY.md` (or the GitHub Pages copy) |
| Ads | No ads |
| App access | All functions need a Galaxy Watch4 or newer with Heartline installed; describe developer mode until partner approval |
| Content rating | IARC questionnaire: health / reference, no user-generated content |
| Target audience | 18 and over |
| Health apps | Category *Health and fitness* / *wellness*; not a medical device; no regulatory clearance claimed |
| Health permissions | Body sensors and health data: taking measurements the user starts. Background health data (`BODY_SENSORS_BACKGROUND`, `READ_HEALTH_DATA_IN_BACKGROUND`): optional irregular rhythm and heart rate alerts. Record a short video of the feature for the review. |
| Foreground service | Type `health`: the one-minute background rhythm check on the watch |
| Exact alarms, SMS, call log, location | Not used |

### Data safety form
- **Does the app collect or share user data?** No. All data is processed on the device and never
  sent to the developer. (Sharing exports to other apps is started by the user and is not
  "sharing" under Play's definition.)
- **Is data encrypted in transit?** Not applicable (no transmission to the developer).
- **Can users request deletion?** Yes: Settings → Delete all data, or uninstall.
- App activity / diagnostics (*Crash logs*, *Diagnostics*): kept on the device only when the user
  agrees (or runs a beta), and shared only when the user exports the files themselves, so not
  "collected".
- Data types *processed on device only*: Health and fitness (heart rate, ECG, blood pressure
  estimates, SpO₂, skin temperature, body composition), personal info (name, birth date, sex,
  height, weight).

## Store listing
- Short and full description, title: `fastlane/metadata/android/en-US/`.
- App icon 512 × 512 and feature graphic 1024 × 500: `docs/brand/heartline-icon-512.png` and
  `docs/brand/play-feature-graphic.png` ([docs/brand](brand/README.md)).
- Phone screenshots (at least 2, 16:9 or 9:16): from `docs/screenshots/phone/`.
- Wear OS screenshots (at least 1, 384 × 384 or larger, round content on a square canvas, no
  device frame): from `docs/screenshots/wear/` (the large-watch images).
- Category: Health & Fitness. Contact email: required by Play.

## Every release
1. Run **Build** with channel *beta* or *stable* (see [RELEASING.md](RELEASING.md)).
2. Download the `Heartline-play-<version>` artifact and upload both `.aab` files to the matching
   Play track (beta → Closed testing, stable → Production).
3. Paste the release notes from the GitHub release (Play allows 500 characters per language).
