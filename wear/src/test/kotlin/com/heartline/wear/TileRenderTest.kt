package com.heartline.wear

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.view.PixelCopy
import android.view.ViewGroup
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
import org.robolectric.Robolectric
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
        TileType.button = Typography.TITLE_SMALL
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
        // Drawn by the hardware renderer through a real window, like on the watch: a software
        // canvas ignores the outline clipping that rounds cards and the edge button.
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val activity = controller.setup().get()
        val parent = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        activity.setContentView(parent, ViewGroup.LayoutParams(size, size))
        val renderer = TileRenderer(activity, Runnable::run) {}
        val future = renderer.inflateAsync(layout, TileIcons.resources(), parent)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        future.get(10, TimeUnit.SECONDS)
        parent.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, size, size)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        fixAutoSize(parent)
        parent.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, size, size)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        if (System.getenv("TILE_DUMP") != null) dump(parent, 0)
        val window = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
        var copied = -1
        PixelCopy.request(activity.window, window, { copied = it }, Handler(android.os.Looper.getMainLooper()))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        check(copied == PixelCopy.SUCCESS) { "pixel copy failed: $copied" }
        val at = IntArray(2).also(parent::getLocationInWindow)
        val bitmap = Bitmap.createBitmap(window, at[0], at[1], size, size).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        // Round watch mask.
        val mask = Path().apply {
            addRect(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW)
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CCW)
        }
        canvas.drawPath(mask, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 24, 28) })
        return bitmap
    }

    /** Robolectric can't auto-size text (the edge button label comes out infinitely large and invisible). */
    private fun fixAutoSize(v: View) {
        if (v is android.widget.TextView && !v.textSize.isFinite()) {
            v.setAutoSizeTextTypeWithDefaults(android.widget.TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            v.layoutParams = v.layoutParams.apply { width = ViewGroup.LayoutParams.WRAP_CONTENT; height = ViewGroup.LayoutParams.WRAP_CONTENT }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) fixAutoSize(v.getChildAt(i))
    }

    private fun dump(v: View, depth: Int) {
        val at = IntArray(2).also(v::getLocationInWindow)
        println("  ".repeat(depth) + v.javaClass.simpleName + " ${at[0]},${at[1]} ${v.width}x${v.height} vis=${v.visibility} " + ((v as? android.widget.TextView)?.let { "'${it.text}' color=${Integer.toHexString(it.currentTextColor)} size=${it.textSize}" } ?: ""))
        if (v is ViewGroup) for (i in 0 until v.childCount) dump(v.getChildAt(i), depth + 1)
    }

    private fun distinct(bitmap: Bitmap): Int {
        val seen = HashSet<Int>()
        for (x in 0 until bitmap.width step 5) for (y in 0 until bitmap.height step 5) seen += bitmap.getPixel(x, y)
        return seen.size
    }
}
