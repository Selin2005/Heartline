# Heartline Privacy Policy

**Version 1 · Effective 26 September 2026**

Heartline is built so that **your health data stays on your devices**. This policy explains what
Heartline processes, where it is kept, and your choices. It applies to the Heartline phone and
watch apps published at https://github.com/selin2005/heartline and, when available, on Google
Play.

## Summary
- We **don't run any server** for Heartline. We don't have an account system, and we **never
  receive** your health data.
- Heartline has **no analytics, no advertising, no tracking and no crash reporting SDKs**.
- Your data leaves your devices **only when you choose** to export or share it.
- The only network request Heartline makes itself is the **update check** in the GitHub version
  (not in the Google Play version), and it sends no personal data.

## 1. Data Heartline processes
| Data | Why | Where it is stored |
|---|---|---|
| **Profile:** name or nickname, birth date, sex, height, weight, preferences | Greetings, age- and sex-dependent calculations (body composition, heart rate ranges), names on reports | Phone, and a copy on the paired watch |
| **Sensor readings:** ECG waveforms and results, PPG-based blood pressure estimates and features, heart rate, inter-beat intervals and HRV, blood oxygen, skin temperature, stress, body composition, motion used to check you are still | The features of the app | Watch until synced, then the phone |
| **Your entries:** cuff readings for calibration, symptoms, notes | Calibration and your history | Phone (calibration also on the watch) |
| **Settings:** alerts, thresholds, goals, update channel | App behaviour | Phone and watch |
| **Diagnostic logs** (if on, see section 3) | Fixing problems you report | Phone and watch, until exported or deleted |

Heartline reads sensors through the Samsung Health Sensor SDK on the watch. Heartline does not
read from or write to Samsung Health, Google Fit or Health Connect.

## 2. How the data moves
- **Watch ↔ phone:** records are sent directly between your paired devices through Google's
  Wearable Data Layer (Google Play services). They are not sent to us.
- **Backups:** Heartline opts out of Android cloud backup, so its data is not copied to your
  Google account. The flip side: if you lose or reset a device, the data on it is gone unless you
  exported it.
- **Exports and sharing:** PDF reports, images, CSV and JSON exports are created only when you
  tap *Share* or *Export*, and go only to the app or person you pick. The optional *Share with
  AI* feature hands a summary to an AI app installed on your phone, only after you confirm, and
  under that app's privacy policy.

## 3. Diagnostic logs
To help fix problems, the apps can keep a **diagnostic log** on each device: what the app did,
errors, connection and sensor states, measurement values (for example ECG quality or blood
pressure features) and the raw sensor values of each measurement. Your name and birth date are
removed before anything is written.
- It is **on by default for beta versions** and **off for stable versions** until you agree when
  the app asks. Change it any time in Settings → Help & diagnostics.
- It is kept compressed and uses at most 150 MB on the phone and 50 MB on the watch (less when
  the device is low on space); older entries are removed first.
- Logs are **never sent automatically**. *Export logs* saves one zip file with the phone's and
  the watch's logs into a folder you choose; you decide whether to send it to us, for example
  with a bug report.
- Turning diagnostic logs off, or *Delete logs*, erases them on both devices.

## 4. Network access
- **Update check (GitHub version only):** once a day, and when you tap *Check now*, the phone app
  asks `api.github.com` for the list of Heartline releases and may download an APK from
  `github.com`. Like every web request, this reveals your IP address and basic device information
  (user agent) to GitHub, under [GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
  No health data or identifiers are sent. You can turn automatic checks off in Settings → Updates.
- **Links** you open (for example to the source code or to this policy) open in your browser.
- The **Google Play version** doesn't make any network request of its own; Google Play handles
  updates.

## 5. Permissions
| Permission | Used for |
|---|---|
| Body sensors / health data (heart rate, SpO₂, skin temperature, additional Samsung health data) | Taking the measurements you start, and background heart rate for rhythm alerts if you turn them on |
| Background health data | Irregular rhythm and high / low heart rate alerts while you're not using the app |
| Physical activity | Checking you are still during a measurement |
| Notifications | Alerts, reminders and update notices |
| Internet, install apps (GitHub version only) | Checking for, downloading and installing updates |

You can revoke any permission in the system settings. The related features stop working.

## 6. Retention and deletion
Your data is kept on your devices until you delete it. The watch only keeps measurements until
they have reached the phone. **Settings → Delete all data** on the phone removes all your
measurements, and you can delete single records at any time. Uninstalling Heartline removes
everything it stored on that device, including your profile and settings.
We can't delete data you have exported or shared, because we never had it.

## 7. Children
Heartline is intended for adults (18+) and is not directed at children.

## 8. Security
Data is stored in the app's private storage, protected by Android's app sandbox and your device
lock. Updates downloaded from GitHub are checked against published SHA-256 checksums before
installation. Please report vulnerabilities as described in [SECURITY.md](../SECURITY.md).

## 9. Your rights
Because all data is under your control on your devices, you can access, export, correct and
delete it yourself at any time from the app. If you have a question or request, contact us below.

## 10. Changes
We'll update this policy when Heartline's data practices change. The version number changes with
every update, and the app asks you to accept the new version.

## 11. Contact
Open an issue at https://github.com/selin2005/heartline/issues. For anything sensitive, use
[private reporting](https://github.com/selin2005/heartline/security/advisories/new).
