# Samsung Health Sensor SDK — مرجع کامل (برای توسعه‌ی اپ سلامت خودمان)

> **هدف این سند:** مرجع جامع و دقیق از «Samsung Health Sensor SDK» (همان SDK رسمی سامسونگ برای خواندن سنسورهای ساعت گلکسی) — طوری نوشته شده که یک AI یا توسعه‌دهنده بتواند با آن یک اپ سلامت مستقل (مثل GeminiMan Wellness Companion) بسازد.
> **منابع:** مستندات رسمی developer.samsung.com (Programming Guide + API Reference + Release Note + FAQ + Process) + استخراج مستقیم API از فایل `samsung-health-sensor-api` موجود در APK دیکامپایل‌شده‌ی Wellness Companion (`decompiled/companion_watch_1.0.1.50`).
> **نسخه‌ی SDK:** v1.4.1 (20 آگوست 2025) — آخرین نسخه‌ی مستندشده.
> **تاریخ تدوین:** 2026-09-24

---

## 1) معرفی

Samsung Health Sensor SDK به یک **اپ Wear OS روی ساعت گلکسی** اجازه می‌دهد داده‌ی خام (Raw) یا پردازش‌شده‌ی سنسورهای سلامت را دریافت کند. این SDK از طریق اپ سیستمی **Health Sensor Service** (که روی ساعت از پیش نصب است و با Galaxy Store/Play Store آپدیت می‌شود) کار می‌کند.

- **پکیج‌های SDK:**
  - `com.samsung.android.service.health.tracking` — اتصال سرویس و مدیریت ترکرها
  - `com.samsung.android.service.health.tracking.data` — داده‌ها، انواع ترکر و ValueKeyها
- **فایل کتابخانه:** `samsung-health-sensor-api.aar` (نسخه‌ی v1.4.1)
  - ✅ **کپی لوکال آماده در ورک‌اسپیس:** `sdk/samsung-health-sensor-api.aar` — **تأیید دوطرفه** (فایل پورتال رسمی = آینه‌ی GitHub؛ هش یکسان). جزئیات/SHA-256: `sdk/README.md`
  - 📚 **مستندات آفلاین کامل + نمونه‌کدهای رسمی سامسونگ:** `sdk/official/1.4.1/` — ۵۵ صفحه‌ی HTML کامل (۳۲ صفحه API Reference کلاس‌به‌کلاس، ۱۱ صفحه Programming Guide، FAQ/Process/Release-note و ۷ نمونه‌کد: ECG / HR / SpO2 / دمای پوست / تعرق / انتقال HR به گوشی) — جزئیات ساختار در `sdk/README.md`
- **دستگاه‌های هدف:** Galaxy Watch4 و جدیدتر (Wear OS Powered by Samsung) — **فقط ساعت، روی گوشی کار نمی‌کند**
- **شبیه‌ساز (Emulator): پشتیبانی نمی‌شود** — برای توسعه، ساعت واقعی لازم است.
- **ماهیت داده:** فقط fitness/wellness — **نه تشخیص و درمان پزشکی**. (سامسونگ رسماً همین را الزام کرده و اپ Dante63 هم دیسکلیمر wellness دارد.)

---

## 2) معماری و مدل دسترسی (خیلی مهم)

```
اپ شما (Wear OS، روی ساعت)
   │  import samsung-health-sensor-api.aar
   ▼
HealthTrackingService ── bind ──► Health Sensor Service (اپ سیستمی روی ساعت)
                                        │
                                        ▼
                                  سنسورهای BioActive (ECG/PPG/BIA/…) + سایر سنسورها
```

### 2.1 مدل Partner (توزیع عمومی)
- قابلیت‌های SDK **فقط برای اپ‌های partner ثبت‌شده** فعال است.
- Health Sensor Service **package name + امضای SHA-256** اپ شما را با اطلاعات ثبت‌شده چک می‌کند؛ در صورت عدم تطابق → خطای **`SDK_POLICY_ERROR`**.
- برای توزیع عمومی اپ، باید **درخواست Partner** بدهید و پس از تأیید، package/signature شما ثبت می‌شود.
- لینک درخواست partner (رسمی): `https://developer.samsung.com/SHealth/business-partner/m48wzh9rwz606k0h`
- بدون partner: اپ فقط وقتی کار می‌کند که **Developer Mode** روی ساعت روشن باشد.

