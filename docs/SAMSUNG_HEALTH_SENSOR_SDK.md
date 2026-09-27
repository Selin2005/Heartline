# Samsung Health Sensor SDK: how Heartline uses it

Heartline reads the Galaxy Watch's health sensors through the
[Samsung Health Sensor SDK](https://developer.samsung.com/health/sensor/overview.html) **1.4.1**
(`wear/libs/samsung-health-sensor-api.aar`). These are our own integration notes. The official
documentation, API reference and license are on Samsung's developer site (links at the end).

## How it works
```
Heartline (Wear OS app on the watch)
   │  samsung-health-sensor-api.aar
   ▼
HealthTrackingService ── bind ──► Health Sensor Service (system app on the watch)
                                        │
                                        ▼
                               BioActive sensors (ECG, PPG, BIA, EDA …)
```
- Works on **Galaxy Watch4 and newer** only, not on phones or emulators. Heartline's fake sensor
  build (`-Pheartline.fakeSensors=true`) replaces the SDK for UI work.
- The data is for **wellness only**, not for diagnosis. Samsung's license requires this, and so
  does Heartline.
- SDK 1.4.x needs Health Sensor Service 1.6.5 or newer. An older service raises
  `OLD_PLATFORM_VERSION`, which `HealthTrackerException.resolve(activity)` fixes by opening the
  store.

## Partner access and developer mode
Health Sensor Service checks the package name and signing certificate of every app:
- **Registered partners** work on every watch. Registration is requested through
  [Samsung's partner process](https://developer.samsung.com/health/sensor/process.html) and ties
  the approval to the application ID and the SHA-256 of the release signing key.
- **Everyone else** only works while **developer mode** is on (Settings → Apps → Health Sensor
  Service → tap the title about 10 times → Developer mode). Otherwise every tracker fails with
  `SDK_POLICY_ERROR`, which Heartline shows as a guide (`SensorErrorScreen`, phone *Help*).

Until Heartline is approved as a partner, users have to turn on developer mode. Partner approval
is also a prerequisite for the Google Play release (see [PLAY_STORE.md](PLAY_STORE.md)).

## Trackers Heartline uses

| Feature | Tracker | Rate / length | Where in the code |
|---|---|---|---|
| ECG | `ECG_ON_DEMAND` (+ its PPG channel for pulse arrival time) | 500 Hz, 30 s | `wear/.../sensor/sdk/SdkEcgSource.kt` |
| Blood pressure, quick | `PPG_ON_DEMAND` (green + IR + red when offered, green alone otherwise) + Android accelerometer, gyroscope, rotation vector (fastest) | 100 Hz, 20–60 s | `SdkPpgSource.kt`, `ImuRecorder.kt` |
| Blood pressure, precise | `ECG_ON_DEMAND` (ECG + its PPG) + the same motion sensors, with the arm-raise maneuver | 500 Hz, 34 s | `SdkEcgSource.kt`, `bp/BpMeasureViewModel.kt` |
| Blood pressure, before recording | `SKIN_TEMPERATURE_ON_DEMAND`, then `EDA_CONTINUOUS` (5 s) when available | a few seconds each | `BpAuxSensors.kt` |
| Heart rate, HRV, irregular rhythm | `HEART_RATE_CONTINUOUS` (heart rate + inter-beat intervals) | 1 Hz, background | `SdkHrSource.kt`, `monitor/` |
| SpO₂ | `SPO2_ON_DEMAND` | ~30 s | `SdkQuickSources.kt` |
| Skin temperature | `SKIN_TEMPERATURE_ON_DEMAND` (Watch5 and newer) | a few seconds | `SdkQuickSources.kt` |
| Stress | `HEART_RATE_CONTINUOUS` IBIs + `EDA_CONTINUOUS` (Watch8 and newer) | ~1 min | `QuickSources.kt` |
| Body composition | `BIA_ON_DEMAND` with a `TrackerUserProfile` (age, sex, height, weight) | ~15 s | `SdkQuickSources.kt` |

Only one on-demand tracker can run at a time, only in the foreground, and for about 30 seconds.
Several continuous trackers can run together. With the screen off they deliver batched data, and
`flush()` fetches it immediately.

## Permissions (target SDK 36+)

| Trackers | Permission |
|---|---|
| ECG, PPG, BIA, EDA | `com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA` |
| Heart rate | `android.permission.health.READ_HEART_RATE` |
| SpO₂ | `android.permission.health.READ_OXYGEN_SATURATION` |
| Skin temperature | `android.permission.health.READ_SKIN_TEMPERATURE` |
| Accelerometer (motion check) | `android.permission.ACTIVITY_RECOGNITION` |
| Background heart rate | `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` |

On Android 15 and older, `BODY_SENSORS` (and `BODY_SENSORS_BACKGROUND`) cover them. The watch
manifest also declares `<queries><package android:name="com.samsung.android.service.health" /></queries>`
so the SDK can find the service.

## Things we learned on real watches
- **ECG contact:** `LEAD_OFF` only reports a meaningful value on the first sample of a batch; the
  others are null. Heartline confirms contact from the signal itself (`EcgContactCheck`) before
  the countdown starts. See [algorithms/ECG_ALGORITHM.md](algorithms/ECG_ALGORITHM.md).
- **BIA progress:** the Galaxy Watch8 reports `PROGRESS` as 0–1 (1.0 = done), older firmware as
  0–100. Statuses 7, 8 and 9 mean the upper key, the lower key or both keys don't sense a finger.
- **Heart rate off the wrist:** `HEART_RATE_STATUS == -3`.
- The SDK doesn't read from or write to Samsung Health. Heartline keeps its own history.

## Errors

| Error | Meaning | Heartline's handling |
|---|---|---|
| `SDK_POLICY_ERROR` | App not registered, or developer mode off | Developer mode guide |
| `PERMISSION_ERROR` | A permission is missing | Permission step in watch setup |
| `OLD_PLATFORM_VERSION` / `PACKAGE_NOT_INSTALLED` | Health Sensor Service too old or missing | `resolve(activity)` opens the store |
| Tracker missing from `getSupportHealthTrackerTypes()` | This watch model doesn't have the sensor | The feature is hidden |

## Official resources
- [Introduction](https://developer.samsung.com/health/sensor/guide/introduction.html) ·
  [Data specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html) ·
  [Developer mode](https://developer.samsung.com/health/sensor/guide/developer-mode.html) ·
  [Permissions](https://developer.samsung.com/health/sensor/guide/permission-request.html)
- [API reference](https://developer.samsung.com/health/sensor/api-reference/overview-summary.html) ·
  [Release notes](https://developer.samsung.com/health/sensor/release-note.html) ·
  [FAQ](https://developer.samsung.com/health/sensor/faq.html) ·
  [Partner process](https://developer.samsung.com/health/sensor/process.html)
