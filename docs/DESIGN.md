# Heartline design system

Heartline follows the visual language of One UI. The implementation lives in:
- **Shared colour tokens:** `shared/.../design/Palette.kt`
- **Phone:** `phone/.../ui/theme/` (Theme, Type = Inter, Dimens) and `ui/components/`
  (ReachabilityScaffold, RoundedCard, CardRow, Chip, PillButton, IconBadge, MetricValue, EcgStrip,
  MiniWave, DayRangeChart, WeekBars)
- **Watch:** `wear/.../ui/theme/WearTheme.kt` and `ui/components/` (ActionScreen, live waveforms,
  edge effects) plus the measuring screens

## Rules

| Topic | Phone | Watch |
|---|---|---|
| Background | `#F6F6F8` (light) / black (dark) | Always OLED black |
| Header | Reachability: large title in the top 30 % of the screen, collapsing to a 56 dp bar | Curved list header |
| Cards | 26 dp corner radius, no shadow, 20 dp padding | Full-width tonal buttons |
| Primary button | 52 dp pill | EdgeButton (Small; ExtraSmall below 210 dp) |
| Semantic colours | ECG red, blood pressure purple, heart rate pink, SpO₂ teal, temperature orange, body composition green, stress yellow | Same, dark variants |
| Measuring | — | Progress ring on the edge, counter in the middle, waveform or icon, and a hint |
| Round screens | — | Text at least 8–9 % of the width away from the edge; shorter text and smaller icons on small watches |
| Accessibility | `contentDescription` for charts and waveforms; touch targets ≥ 48 dp | Touch targets ≥ 52 dp |

## Visual review

For every UI change: run `./gradlew recordPaparazziDebug`, review the PNGs (round clipping,
contrast, layout), run `python3 tools/screenshots/sync.py`, and commit `docs/screenshots/` together
with the change.

Screenshot matrix: phone (Pixel 6) in light and dark; watch small round (192 dp) and large round
(454 px, Watch8 Classic class); the ECG PDF page at 2×. The results are browsable in
[docs/screenshots](screenshots/README.md).