### 2.2 Developer Mode (برای تست/دیباگ — نه کاربر نهایی)
روی ساعت:
1. Settings → Apps
2. پایین بروید → **Health Sensor Service** (در نسخه‌های قدیمی‌تر ممکن است **Health Platform** نام داشته باشد)
3. حدود **۱۰ بار** روی عنوان (title area) بزنید
4. دکمه‌ی **Developer mode** ظاهر می‌شود → بزنید
5. با لایسنس **Samsung Health Partner Service & SDK License** موافقت کنید و روشنش کنید

> ⚠️ سامسونگ تأکید کرده: Developer Mode فقط برای تست است و راهنمایش نباید به کاربر نهایی داده شود. اپ Dante63 هم همین کار را می‌کند (یک صفحه‌ی راهنما + ویدئو داخل اپ).

### 2.3 سازگاری نسخه‌ی SDK با Health Sensor Service
| SDK library | Health Sensor Service لازم |
|---|---|
| 1.0.0 | 1.2.0+ |
| 1.1.0 | 1.3.0+ |
| 1.2.0 | 1.5.0+ |
| 1.3.0 | 1.5.4+ |
| **1.4.0** | **1.6.5+** |

اگر نسخه‌ها نخوانند → خطای `OLD_PLATFORM_VERSION` که با `HealthTrackerException.hasResolution()` / `resolve(Activity)` قابل حل است (کاربر را به آپدیت سرویس هدایت می‌کند).
> در APK کامپنیون، داخل کد SDK لاگ `"min health version required :160503000"` دیده می‌شود (یعنی 1.6.05.03.000 — همان نیاز SDK 1.4.0).

---

## 3) انواع ترکر (HealthTrackerType) و مشخصات داده

### 3.1 Continuous (پیوسته — کم‌مصرف)
| Tracker | Raw/Processed | توضیح |
|---|---|---|
| `ACCELEROMETER_CONTINUOUS` | Raw | x/y/z با فرکانس 25Hz |
| `EDA_CONTINUOUS` | Raw | فعالیت الکترودرمال، 1Hz — **فقط Watch8 و جدیدتر** |
| `HEART_RATE_CONTINUOUS` | Processed | ضربان + **IBI** (برای HRV)، 1Hz |
| `PPG_CONTINUOUS` | Raw | PPG سبز/IR/قرمز با 25Hz |
| `SKIN_TEMPERATURE_CONTINUOUS` | Processed | دمای پوست + دمای محیط — **Watch5 و جدیدتر** (نه دمای بدن) |

### 3.2 On-demand (درخواستی — فقط یکی در هر لحظه، حداکثر ~30 ثانیه، فقط Foreground)
| Tracker | Raw/Processed | توضیح |
|---|---|---|
| `ECG_ON_DEMAND` | Raw | ECG با **500Hz** |
| `PPG_ON_DEMAND` | Raw | PPG سبز/IR/قرمز با **100Hz** |
| `SPO2_ON_DEMAND` | Processed | اکسیژن خون — نیازمند Health Sensor Service **v1.3.0+** |
| `SKIN_TEMPERATURE_ON_DEMAND` | Processed | دمای پوست/محیط |
| `BIA_ON_DEMAND` | Processed | ترکیب بدن (چربی/عضله/آب/…) |
| `MF_BIA_ON_DEMAND` | Processed | امپدانس چندفرکانسی (5/10/50/250 kHz) — **Watch8 و جدیدتر** |

### 3.3 Other
| Tracker | Raw/Processed | توضیح |
|---|---|---|
| `SWEAT_LOSS` | Processed | میزان تعرق بعد از دویدن (نیاز به `ExerciseType`/`TrackerUserProfile`) |

### 3.4 enum کامل + مقادیر Deprecated
`HealthTrackerType` (از خود SDK استخراج شده):
```
ACCELEROMETER*, ACCELEROMETER_CONTINUOUS,
BIA*, BIA_ON_DEMAND,
ECG*, ECG_ON_DEMAND,
EDA_CONTINUOUS,
HEART_RATE*, HEART_RATE_CONTINUOUS,
MF_BIA_ON_DEMAND,
PPG_GREEN*, PPG_IR*, PPG_RED*, PPG_CONTINUOUS, PPG_ON_DEMAND,
SKIN_TEMPERATURE*, SKIN_TEMPERATURE_CONTINUOUS, SKIN_TEMPERATURE_ON_DEMAND,
SPO2*, SPO2_ON_DEMAND,
SWEAT_LOSS
```
(*) = deprecated؛ معادل جدید: `ACCELEROMETER→ACCELEROMETER_CONTINUOUS`، `BIA→BIA_ON_DEMAND`، `ECG→ECG_ON_DEMAND`، `HEART_RATE→HEART_RATE_CONTINUOUS`، `PPG_GREEN→PPG_CONTINUOUS`، `PPG_IR/PPG_RED→PPG_ON_DEMAND`، `SKIN_TEMPERATURE→SKIN_TEMPERATURE_ON_DEMAND`، `SPO2→SPO2_ON_DEMAND`.

