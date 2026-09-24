# دیزاین‌سیستم Heartline (One UI-like)

مرجع کامل تصمیمات: `docs/MASTER_PLAN.md` §3. پیاده‌سازی:
- توکن‌های رنگ مشترک: `shared/.../design/Palette.kt`
- گوشی: `phone/.../ui/theme/` (Theme, Type = Inter, Dimens) و `ui/components/` (ReachabilityScaffold, RoundedCard, CardRow, Chip, PillButton, IconBadge, MetricValue, EcgStrip, MiniWave, DayRangeChart, WeekBars)
- ساعت: `wear/.../ui/theme/WearTheme.kt` و `ui/components/` (ActionScreen, LiveWave) + `MeasuringScreen`

## قواعد
| موضوع | گوشی | ساعت |
|---|---|---|
| پس‌زمینه | `#F6F6F8` / مشکی | همیشه مشکی OLED |
| سرصفحه | Reachability: عنوان بزرگ در ۳۰٪ بالا، جمع‌شدن به نوار ۵۶dp | ListHeader منحنی |
| کارت | شعاع 26dp، بدون سایه، padding 20dp | Button tonal تمام‌عرض |
| دکمه‌ی اصلی | Pill 52dp | EdgeButton (Small؛ ExtraSmall زیر 210dp) |
| رنگ معنایی | ECG قرمز، BP بنفش، HR صورتی، SpO2 فیروزه‌ای، دما نارنجی، بدن سبز، استرس زرد | همان (نسخه‌ی dark) |
| اندازه‌گیری | — | حلقه‌ی پیشرفت روی لبه + شمارنده‌ی وسط + موج/آیکن + راهنما |
| صفحه‌ی گرد | — | متن حداقل 8–9٪ عرض از لبه؛ روی ساعت کوچک متن کوتاه‌تر و آیکن کوچک‌تر |
| دسترس‌پذیری | contentDescription برای نمودارها/موج؛ هدف لمسی ≥48dp | هدف لمسی ≥52dp |

## بازبینی بصری
هر تغییر UI: `./gradlew recordPaparazziDebug` → بازبینی PNGها (برش گرد، کنتراست، چیدمان) → `bash tools/screenshots/sync.sh` → کامیت `docs/screenshots/`.
ماتریس: گوشی Pixel 6 light/dark؛ ساعت small round (192dp) و large round (454px، کلاس Watch8 Classic)؛ صفحه‌ی PDF در 2×.
