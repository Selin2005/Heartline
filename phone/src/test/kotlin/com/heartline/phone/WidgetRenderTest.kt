package com.heartline.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.widget.BpWidget
import com.heartline.phone.widget.DashboardWidget
import com.heartline.phone.widget.EcgWidget
import com.heartline.phone.widget.HeartDayWidget
import com.heartline.phone.widget.HeartRateWidget
import com.heartline.phone.widget.HeartlineWidget
import com.heartline.phone.widget.QuickMeasureWidget
import com.heartline.phone.widget.StressWidget
import com.heartline.phone.widget.WidgetModel
import com.heartline.phone.widget.WidgetSamples
import com.heartline.phone.widget.WidgetSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every widget at each of its sizes through the real RemoteViews path (Glance → RemoteViews
 * → inflated views) onto a wallpaper-like background, and writes PNGs for review:
 * build/widget-shots/, plus docs/screenshots/widgets/ and the picker preview images when
 * HEARTLINE_WIDGET_SHOTS is set.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
abstract class WidgetRenderTest(private val night: Boolean) {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Which size is the picker preview (the widget's default cell size). */
    private val previewSize = mapOf("dashboard" to 1, "quick" to 1)

    private val widgets: List<Triple<String, HeartlineWidget, List<DpSize>>> = listOf(
        Triple("dashboard", DashboardWidget(), listOf(DpSize(180.dp, 110.dp), DpSize(340.dp, 170.dp), DpSize(340.dp, 360.dp))),
        Triple("heart_rate", HeartRateWidget(), listOf(DpSize(165.dp, 165.dp), DpSize(340.dp, 165.dp))),
        Triple("ecg", EcgWidget(), listOf(DpSize(165.dp, 165.dp))),
        Triple("bp", BpWidget(), listOf(DpSize(165.dp, 165.dp))),
        Triple("stress", StressWidget(), listOf(DpSize(165.dp, 165.dp))),
        Triple("quick", QuickMeasureWidget(), listOf(DpSize(165.dp, 70.dp), DpSize(340.dp, 70.dp), DpSize(340.dp, 150.dp))),
        Triple("heart_day", HeartDayWidget(), listOf(DpSize(340.dp, 170.dp), DpSize(340.dp, 260.dp))),
    )

    @Test
    fun renderAll() = runTest {
        val out = File("build/widget-shots").apply { mkdirs() }
        val publish = System.getenv("HEARTLINE_WIDGET_SHOTS") != null
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val mode = if (night) "dark" else "light"
        for ((name, widget, sizes) in widgets) {
            for ((i, size) in sizes.withIndex()) {
                for ((variant, snapshot) in listOf("full" to WidgetSamples.snapshot, "empty" to WidgetSnapshot())) {
                    val bitmap = render(widget, size, WidgetModel(snapshot, nowSlot = 28))
                    // The widget drew something besides the background.
                    assertTrue("$name $variant", distinctColours(bitmap) > 8)
                    val file = "${name}_${i}_${variant}_$mode.png"
                    File(out, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (publish) {
                        File(root, "docs/screenshots/widgets").mkdirs()
                        File(root, "docs/screenshots/widgets/$file").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        if (i == (previewSize[name] ?: 0) && variant == "full" && !night) {
                            val preview = render(widget, size, WidgetModel(snapshot, nowSlot = 28), framed = false)
                            File(root, "phone/src/main/res/drawable-nodpi/widget_preview_$name.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    }
                }
            }
        }
    }

    /** [framed]: on a wallpaper-coloured margin for review; false gives just the widget (picker preview). */
    private suspend fun render(widget: HeartlineWidget, size: DpSize, model: WidgetModel, framed: Boolean = true): Bitmap {
        val density = context.resources.displayMetrics.density
        val result = GlanceRemoteViews().compose(context, size) { widget.Content(model) }
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        val pad = if (framed) (12 * density).toInt() else 0
        val parent = FrameLayout(context)
        val view: View = result.remoteViews.apply(context, parent)
        parent.addView(view, FrameLayout.LayoutParams(w, h))
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Soft wallpaper so the rounded widget and its transparency read like on a home screen.
        if (framed) canvas.drawColor(if (night) Color.rgb(34, 36, 48) else Color.rgb(214, 222, 240))
        canvas.translate(pad.toFloat(), pad.toFloat())
        parent.draw(canvas)
        return bitmap
    }

    private fun distinctColours(bitmap: Bitmap): Int {
        val seen = HashSet<Int>()
        for (x in 0 until bitmap.width step 7) for (y in 0 until bitmap.height step 7) seen += bitmap.getPixel(x, y)
        return seen.size
    }
}

@Config(qualifiers = "xxhdpi")
class WidgetRenderLightTest : WidgetRenderTest(night = false)

@Config(qualifiers = "night-xxhdpi")
class WidgetRenderDarkTest : WidgetRenderTest(night = true)