---

## 4) مجوزها (Permissions) — بر اساس نسخه‌ی هدف اپ

### 4.1 اگر targetSdk = Android 16 (API 36) یا بالاتر
| Tracker | مجوز لازم |
|---|---|
| `ACCELEROMETER_CONTINUOUS` | `android.permission.ACTIVITY_RECOGNITION` |
| `BIA_ON_DEMAND`, `ECG_ON_DEMAND`, `EDA_CONTINUOUS`, `MF_BIA_ON_DEMAND`, `PPG_CONTINUOUS`, `PPG_ON_DEMAND` | `com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA` |
| `HEART_RATE_CONTINUOUS` | `android.permission.health.READ_HEART_RATE` |
| `SPO2_ON_DEMAND` | `android.permission.health.READ_OXYGEN_SATURATION` |
| `SKIN_TEMPERATURE_CONTINUOUS/ON_DEMAND` | `android.permission.health.READ_SKIN_TEMPERATURE` |
| `SWEAT_LOSS` | `ACTIVITY_RECOGNITION` + `READ_ADDITIONAL_HEALTH_DATA` (هر دو) |

### 4.2 اگر targetSdk = Android 15 (API 35) یا پایین‌تر
- تقریباً همه → `android.permission.BODY_SENSORS`
- `ACCELEROMETER_CONTINUOUS` و `SWEAT_LOSS` → `ACTIVITY_RECOGNITION` (+ `BODY_SENSORS` برای sweat loss)

### 4.3 نمونه Manifest
```xml
<uses-permission android:name="android.permission.BODY_SENSORS" />
<uses-permission android:name=
    "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA" />
<uses-permission android:name="android.permission.health.READ_HEART_RATE" />
<uses-permission android:name="android.permission.health.READ_OXYGEN_SATURATION" />
<uses-permission android:name="android.permission.health.READ_SKIN_TEMPERATURE" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />

<!-- برای Package Visibility (targetSdk 30+): دیدن اپ Health Sensor Service -->
<queries>
    <package android:name="com.samsung.android.service.health" />
</queries>
```
> نکته‌ی عملی (از اپ Dante63): `queries` بالا در manifest کامپنیون وجود دارد و برای کارکرد صحیح چک‌های SDK لازم است.

---

## 5) API Reference کامل (از SDK v1.4.x)

### 5.1 `HealthTrackingService`
```java
// سازنده (ترتیب رسمی: listener, context — در اپ Dante هم تأیید شد: new HealthTrackingService(connectionListener, context))
public HealthTrackingService(ConnectionListener listener, Context context);

public void connectService();
public void disconnectService();

public HealthTrackerCapability getTrackingCapability();

public HealthTracker getHealthTracker(HealthTrackerType type);
public HealthTracker getHealthTracker(HealthTrackerType type, TrackerUserProfile profile);
public HealthTracker getHealthTracker(HealthTrackerType type, TrackerUserProfile profile, ExerciseType exerciseType);
public HealthTracker getHealthTracker(HealthTrackerType type, Set<PpgType> ppgTypes);           // برای PPG
public HealthTracker getHealthTracker(HealthTrackerType type, TrackerUserProfile profile, ExerciseType exerciseType, int durationSeconds); // sweat loss
```

### 5.2 `ConnectionListener` (اینترفیس)
```java
public interface ConnectionListener {
    void onConnectionSuccess();
    void onConnectionEnded();
    void onConnectionFailed(HealthTrackerException e);
}
```

### 5.3 `HealthTracker`
```java
public void setEventListener(TrackerEventListener listener);   // شروع دریافت داده
public void unsetEventListener();                              // توقف دریافت
public boolean flush();                                        // دریافت فوری داده‌های بافر‌شده (وقتی صفحه خاموش است)

// فقط برای SWEAT_LOSS:
public void setExerciseData(DataType dataType, float[] values, long[] timestamps);
public void setExerciseState(ExerciseState state);

public interface TrackerEventListener {
    void onDataReceived(List<DataPoint> dataPoints);
    void onError(TrackerError error);
    void onFlushCompleted();
}

public enum TrackerError {
    PERMISSION_ERROR,     // مجوز لازم داده نشده
    SDK_POLICY_ERROR      // package/signature با اطلاعات ثبت‌شده‌ی سامسونگ نمی‌خواند
}
```

