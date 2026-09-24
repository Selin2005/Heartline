# پروتکل سینک ساعت ↔ گوشی (v1)

پیاده‌سازی: `shared/src/main/kotlin/com/heartline/shared/sync/` (`Protocol`, `SyncEngines`, `WaveCodec`, `Messages`) — ترنسپورت دستگاه: `datalayer/DataLayerTransport` (MessageClient + ChannelClient).
کشف نود: Capability `heartline_phone` / `heartline_watch` (`res/values/wear.xml`). هر دو اپ `applicationId = com.heartline.app` و امضای یکسان دارند.

| مسیر | جهت | کانال | محتوا (JSON مگر ذکر شود) | پاسخ |
|---|---|---|---|---|
| `/hl/v1/hello` | W→P | Message | `Hello{protocol, appVersion, capabilities, sensorServiceVersion}` | گوشی `settings` + `bp/calibration` + `profile` می‌فرستد |
| `/hl/v1/record/meta` | W→P | Message | `RecordMeta{id, kind, startedAtMs, durationMs, sampleRateHz, sampleCount, summary}` | `record/ack` پس از ذخیره |
| `/hl/v1/record/wave/<id>` | W→P | Channel | باینری `HLW1`: ۱۶ بایت هدر (magic, kind, rate, count) + Float32LE | (با meta ادغام می‌شود) |
| `/hl/v1/record/ack` | P→W | Message | `Ack{id, ok}` | ساعت از outbox حذف می‌کند |
| `/hl/v1/hr/batch` | W→P | Message | `HrBatch{id, minutes[HrMinute]}` (هر ۱۵ دقیقه) | `ack` |
| `/hl/v1/alert` | W→P | Message | `HealthAlert{id, kind(IRREGULAR_RHYTHM/HIGH/LOW), atMs, bpm, windowStartsMs}` | `ack` |
| `/hl/v1/bp/calib-capture` | P→W | Message | `CaptureRequest{captureId, round}` | ساعت اعلان «دور کالیبراسیون» |
| `/hl/v1/bp/calib-capture` | W→P | Message | `CaptureResult{id, captureId, round, features}` | `ack` |
| `/hl/v1/bp/calibration` | P→W | Message | `BpCalibration` یا `null` (حذف) | — |
| `/hl/v1/settings` | P→W | Message | `MonitorSettings{irregularRhythmEnabled, heartRateAlertsEnabled, highBpm, lowBpm}` | ساعت سرویس پایش را روشن/خاموش می‌کند |
| `/hl/v1/profile` | P→W | Message | `UserProfile{birthYear, sex, heightCm, weightKg}` | — |
| `/hl/v1/delete` | P→W | Message | `DeleteRecord{id}` | — |
| `/hl/v1/open` | P→W | Message | نام مسیر (`ecg`, `blood_pressure`, …) متن ساده | اعلان «Tap to start» |

قواعد:
- **Idempotent:** شناسه‌ها UUID؛ گوشی رکورد تکراری را دوباره ذخیره نمی‌کند ولی دوباره ack می‌دهد.
- **ترتیب:** meta و wave ممکن است به هر ترتیبی برسند؛ ذخیره پس از رسیدن هر دو (یا فقط meta وقتی `sampleCount = 0`).
- **ماندگاری ساعت:** رکوردها و پیام‌ها در Room (`records`, `messages`) تا ack می‌مانند؛ `SyncWorker` با backoff نمایی تلاش می‌کند؛ پیام‌های قدیمی‌تر از ۷ روز حذف می‌شوند.
- **مقاومت:** پیام خراب/ناشناخته دور ریخته می‌شود (تست fuzz: `RobustnessTest`).
- **سازگاری:** `Hello.protocol` باید `1` باشد؛ فیلدهای ناشناخته نادیده گرفته می‌شوند (`ignoreUnknownKeys`).
