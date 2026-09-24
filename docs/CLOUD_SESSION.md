# کار در سشن ابری Claude Code (Heartline)

> محیط: Ubuntu 24.04، JDK 21، Android SDK در `/opt/android-sdk` (`platforms;android-36`، `build-tools;36.0.0`)، بدون امولاتور/KVM/دستگاه.
> اعتبارسنجی = `assembleDebug` هر دو ماژول + تست JVM + Robolectric + Paparazzi (record/verify) + ktlint. نصب روی دستگاه = دانلود APK از GitHub Actions.

## 1) پروکسی Gradle (چرا و چطور)
سندباکس تمام ترافیک خروجی را از یک پروکسی HTTP که در `https_proxy`/`HTTPS_PROXY` تعریف شده عبور می‌دهد.
**JVM (و در نتیجه Gradle و sdkmanager) این متغیرهای محیطی را نادیده می‌گیرد** → بدون تنظیم، خطای
`java.net.UnknownHostException: dl.google.com` یا `Could not resolve …` می‌گیرید.

راه‌حل: `tools/cloud/gradle-proxy-setup.sh`
- متغیر پروکسی را پارس می‌کند (`scheme://[user[:pass]@]host:port`، با URL-decode).
- یک بلوک مدیریت‌شده بین `# >>> heartline-proxy >>>` و `# <<< heartline-proxy <<<` در `~/.gradle/gradle.properties` می‌نویسد:
  `systemProp.http(s).proxyHost/Port`، در صورت وجود `proxyUser/proxyPassword`، `systemProp.http.nonProxyHosts` (از `NO_PROXY`؛ CIDRها حذف و `.domain` → `*.domain`)
  و `jdk.http.auth.tunneling.disabledSchemes=` (برای Basic auth روی CONNECT).
- idempotent است؛ بلوک قبلی را جایگزین و در نبود پروکسی حذف می‌کند.
- فقط وقتی `CLAUDE_CODE_REMOTE=true` اجرا می‌شود (اجرای دستی خارج از سشن: `HEARTLINE_FORCE_PROXY_SETUP=1`).

به‌صورت **SessionStart hook** در `.claude/settings.json` وصل است، پس در شروع هر سشن ابری خودکار اجرا می‌شود.
اجرای دستی: `bash tools/cloud/gradle-proxy-setup.sh && grep -A12 heartline-proxy ~/.gradle/gradle.properties`

> نکته: اگر پورت پروکسی در طول سشن عوض شد (ری‌استارت کانتینر)، اسکریپت را دوباره اجرا کنید و `./gradlew --stop` بزنید.

### sdkmanager
sdkmanager تنظیمات Gradle را نمی‌خواند؛ پروکسی را صریح بدهید:
```bash
p="${HTTPS_PROXY#*://}"; p="${p%/}"
sdkmanager --proxy=http --proxy_host="${p%:*}" --proxy_port="${p##*:}" "platforms;android-36"
```

## 2) حلقه‌ی روزمره
```bash
./gradlew :phone:assembleDebug :wear:assembleDebug   # بیلد APKها
./gradlew test                                        # JVM + Robolectric
./gradlew recordPaparazziDebug                        # تولید اسکرین‌شات‌ها
./gradlew verifyPaparazziDebug                        # رگرسیون بصری
./gradlew ktlintCheck                                 # (ktlintFormat برای اصلاح)
bash tools/screenshots/sync.sh                        # کپی PNGها به docs/screenshots/
```

## 3) CI و دانلود APK
`.github/workflows/build.yml` با هر push/PR: ktlint + تست + verifyPaparazzi + assembleDebug هر دو ماژول،
سپس artifactهای `heartline-phone-debug` و `heartline-wear-debug` را آپلود می‌کند
(GitHub → Actions → آخرین run → بخش Artifacts). هر دو APK با `keystore/debug.keystore` مشترک امضا می‌شوند
(لازمه‌ی Wearable Data Layer و نصب روی نسخه‌ی قبلی).

## 4) عیب‌یابی
| علامت | علت | راه‌حل |
|---|---|---|
| `UnknownHostException` / `Could not GET` | پروکسی برای JVM تنظیم نشده | اسکریپت بالا + `./gradlew --stop` |
| `407 Proxy Authentication Required` | Basic auth غیرفعال در JDK | بلوک اسکریپت شامل `disabledSchemes=` است؛ daemon را ری‌استارت کنید |
| `PKIX path building failed` | CA پروکسی در truststore نیست | `JAVA_TOOL_OPTIONS` سشن truststore را تنظیم می‌کند؛ آن را unset نکنید |
| Paparazzi: `verify` شکست | تغییر بصری | PNGهای `*/build/paparazzi/failures` را ببینید؛ اگر عمدی است `record` |