### 5.4 `HealthTrackerCapability`
```java
public List<HealthTrackerType> getSupportHealthTrackerTypes(); // ترکرهای موجود روی همین ساعت
public String getVersion();
```

### 5.5 `HealthTrackerException`
```java
public static final int PACKAGE_NOT_INSTALLED = 0;  // Health Sensor Service نصب نیست
public static final int OLD_PLATFORM_VERSION  = 1;  // نسخه‌ی سرویس قدیمی است

public int getErrorCode();
public boolean hasResolution();          // آیا Galaxy Store/Play Store برای آپدیت هست؟
public void resolve(Activity activity);  // باز کردن استور برای آپدیت سرویس
```

### 5.6 `TrackerUserProfile` (برای BIA/MF-BIA/SweatLoss لازم است)
```java
new TrackerUserProfile.Builder()
    .setAge(int years)
    .setGender(int gender)     // مطابق enum سامسونگ (0=مرد، 1=زن در اکثر نسخه‌ها)
    .setHeight(float cm)
    .setWeight(float kg)
    .build();
```

### 5.7 `DataPoint`
```java
public long getTimestamp();                    // زمان نمونه (میلی‌ثانیه)
public <T> T getValue(ValueKey<T> valueKey);   // خواندن مقدار
```

### 5.8 `ValueKey` — تمام Setها و فیلدها (استخراج مستقیم از SDK)

**`EcgSet`** (ECG_ON_DEMAND):
| ValueKey | نوع | توضیح |
|---|---|---|
| `ECG_MV` | Float | مقدار ECG به میلی‌ولت |
| `MAX_THRESHOLD_MV` / `MIN_THRESHOLD_MV` | Float | آستانه‌ها (برای تشخیص contact) |
| `LEAD_OFF` | Integer | وضعیت جدا شدن الکترود |
| `SEQUENCE` | Integer | شماره‌ی توالی نمونه |
| `PPG_GREEN` | Integer | کانال PPG همراه (برای تشخیص contact) |

**`HeartRateSet`** (HEART_RATE_CONTINUOUS):
| ValueKey | نوع |
|---|---|
| `HEART_RATE` | Integer |
| `IBI_LIST` | List\<Integer\> (فاصله‌ی ضربان‌ها → HRV) |
| `IBI_STATUS_LIST` | List\<Integer\> |
| `HEART_RATE_STATUS` | Integer (‏-3 = ساعت روی دست نیست) |

**`SpO2Set`** (SPO2_ON_DEMAND):
| ValueKey | نوع |
|---|---|
| `SPO2` | Integer (درصد) |
| `HEART_RATE` | Integer |
| `ACCURACY_FLAG` | Integer |
| `STATUS` | Integer |

**`PpgSet`** (PPG_CONTINUOUS / PPG_ON_DEMAND):
| ValueKey | نوع |
|---|---|
| `PPG_GREEN` / `PPG_IR` / `PPG_RED` | Integer |
| `GREEN_STATUS` / `IR_STATUS` / `RED_STATUS` | Integer |

**`SkinTemperatureSet`**: `OBJECT_TEMPERATURE` (Float)، `AMBIENT_TEMPERATURE` (Float)، `STATUS` (Integer)

**`BiaSet`**: `STATUS`, `BODY_FAT_RATIO`, `BODY_FAT_MASS`, `TOTAL_BODY_WATER`, `SKELETAL_MUSCLE_RATIO`, `SKELETAL_MUSCLE_MASS`, `BASAL_METABOLIC_RATE`, `FAT_FREE_RATIO`, `FAT_FREE_MASS`, `PROGRESS`, `BODY_IMPEDANCE_MAGNITUDE`, `BODY_IMPEDANCE_DEGREE`

**`MfBiaSet`**: `BODY_IMPEDANCE_MAGNITUDE_5K/10K/50K/250K`, `BODY_IMPEDANCE_PHASE_5K/10K/50K/250K`, `STATUS`, `PROGRESS`

**`EdaSet`**: `SKIN_CONDUCTANCE` (Float)، `STATUS` (Integer)

**`AccelerometerSet`**: `ACCELEROMETER_X/Y/Z` (Integer)

