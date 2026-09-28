# Tested devices

The watches and phones Heartline has been tested on, and what worked on each. The list grows with
every report: send yours with the
[Device report](https://github.com/selin2005/heartline/issues/new?template=device_report.yml) form.

A device that isn't listed hasn't been tried yet. That doesn't mean Heartline doesn't work on it.
Any **Galaxy Watch4 or newer** should work, but sensors differ between models.

✅ works · ⚠️ works with limits (see notes) · ❌ doesn't work · — not tried yet · n/a the watch has no such sensor

## Watches

| Watch | One UI Watch | Wear OS | Health Sensor Service | Heartline | ECG | BP quick | BP precise | Heart rate / HRV (background) | SpO₂ | Skin temp. | Stress / EDA | Body composition | Tiles / cards | Tested | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Galaxy Watch8 Classic | One UI 8 Watch | Wear OS 6 | 1.6.5 or newer | 0.0.2.104-dev.39 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 2026-09 | Latest stable software (One UI 9 Watch is still in beta). All features tested and working. ECG contact only works with the "no contact = 5" `LEAD_OFF` rule. Precise BP: the PPG inside ECG has a value in only 1 of 5 samples (repaired by `PpgRepair`). |

## Phones

| Phone | One UI | Android | Heartline | Install | Sync with watch | Widgets | PDF / CSV export | Log export | In-app update | Tested | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|
| *(to be added)* | | | | | | | | | | | |

For phones that aren't Samsung, write "—" under One UI.

## Sensor details per watch

Optional. Heartline doesn't need these values to support a watch: it has no per-model settings and
detects everything while it runs (which sensors exist, the PPG channels, the accelerometer rate,
gaps in the PPG, the BIA progress scale). Blood pressure accuracy comes from each user's own cuff
calibration, and *Accuracy check* shows how close the watch is to that user's cuff.

The table records what [DEVICE_TESTING.md](DEVICE_TESTING.md) (checklist item 15) finds in the logs,
so a new model's quirks are spotted early, such as the PPG inside ECG on the Watch8 Classic, which
has a value in only 1 of 5 samples.

| Watch | PPG channels (quick) | Accelerometer rate | PPG inside ECG | Skin temp. | EDA | BIA progress scale | BCG quality at rest |
|---|---|---|---|---|---|---|---|
| Galaxy Watch8 Classic | green, IR, red | ~100 Hz | 1 of 5 samples, jumps on gain changes | yes | yes | 0–1 | 0.61–0.68 |

## Adding a device
- **Testers:** fill in the
  [Device report](https://github.com/selin2005/heartline/issues/new?template=device_report.yml)
  form. Don't attach real health data.
- **Maintainers:** copy the report into the tables above, one row per watch or phone and
  software version, and close the issue with a link to the commit. When a newer Heartline or
  One UI version is tested on the same device, update the row.
