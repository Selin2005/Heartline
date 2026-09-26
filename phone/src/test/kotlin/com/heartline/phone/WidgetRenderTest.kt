// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewGroup
import android.graphics.Bitmap
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
import com.heartline.phone.widget.MeasureButtonWidget
import com.heartline.phone.widget.MetricTileWidget
import com.heartline.phone.widget.WallpaperTones
import com.heartline.phone.widget.WidgetModel
import com.heartline.phone.widget.WidgetStyle
import com.heartline.phone.widget.WidgetTheme
import com.heartline.shared.model.Metric
import com.heartline.phone.widget.WidgetSamples
import com.heartline.phone.widget.WidgetSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
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

    private data class Shot(val name: String, val widget: HeartlineWidget, val sizes: List<DpSize>, val style: WidgetStyle = WidgetStyle(), val preview: Pair<String, Int>? = null)

    private val one = DpSize(72.dp, 72.dp)
    private val twoByOne = DpSize(165.dp, 70.dp)
    private val square = DpSize(165.dp, 165.dp)
    private val fourByOne = DpSize(340.dp, 70.dp)
    private val wide = DpSize(340.dp, 165.dp)

    private val shots: List<Shot> = listOf(
        Shot("dashboard", DashboardWidget(), listOf(DpSize(180.dp, 110.dp), DpSize(340.dp, 170.dp), DpSize(340.dp, 360.dp)), preview = "dashboard" to 1),
        Shot("heart_rate", HeartRateWidget(), listOf(square, wide), preview = "heart_rate" to 0),
        Shot("ecg", EcgWidget(), listOf(square), preview = "ecg" to 0),
        Shot("bp", BpWidget(), listOf(square), preview = "bp" to 0),
        Shot("stress", StressWidget(), listOf(square), preview = "stress" to 0),
        Shot("quick", QuickMeasureWidget(), listOf(twoByOne, fourByOne, DpSize(340.dp, 150.dp)), preview = "quick" to 1),
        Shot("heart_day", HeartDayWidget(), listOf(DpSize(340.dp, 170.dp), DpSize(340.dp, 260.dp)), preview = "heart_day" to 0),
        Shot("tile_hr", MetricTileWidget(), listOf(one, twoByOne, square, fourByOne, wide), WidgetStyle(metric = Metric.HEART_RATE), preview = "metric_tile_small" to 0),
        Shot("tile_bp", MetricTileWidget(), listOf(one, twoByOne, square, wide), WidgetStyle(metric = Metric.BLOOD_PRESSURE), preview = "metric_tile" to 2),
        Shot("tile_ecg", MetricTileWidget(), listOf(one, twoByOne, square, wide), WidgetStyle(metric = Metric.ECG)),
        Shot("tile_spo2", MetricTileWidget(), listOf(one, twoByOne, square, wide), WidgetStyle(metric = Metric.SPO2), preview = "metric_tile_slim" to 1),
        Shot("tile_body", MetricTileWidget(), listOf(one, twoByOne, square, wide), WidgetStyle(metric = Metric.BODY_COMPOSITION)),
        Shot("tile_stress", MetricTileWidget(), listOf(square), WidgetStyle(metric = Metric.STRESS)),
        Shot("tile_hr_wallpaper", MetricTileWidget(), listOf(square), WidgetStyle(theme = WidgetTheme.WALLPAPER, metric = Metric.HEART_RATE)),
        Shot("measure", MeasureButtonWidget(), listOf(one, twoByOne), WidgetStyle(metric = Metric.ECG), preview = "measure" to 0),
    )

    @Test
    fun renderAll() = runTest {
        val out = File("build/widget-shots").apply { mkdirs() }
        val publish = System.getenv("HEARTLINE_WIDGET_SHOTS") != null
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val mode = if (night) "dark" else "light"
        val wallpaper = WallpaperTones.of(context)
        for (shot in shots) {
            for ((i, size) in shot.sizes.withIndex()) {
                for ((variant, snapshot) in listOf("full" to WidgetSamples.snapshot, "empty" to WidgetSnapshot())) {
                    val model = WidgetModel(snapshot, shot.style, nowSlot = 28, wallpaper = wallpaper)
                    val bitmap = render(shot.widget, size, model)
                    // The widget drew something besides the background.
                    assertTrue("${shot.name} $variant", distinctColours(bitmap) > 4)
                    val file = "${shot.name}_${i}_${variant}_$mode.png"
                    File(out, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (publish) {
                        File(root, "docs/screenshots/widgets").mkdirs()
                        File(root, "docs/screenshots/widgets/$file").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val preview = shot.preview
                        if (preview != null && i == preview.second && variant == "full" && !night) {
                            val image = render(shot.widget, size, model, framed = false)
                            File(root, "phone/src/main/res/drawable-nodpi/widget_preview_${preview.first}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        // Drawn by the hardware renderer through a real window, as on the phone: a software canvas
        // ignores the outline clipping that rounds widgets (cornerRadius) on Android 12+.
        System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val activity = controller.setup().get()
        val frame = FrameLayout(activity).apply {
            // Soft wallpaper so the rounded widget and its transparency read like on a home screen.
            setBackgroundColor(if (!framed) Color.TRANSPARENT else if (night) Color.rgb(34, 36, 48) else Color.rgb(214, 222, 240))
            setPadding(pad, pad, pad, pad)
        }
        val holder = FrameLayout(activity)
        frame.addView(holder, FrameLayout.LayoutParams(w, h))
        val view: View = result.remoteViews.apply(activity, holder)
        holder.addView(view, FrameLayout.LayoutParams(w, h))
        activity.setContentView(frame, ViewGroup.LayoutParams(w + 2 * pad, h + 2 * pad))
        shadowOf(Looper.getMainLooper()).idle()
        val window = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
        var copied = -1
        PixelCopy.request(activity.window, window, { copied = it }, Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        check(copied == PixelCopy.SUCCESS) { "pixel copy failed: $copied" }
        val at = IntArray(2).also(frame::getLocationInWindow)
        val bitmap = Bitmap.createBitmap(window, at[0], at[1], w + 2 * pad, h + 2 * pad)
        controller.pause().stop().destroy()
        return bitmap
    }

    private fun distinctColours(bitmap: Bitmap): Int {
        val seen = HashSet<Int>()
        for (x in 0 until bitmap.width step 7) for (y in 0 until bitmap.height step 7) seen += bitmap.getPixel(x, y)
        return seen.size
    }
}

@Config(qualifiers = "w480dp-h900dp-xxhdpi")
class WidgetRenderLightTest : WidgetRenderTest(night = false)

@Config(qualifiers = "w480dp-h900dp-night-xxhdpi")
class WidgetRenderDarkTest : WidgetRenderTest(night = true)