**`SweatLossSet`**: `SWEAT_LOSS` (Float)، `STATUS` (Integer)

**Deprecated**: `PpgGreenSet`، `PpgIrSet`، `PpgRedSet` (به‌جایشان `PpgSet`).

### 5.9 انواع کمکی
- **`PpgType`**: `GREEN`, `IR`, `RED` — برای `getHealthTracker(type, Set<PpgType>)`
- **`ExerciseType`** و **`ExerciseState`** و **`DataType`** — برای SWEAT_LOSS
- **`ServiceDataPoint` / `ServiceValue`** — کلاس‌های داخلی SDK (Parcelable) — در اپ معمولاً مستقیم استفاده نمی‌شوند.

---

## 6) نمونه‌ی کد کامل (اتصال + مجوز + Capability + ECG)

```java
public class EcgActivity extends FragmentActivity {
    private HealthTrackingService tracking;
    private HealthTracker ecgTracker;

    private final ConnectionListener connectionListener = new ConnectionListener() {
        @Override public void onConnectionSuccess() {
            List<HealthTrackerType> types =
                tracking.getTrackingCapability().getSupportHealthTrackerTypes();
            if (types.contains(HealthTrackerType.ECG_ON_DEMAND)) {
                ecgTracker = tracking.getHealthTracker(HealthTrackerType.ECG_ON_DEMAND);
            }
        }
        @Override public void onConnectionEnded() { }
        @Override public void onConnectionFailed(HealthTrackerException e) {
            if (e.hasResolution()) e.resolve(EcgActivity.this);
        }
    };

    private final HealthTracker.TrackerEventListener ecgListener =
        new HealthTracker.TrackerEventListener() {
            @Override public void onDataReceived(List<DataPoint> points) {
                for (DataPoint p : points) {
                    float mv = p.getValue(ValueKey.EcgSet.ECG_MV);
                    long ts = p.getTimestamp();
                    // رسم موج ECG / ارسال به گوشی
                }
            }
            @Override public void onError(HealthTracker.TrackerError error) {
                // PERMISSION_ERROR / SDK_POLICY_ERROR
            }
            @Override public void onFlushCompleted() { }
        };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 1) مجوزها را چک/درخواست کن (READ_ADDITIONAL_HEALTH_DATA برای ECG)
        // 2) اتصال:
        tracking = new HealthTrackingService(connectionListener, this);
        tracking.connectService();
    }

    private void startEcg() { ecgTracker.setEventListener(ecgListener); } // حداکثر ~30 ثانیه
    private void stopEcg()  { ecgTracker.unsetEventListener(); }

    @Override protected void onDestroy() {
        if (tracking != null) tracking.disconnectService();
        super.onDestroy();
    }
}
```

### نکات عملیاتی (از FAQ و مستندات)
- **On-demand**: فقط در Foreground، فقط یکی هم‌زمان، ~30 ثانیه.
- **Continuous**: چند ترکر هم‌زمان OK؛ صفحه خاموش → داده‌ی بافری با دوره‌های بزرگ‌تر (مثلاً HR هر 10 دقیقه 600 نمونه)؛ با `flush()` می‌توان فوری گرفت.
- **Off-body**: با `STATUS` (مثلاً `HEART_RATE_STATUS == -3`) یا `Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT` قابل تشخیص است.
- **PPG خام** برای تخمین چیزهایی مثل فشار خون (خود سامسونگ BP نمی‌دهد — الگوریتم + کالیبراسیون باید خودت بسازی).
- **SDK داده را از Samsung Health نمی‌گیرد** و با آن به اشتراک نمی‌گذارد؛ اتصال به Samsung Health (اگر خواستی) باید جداگانه انجام شود.
- **HR + IBI** → محاسبه‌ی HRV ممکن است (این تفاوت کلیدی با Health Services است).
- **Accelerometer 25Hz** با مصرف باتری کم (بدون بیدار کردن CPU).

---

## 7) جریان راه‌اندازی اپ (Process رسمی)

1. **دانلود SDK**: Samsung Health Sensor SDK v1.4.1 از پورتال سامسونگ (نیازمند حساب سامسونگ) — **کپی لوکال از قبل موجود است:** `sdk/samsung-health-sensor-api.aar` → import در پروژه (`samsung-health-sensor-api.aar`)
2. **Developer Mode** روی ساعت روشن کن (برای تست با امضای غیرثبت‌شده)
3. **توسعه** (اتصال → Capability → مجوز → Tracker → Listener)
4. **درخواست Partner** قبل از توزیع عمومی (ثبت package name + SHA-256) — لینک بالا
5. **توزیع** (Play Store/Galaxy Store) — بعد از تأیید، بدون نیاز به Dev Mode روی دستگاه کاربر

