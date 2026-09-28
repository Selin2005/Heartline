# Changelog

All notable changes to Heartline. Each release's notes are written by the Build workflow when the
release is made, and the app shows them under *What's new* after an update.

## 0.0.2.106-beta.1 — 2026-09-28

### Changes
- Telegram announcement: only after a successful build, retried, never silent
- Announce stable and beta releases on Telegram
- gitignore: base64 copies of signing keys
- Sign dev builds with the release key too
- In-app links to the source code and the Telegram community
- Link the Heartline community on Telegram
- App icon PNGs and a README poster in docs/brand
- Issues: labels workflow, device details required in bug reports
- README and SDK notes: developer mode is in Health Platform
- Tested devices: Health Platform 1.7.00.05 on the Galaxy Watch8 Classic
- Tested devices: complete the Galaxy Watch8 Classic row
- CLAUDE.md: ask where changes go at the start of each conversation
- CLAUDE.md: coding agents work directly on main
- Tested devices: the sensor details table is optional
- Tested devices list and a Device report form
- Docs and tools: README for tools/ecg-eval, drop unreferenced in-sample reports
- Keep AI attribution out of the history
- BP algorithm 6.1: fixes from the first real Galaxy Watch8 Classic logs
- Updates by channel: dev gets everything, beta gets betas and releases, stable gets releases
- Build: four-part versions, visible version in the run title, stop on a bad version
- Help & diagnostics: export the phone's and watch's logs
- BP algorithm 6: every watch sensor, state-weighted fusion, full raw session logs
- Build workflow: choose the branch to build
- BP algorithm 5: state-aware estimate that never reads a compensating drop as high
- README: rewritten in English for the open-source release
- Google Play readiness: new application ID, Play guide and store listing
- In-app updates from GitHub releases, beta opt-in and What's new
- Build workflow: stable, beta and dev channels with AI-written release notes
- Terms of Use, Privacy Policy and an in-app acceptance gate
- Screenshots: one folder and README per app section, losslessly optimised
- Docs: English rewrite of the design, protocol, device testing, SDK and cloud notes
- License Heartline under AGPL-3.0 with a CLA and a trademark policy
- Remove device logs, the bundled Samsung SDK package and internal planning docs
- Docs: Heartline cards in the watch's tile stack
- Watch: Heartline cards for the tile stack (small and large), no buttons
- Build: compile and target SDK 37 with AGP 9.1.1 and Gradle 9.3.1
- Daily goal and streak, your usual ranges, accent colour, distinct vibrations, weekly summary
- Watch: effects that come in from the round edge
- A more personal Heartline: greetings by name and a new watch launcher
- Watch tiles and complications in the Samsung Health style
