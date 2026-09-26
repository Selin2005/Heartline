# Third-party notices

Heartline is licensed under the [AGPL-3.0](LICENSE), with the additional permission in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). It includes or links to the following third-party
components, each under its own license. Those licenses keep applying to those components.

## Bundled in the app

| Component | Used for | License |
|---|---|---|
| [Samsung Health Sensor SDK](https://developer.samsung.com/health/sensor) 1.4.1 (`wear/libs/samsung-health-sensor-api.aar`) | Reading ECG, PPG, SpO₂, skin temperature, EDA and BIA on the Galaxy Watch | Samsung Health Partner Service & SDK License (proprietary) |
| [Inter](https://github.com/rsms/inter) typeface (`*/src/main/res/font/`) | App typography | SIL Open Font License 1.1 — see [third_party/inter/LICENSE.txt](third_party/inter/LICENSE.txt) |
| [ECGFounder](https://github.com/PKUDigitalHealth/ECGFounder) single-lead model, exported to ONNX (`phone/src/main/assets/ecg/ecgfounder_1lead_fp16.onnx`) | ECG second opinion on the phone | MIT |
| [PaPaGei-S](https://github.com/Nokia-Bell-Labs/papagei-foundation-model) PPG encoder, exported to ONNX and quantized (`phone/src/main/assets/papagei_s_int8.onnx`) | Pulse-shape features for the personal blood-pressure model | BSD-3-Clause-Clear (code); weights from [Zenodo record 13983110](https://zenodo.org/records/13983110) under the terms stated there |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | Running the models above | MIT |
| AndroidX: Jetpack Compose, Wear Compose, Glance, Room, WorkManager, DataStore, Wear Tiles, ProtoLayout, Remote Compose and Health Services | App framework | Apache License 2.0 |
| [Google Play services Wearable](https://developers.google.com/android/guides/overview) | Phone ↔ watch Data Layer | Android Software Development Kit License (proprietary) |
| [Kotlin](https://kotlinlang.org), kotlinx.coroutines, kotlinx.serialization | Language and libraries | Apache License 2.0 |
| [Koin](https://insert-koin.io) | Dependency injection | Apache License 2.0 |

## Models trained by the project

`shared/src/main/resources/ecg/rhythm_model.json` and
`phone/src/main/assets/ecg/second_opinion_model.json` are trained by the scripts in `tools/`, using
public PhysioNet databases (PhysioNet/CinC Challenge 2017, MIT-BIH Arrhythmia, MIT-BIH Atrial
Fibrillation, MIT-BIH Noise Stress Test). The databases themselves are not included. Their
licenses and citation requirements are listed on each database's PhysioNet page.

## Used only at build or test time (not shipped)

Gradle, the Android Gradle Plugin, ktlint, Robolectric, Paparazzi, JUnit (Apache-2.0, MIT or EPL-1.0).