> بدون مرحله ۴، اپ فقط با Dev Mode روشن کار می‌کند — یعنی برای کاربر عادی قابل استفاده نیست.

---

## 8) نقشه‌ی پیشنهادی برای اپ ما (بر اساس SDK)

| فاز | قابلیت | ترکر | نکته |
|---|---|---|---|
| 1 | ECG | `ECG_ON_DEMAND` (500Hz) | ساده‌ترین شروع؛ داده‌ی خام، نیاز به پردازش/نمایش |
| 1 | HR + HRV | `HEART_RATE_CONTINUOUS` + `IBI_LIST` | پیوسته، کم‌مصرف |
| 2 | SpO2 | `SPO2_ON_DEMAND` | 30 ثانیه، نیاز به Service 1.3.0+ |
| 2 | دمای پوست | `SKIN_TEMPERATURE_ON_DEMAND/CONTINUOUS` | Watch5+ |
| 2 | تخمین BP | `PPG_ON_DEMAND` (100Hz) یا `PPG_CONTINUOUS` | نیاز به الگوریتم + کالیبراسیون (مثل SHM: 3 بار در 28 روز) |
| 3 | ترکیب بدن | `BIA_ON_DEMAND` (+`TrackerUserProfile`) | Watch4+ |
| 3 | EDA / MF-BIA | `EDA_CONTINUOUS` / `MF_BIA_ON_DEMAND` | Watch8+ |
| 3 | تعرق | `SWEAT_LOSS` | نیاز به ExerciseType/Profile |

**هشدارها:**
- Partner شدن برای توزیع عمومی الزامی است (و سامسونگ می‌تواند کنترل کند — درس گرفته از سرنوشت SHM MOD).
- اپ باید wellness بماند (نه diagnosis) — هم قانونی، هم مطابق قوانین سامسونگ.
- برای UI/UX فارسی و گزارش‌ها هیچ محدودیتی از سمت SDK نیست.

---

## 9) محدودیت‌ها و خطاهای رایج

| خطا | معنا | راه‌حل |
|---|---|---|
| `SDK_POLICY_ERROR` | package/signature ثبت‌نشده یا عدم تطابق | Dev Mode برای تست؛ Partner Request برای توزیع |
| `PERMISSION_ERROR` | مجوز لازم داده نشده | درخواست مجوز مطابق جدول بخش 4 |
| `OLD_PLATFORM_VERSION` | Health Sensor Service قدیمی | `resolve(activity)` → هدایت به آپدیت |
| `PACKAGE_NOT_INSTALLED` | Health Sensor Service نصب نیست | `resolve(activity)` |
| Capability خالی/ناقص | مدل ساعت/نسخه‌ی نرم‌افزار پشتیبانی نمی‌کند | `getSupportHealthTrackerTypes()` را چک کن |

---

## 10) منابع رسمی

- Introduction: https://developer.samsung.com/health/sensor/guide/introduction.html
- Data Specifications: https://developer.samsung.com/health/sensor/guide/data-specifications.html
- Developer Mode: https://developer.samsung.com/health/sensor/guide/developer-mode.html
- Connect: https://developer.samsung.com/health/sensor/guide/connect-health-sensor-service.html
- Capability: https://developer.samsung.com/health/sensor/guide/capability-check.html
- Permissions: https://developer.samsung.com/health/sensor/guide/permission-request.html
- Tracking Data: https://developer.samsung.com/health/sensor/guide/tracking-data.html
- Process/Partner: https://developer.samsung.com/health/sensor/process.html
- Release Note: https://developer.samsung.com/health/sensor/release-note.html
- FAQ: https://developer.samsung.com/health/sensor/faq.html
- API Reference: https://developer.samsung.com/health/sensor/api-reference/overview-summary.html
- Code Labs (نمونه‌ها): Blood Oxygen، Skin Temperature، ECG Monitor، HR Tracker، Sweat Loss
- **شاهد محلی:** `decompiled/companion_watch_1.0.1.50_java/sources/com/samsung/android/service/health/tracking/` (کل SDK v1.4.x داخل APK کامپنیون)
- **بسته‌ی رسمی SDK (آفلاین):** `sdk/official/1.4.1/` — API Reference + Programming Guide + ۷ نمونه‌کد رسمی + AAR اصلی (`sdk/README.md`)
