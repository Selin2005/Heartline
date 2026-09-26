# Watch ↔ phone sync protocol (v1)

Implementation: `shared/src/main/kotlin/com/heartline/shared/sync/` (`Protocol`, `SyncEngines`,
`WaveCodec`, `Messages`). The device transport is `datalayer/DataLayerTransport` (MessageClient +
ChannelClient).

Nodes find each other through the capabilities `heartline_phone` / `heartline_watch`
(`res/values/wear.xml`). The Wearable Data Layer only connects apps with the **same application
ID and the same signing key**, so the phone and watch apps are always built and signed together.

| Path | Direction | Channel | Payload (JSON unless noted) | Response |
|---|---|---|---|---|
| `/hl/v1/hello` | W→P | Message | `Hello{protocol, appVersion, capabilities, sensorServiceVersion, role, deviceName, settings}` | Phone replies with `status`, then sends `settings`, `bp/calibration` and `profile` |
| `/hl/v1/status` | P→W | Message | `PhoneStatus{protocol, appVersion, onboarded, termsAccepted, profileComplete, displayName, calibration, …}` | Watch gates its features on it |
| `/hl/v1/setup-request` | W→P | Message | `SetupRequest{target}` (`HOME`, `PROFILE`, `BP_CALIBRATION`, `DEV_MODE_HELP`, `TERMS`) | Phone opens that screen |
| `/hl/v1/record/meta` | W→P | Message | `RecordMeta{id, kind, startedAtMs, durationMs, sampleRateHz, sampleCount, summary}` | `record/ack` once stored |
| `/hl/v1/record/wave/<id>` | W→P | Channel | Binary `HLW1`: 16-byte header (magic, kind, rate, count) + Float32LE samples | Merged with the meta |
| `/hl/v1/record/ack` | P→W | Message | `Ack{id, ok}` | Watch removes it from its outbox |
| `/hl/v1/hr/batch` | W→P | Message | `HrBatch{id, minutes[HrMinute]}` (every 15 minutes) | `ack` |
| `/hl/v1/alert` | W→P | Message | `HealthAlert{id, kind(IRREGULAR_RHYTHM/HIGH/LOW), atMs, bpm, windowStartsMs}` | `ack` |
| `/hl/v1/bp/calib-capture` | P→W | Message | `CaptureRequest{captureId, round}` | Watch opens the calibration round |
| `/hl/v1/bp/calib-capture` | W→P | Message | `CaptureResult{id, captureId, round, features}` | `ack` |
| `/hl/v1/bp/calibration` | P→W | Message | `BpCalibration`, or `null` to delete it | — |
| `/hl/v1/settings` | P→W | Message | `MonitorSettings{…}` | Watch turns background monitoring on or off |
| `/hl/v1/profile` | P→W | Message | `UserProfile{birthYear, sex, heightCm, weightKg, …}` | — |
| `/hl/v1/delete` | P→W | Message | `DeleteRecord{id}` | — |
| `/hl/v1/open` | P→W | Message | Screen name (`ecg`, `blood_pressure`, …) as plain text | Watch opens that screen |

## Rules
- **Idempotent:** IDs are UUIDs. The phone never stores a record twice, but acknowledges it again.
- **Ordering:** meta and waveform may arrive in any order. The record is stored once both have
  arrived (or the meta alone when `sampleCount = 0`).
- **Durability on the watch:** records and messages stay in Room (`records`, `messages`) until
  acknowledged. `SyncWorker` retries with exponential backoff; messages older than 7 days are
  dropped.
- **Robustness:** a corrupt or unknown message is discarded (fuzz test: `RobustnessTest`).
- **Compatibility:** `Hello.protocol` must be `1`; unknown fields are ignored (`ignoreUnknownKeys`).
