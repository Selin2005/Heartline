# Changelog

All notable changes to Heartline. Each release's notes are written by the Build workflow when the
release is made, and the app shows them under *What's new* after an update.

## 0.0.2.116-beta.3 — 2026-10-08

### Changes
- Blood oxygen by day: wait for a still arm instead of trying and failing
- Blood oxygen tries on the phone: "Today on your watch"
- Blood oxygen every 30 minutes for breathing conditions
- Blood oxygen by day too: retries that reset, low readings only when confirmed
- BackgroundWindow.endReason: wrap the off-wrist rule within the line limit (ktlint)
- No background sensor on the charger; rhythm windows end by their readings, not by timers

## 0.0.2.116-beta.2 — 2026-10-06

### Changes
- Monitoring review: rhythm windows keep the personal limits, ECG notes kept, no sensor clashes
- No false irregular rhythm in a smooth sleeping rhythm; watch readings grouped by stretch

## 0.0.2.116-beta.1 — 2026-10-05

### Changes
- Read background windows by the readings' own time, and show the watch's readings everywhere
- BP algorithm 6.5: five corrections found by the full specification
- Write the complete blood-pressure algorithm specification
- BP algorithm 6.4: measured on real cuff checks, no forearm correction, coupled diastolic

## 0.0.2.115-beta.3 — 2026-10-03

### Changes
- Update downloads that survive leaving the screen and continue where they stopped
- Release notes in the app: hide comments, show quotes and italics
- Fix what a real watch log showed: temperature, window warm-up, alert logs
- No heart-rate notice compared with a usual that doesn't exist yet
- Stress monitoring, monitoring setup with health questions, and no rhythm card on Home
- Background blood oxygen and skin temperature monitoring
- All-day heart rate keeps recording when heart monitoring is off
- Personal heart-rate limits, one heart monitoring switch, and a 60-day simulation
- Heart alerts that know about exercise and sleep, and fewer false rhythm notifications
- Fix the Telegram group link, add the ECG GIF and a Samsung disclaimer
- Documentation, terms and policy updates

## 0.0.2.115-beta.2 — 2026-09-30

### Improved
- Blood pressure estimates now follow changes away from your last calibration and show a more honest ± range.
- Updated documentation, terms, and policy.

### Fixed
- Measurements stopped early or ended by an error no longer lose their data — everything recorded up to that point is saved and sent to your phone, with cuff readings kept alongside their sessions.

## 0.0.2.115-beta.1 — 2026-09-29

### Improved
- Diagnostic logs are now compressed and stored in segments, so weeks of logs fit on your watch and phone without filling them up — your existing logs are kept.
- Updated our documentation, terms, and privacy policy.

### Fixed
- Fixed blood pressure calibration on some watches (like the Galaxy Watch6) where the pulse wave could be read upside down, so you're no longer asked to calibrate again after every measurement.

## 0.0.2.112-beta.1 — 2026-09-29

### New

- ECG recordings with a rhythm result — clear illustrations show where to put your finger, and a built-in second opinion reviews each recording.
- Blood pressure estimates with a ± range, calibrated and refined with your own cuff readings.
- Heart rate, blood oxygen (SpO2) and stress measurements on the watch.
- Heart-rate range charts for a day, week or month.
- Body composition on the watch and phone: fat, muscle and more, with trends, ranges and your body type.
- Set today's weight with the rotating bezel — one click, one step.
- Live animations on every measuring screen, driven by the sensor data.
- A personal watch home screen: a greeting by name, today's check-ins and your metrics in the order you measure them.
- Watch tiles for heart rate, blood pressure, quick measure, wellness and stress, all updating live.
- Watch-face complications for every metric, plus an ECG shortcut.
- Confetti that bursts in from the round edge on your birthday and when all check-ins are done.
- Daily check-ins with a streak, results shown against your usual range, an accent colour, distinct vibrations and a weekly summary.
- Phone home-screen widgets: a health dashboard, one widget per metric and a one-tap measure button.
- Quick Settings tiles that start a measurement on the watch and show the latest reading.
- All settings sync between phone and watch, including heart-rate and irregular-rhythm alerts.
- Export results as an ECG PDF, CSV file or image, with an editable file name and an option to include your name.
- Share exports straight to AI apps — Claude, ChatGPT, Gemini and Grok have their own tiles.
- Export the phone's and watch's logs from Help & diagnostics, with personal details removed.
- In-app updates with a choice of stable, beta or dev channel, and a "What's new" note for each release.
- Links to the source code and the Telegram community.
- A new One UI-style app icon for phone and watch.

### Improved

- Documentation, Terms of Use and Privacy Policy updates.
