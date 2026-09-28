# Changelog

All notable changes to Heartline. Each release's notes are written by the Build workflow when the
release is made, and the app shows them under *What's new* after an update.

## 0.0.2.106-beta.1 — 2026-09-28

### Changes
- Sign dev builds with the release key too
- In-app links to the source code and the Telegram community
- BP algorithm 6.1: fixes from the first real Galaxy Watch8 Classic logs
- Updates by channel: dev gets everything, beta gets betas and releases, stable gets releases
- Build: four-part versions, visible version in the run title, stop on a bad version
- Help & diagnostics: export the phone's and watch's logs
- BP algorithm 6: every watch sensor, state-weighted fusion, full raw session logs
- BP algorithm 5: state-aware estimate that never reads a compensating drop as high
- Google Play readiness: new application ID, Play guide and store listing
- In-app updates from GitHub releases, beta opt-in and What's new
- Terms of Use, Privacy Policy and an in-app acceptance gate
- License Heartline under AGPL-3.0 with a CLA and a trademark policy
- Remove device logs, the bundled Samsung SDK package and internal planning docs
- Watch: Heartline cards for the tile stack (small and large), no buttons
- Build: compile and target SDK 37 with AGP 9.1.1 and Gradle 9.3.1
- Daily goal and streak, your usual ranges, accent colour, distinct vibrations, weekly summary
- Watch: effects that come in from the round edge
- A more personal Heartline: greetings by name and a new watch launcher
- Watch tiles and complications in the Samsung Health style
- Phone: Quick Settings tiles that start a measurement on the watch
- Phone widgets: Samsung Health–style redesign, health tiles and a measure button
- Watch tiles: fix the broken tile-picker previews
- Back after a widget, tile or complication returns to where the user came from
- Watch: set today's weight with the rotating bezel
- Body composition on the phone: one long animated page with trend, ranges and body type
- Body composition on the watch: all values, today's weight, scrolling animated result
- Watch: ECG illustration watch has one face, not a bezel ring plus a screen
- Watch: ECG instruction illustration back to the realistic style, with more detail
- Watch: friendlier flat ECG instruction illustration
- Watch: clear ECG instruction illustration (arm, watch, finger on the top key)
- Fix body composition never finishing; ECG contact rule back to "5 = no contact"; raw sensor logs
- Fix ECG never starting with a finger on the key; precise body-composition key hints
- ECG: bundle ECGFounder second opinion, train on AFDB and CPSC 2021
- ECGFounder evaluated on all data: not bundled
- ECG phase 4-5: ECGFounder second opinion on the phone, docs and checks
- ECG algorithm 3: no start without a finger, abnormal rhythms are no longer "noise"
- Blood pressure algorithm 3/4: show real changes, learn from cuff checks, on-phone model
- Watch-face complications for every metric, plus an ECG shortcut; widget docs
- Watch tiles: redesigned Heart and BP, new Quick measure, Wellness and Stress; live updates
- Phone home-screen widgets (Glance): dashboard, heart rate, ECG, BP, stress, quick measure, day chart
- Documentation, terms and policy updates
