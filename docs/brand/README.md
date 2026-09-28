# Heartline icon and promotional images

| File | Size | Use |
|---|---|---|
| [heartline-icon-512.png](heartline-icon-512.png) | 512 × 512 | Google Play app icon (full square; Play rounds the corners) |
| [heartline-icon-1024.png](heartline-icon-1024.png) | 1024 × 1024 | Large icon for other stores, websites and print |
| [heartline-icon-rounded.png](heartline-icon-rounded.png) | 512 × 512 | Icon with rounded corners and a transparent background, as on a phone |
| [heartline-poster.png](heartline-poster.png) | 1280 × 640 | README header and GitHub social preview (Settings → General → Social preview) |
| [play-feature-graphic.png](play-feature-graphic.png) | 1024 × 500 | Google Play feature graphic |

<img src="heartline-icon-rounded.png" width="96" alt="Heartline icon">

All of them are drawn from the app's own launcher icon
(`phone/src/main/res/drawable/ic_launcher_*.xml`) and the published screenshots, so they stay in
step with the app. After changing the icon or the screenshots:

```bash
HEARTLINE_BRAND_ASSETS=1 ./gradlew :phone:testDebugUnitTest --tests '*BrandAssetsTest*'
python3 tools/screenshots/sync.py   # optimises the PNGs
```

The layouts are in `phone/src/test/kotlin/com/heartline/phone/BrandAssetsTest.kt`.

The Heartline name and icon are not covered by the AGPL license. Forks use their own name and icon;
see the [trademark policy](../../TRADEMARK.md).
