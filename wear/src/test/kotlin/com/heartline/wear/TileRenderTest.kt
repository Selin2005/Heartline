package com.heartline.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.tiles.renderer.TileRenderer
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.model.EcgResult
import com.heartline.shared.profile.StressLevel
import com.heartline.wear.tile.BpTileService
import com.heartline.wear.tile.HeartTileService
import com.heartline.wear.tile.HeartlineTileService
import com.heartline.wear.tile.QuickMeasureTileService
import com.heartline.wear.tile.StressTileService
import com.heartline.wear.tile.TileColors
import com.heartline.wear.tile.TileData
import com.heartline.wear.tile.TileIcons
import com.heartline.wear.tile.TileType
import androidx.wear.protolayout.material3.Typography
import com.heartline.wear.tile.WellnessTileService
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Draws every tile with the real ProtoLayout renderer on a round 1.4" watch and writes PNGs for
 * review (build/tile-shots; docs/screenshots/wear/tiles and the tile-picker previews when
 * HEARTLINE_TILE_SHOTS is set).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w227dp-h227dp-round-xhdpi")
class TileRenderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val sample = TileData(
        heartRate = 72,
        lastEcg = "Sinus rhythm",
        lastBp = "118/76",
        bpDaysLeft = 21,
        heartMin = 52,
        heartMax = 118,
        ecgResult = EcgResult.SINUS_RHYTHM,
        bpCategory = BpCategory.NORMAL,
        spo2 = 97,
        stressScore = 38,
        stressLevel = StressLevel.MEDIUM,
        hrvMs = 42,
        temperature = "+0.2°",
    )
    private val empty = TileData(null, null, null, null)

    private val tiles: List<Pair<String, HeartlineTileService>> = listOf(
        "heart" to HeartTileService(),
        "bp" to BpTileService(),
        "quick" to QuickMeasureTileService(),
        "wellness" to WellnessTileService(),
        "stress" to StressTileService(),
    )

    @Test
    fun renderAll() {
        // Renderable stand-ins for the variable-font styles (see TileType).
        TileType.big = Typography.TITLE_LARGE
        TileType.value = Typography.TITLE_MEDIUM
        TileType.button = Typography.LABEL_MEDIUM
        val out = File("build/tile-shots").apply { mkdirs() }
        val publish = System.getenv("HEARTLINE_TILE_SHOTS") != null
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        for ((name, tile) in tiles) {
            for ((variant, data) in listOf("full" to sample, "empty" to empty)) {
                val bitmap = render(tile, data)
                assertTrue("$name $variant", distinct(bitmap) > 6)
                val file = "tile_${name}_$variant.png"
                File(out, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (publish) {
                    File(root, "docs/screenshots/wear/tiles").mkdirs()
                    File(root, "docs/screenshots/wear/tiles/$file").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (variant == "full") {
                        File(root, "wear/src/main/res/drawable-nodpi").mkdirs()
                        File(root, "wear/src/main/res/drawable-nodpi/tile_preview_$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        }
    }

    private fun render(tile: HeartlineTileService, data: TileData): Bitmap {
        val metrics = context.resources.displayMetrics
        val size = (227 * metrics.density).toInt()
        val device = DeviceParametersBuilders.DeviceParameters.Builder()
            .setScreenWidthDp(227)
            .setScreenHeightDp(227)
            .setScreenDensity(metrics.density)
            .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
            .setDevicePlatform(DeviceParametersBuilders.DEVICE_PLATFORM_WEAR_OS)
            .build()
        val element = materialScope(context, device, defaultColorScheme = TileColors.scheme) { with(tile) { layout(context, data) } }
        val layout = LayoutElementBuilders.Layout.Builder().setRoot(element).build()
        val parent = FrameLayout(context)
        val renderer = TileRenderer(context, Runnable::run) {}
        val future = renderer.inflateAsync(layout, TileIcons.resources(), parent)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        future.get(10, TimeUnit.SECONDS)
        parent.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        parent.draw(canvas)
        // Round watch mask.
        val mask = Path().apply {
            addRect(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW)
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CCW)
        }
        canvas.drawPath(mask, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 24, 28) })
        return bitmap
    }

    private fun distinct(bitmap: Bitmap): Int {
        val seen = HashSet<Int>()
        for (x in 0 until bitmap.width step 5) for (y in 0 until bitmap.height step 5) seen += bitmap.getPixel(x, y)
        return seen.size
    }
}
