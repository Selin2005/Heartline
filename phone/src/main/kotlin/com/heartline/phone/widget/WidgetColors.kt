package com.heartline.phone.widget

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.heartline.shared.design.Palette
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Severity

/**
 * The phone's wallpaper colours (Material You / One UI colour palette), light and dark, for the
 * WALLPAPER theme. Null before Android 12.
 */
data class WallpaperTones(
    val surface: Pair<Long, Long>,
    val card: Pair<Long, Long>,
    val onBackground: Pair<Long, Long>,
    val onSurfaceVariant: Pair<Long, Long>,
    val primary: Pair<Long, Long>,
) {
    companion object {
        fun of(context: Context): WallpaperTones? {
            if (Build.VERSION.SDK_INT < 31) return null
            fun c(id: Int) = context.getColor(id).toLong() and 0xFFFFFFFF
            return WallpaperTones(
                surface = c(android.R.color.system_neutral1_10) to c(android.R.color.system_neutral1_900),
                card = c(android.R.color.system_accent2_50) to c(android.R.color.system_neutral2_800),
                onBackground = c(android.R.color.system_neutral1_900) to c(android.R.color.system_neutral1_50),
                onSurfaceVariant = c(android.R.color.system_neutral2_600) to c(android.R.color.system_neutral2_300),
                primary = c(android.R.color.system_accent1_600) to c(android.R.color.system_accent1_200),
            )
        }
    }
}

/**
 * Widget colours from the app palette. SYSTEM follows the phone's light/dark mode; LIGHT and DARK
 * are fixed; WALLPAPER follows light/dark with the wallpaper's colours. The background carries the
 * user's opacity; text stays fully opaque. Metric colours are always Heartline's own, like
 * Samsung Health keeps its own on every theme.
 */
class WidgetColors(private val style: WidgetStyle, private val wallpaper: WallpaperTones? = null) {
    private fun pick(light: Long, dark: Long, alpha: Float = 1f): ColorProvider {
        val l = Color(light).copy(alpha = alpha)
        val d = Color(dark).copy(alpha = alpha)
        return when (style.theme) {
            WidgetTheme.SYSTEM, WidgetTheme.WALLPAPER -> ColorProvider(day = l, night = d)
            WidgetTheme.LIGHT -> ColorProvider(l)
            WidgetTheme.DARK -> ColorProvider(d)
        }
    }

    private fun tone(palette: Pair<Long, Long>, wall: Pair<Long, Long>?, alpha: Float = 1f): ColorProvider {
        val (l, d) = if (style.theme == WidgetTheme.WALLPAPER && wall != null) wall else palette
        return pick(l, d, alpha)
    }

    /** A fully opaque widget can use drawable shapes everywhere (see WidgetUi.shape). */
    val opaque = style.opacity >= 100

    val background = tone(Palette.Light.SURFACE to Palette.Dark.SURFACE, wallpaper?.surface, style.opacity / 100f)

    /** Inner surfaces (pills, the quick-measure chips): never transparent below 40 %. */
    val card = tone(Palette.Light.SURFACE_VARIANT to Palette.Dark.SURFACE_VARIANT, wallpaper?.card, (style.opacity / 100f).coerceAtLeast(0.4f))
    val onBackground = tone(Palette.Light.ON_BACKGROUND to Palette.Dark.ON_BACKGROUND, wallpaper?.onBackground)
    val onSurfaceVariant = tone(Palette.Light.ON_SURFACE_VARIANT to Palette.Dark.ON_SURFACE_VARIANT, wallpaper?.onSurfaceVariant)
    val primary = tone(Palette.Light.PRIMARY to Palette.Dark.PRIMARY, wallpaper?.primary)
    val onPrimary = ColorProvider(Color.White)

    /** Chart tracks and empty bars. */
    val track = tone(Palette.Light.SURFACE_VARIANT to Palette.Dark.SURFACE_VARIANT, wallpaper?.card)

    /** The widget background without the user's transparency (marker rings on charts). */
    val solidBackground = tone(Palette.Light.SURFACE to Palette.Dark.SURFACE, wallpaper?.surface)

    fun metric(metric: Metric): ColorProvider {
        val (l, d) = metricArgb(metric)
        return pick(l, d)
    }

    /** Soft pastel behind a metric icon (like the app's IconBadge), pre-blended so it is opaque. */
    fun metricTint(metric: Metric): ColorProvider {
        val (l, d) = metricArgb(metric)
        val (bl, bd) = if (style.theme == WidgetTheme.WALLPAPER && wallpaper != null) wallpaper.surface else Palette.Light.SURFACE to Palette.Dark.SURFACE
        return pick(blend(l, bl, 0.16f), blend(d, bd, 0.26f))
    }

    fun severity(severity: Severity): ColorProvider = when (severity) {
        Severity.NORMAL -> pick(Palette.Light.STATUS_NORMAL, Palette.Dark.STATUS_NORMAL)
        Severity.WARN -> pick(Palette.Light.STATUS_WARN, Palette.Dark.STATUS_WARN)
        Severity.ALERT -> pick(Palette.Light.STATUS_ALERT, Palette.Dark.STATUS_ALERT)
        Severity.NEUTRAL -> onSurfaceVariant
    }

    /** Colour for a chart layer of [metric]. */
    fun chart(tone: ChartTone, metric: Metric): ColorProvider = when (tone) {
        ChartTone.METRIC -> metric(metric)
        ChartTone.SUBTLE -> onSurfaceVariant
        ChartTone.TEXT -> onBackground
        ChartTone.TRACK -> track
        ChartTone.NORMAL -> severity(Severity.NORMAL)
        ChartTone.WARN -> severity(Severity.WARN)
        ChartTone.ALERT -> severity(Severity.ALERT)
        ChartTone.BACKGROUND -> solidBackground
    }

    companion object {
        /** [fg] at [amount] over [bg], as an opaque ARGB colour. */
        fun blend(fg: Long, bg: Long, amount: Float): Long {
            fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toFloat()
            fun mix(shift: Int) = (ch(fg, shift) * amount + ch(bg, shift) * (1 - amount)).toLong().coerceIn(0, 255) shl shift
            return 0xFF000000 or mix(16) or mix(8) or mix(0)
        }

        fun metricArgb(metric: Metric): Pair<Long, Long> = when (metric) {
            Metric.ECG -> Palette.Light.ECG to Palette.Dark.ECG
            Metric.BLOOD_PRESSURE -> Palette.Light.BP to Palette.Dark.BP
            Metric.HEART_RATE -> Palette.Light.HEART_RATE to Palette.Dark.HEART_RATE
            Metric.SPO2 -> Palette.Light.SPO2 to Palette.Dark.SPO2
            Metric.SKIN_TEMPERATURE -> Palette.Light.TEMP to Palette.Dark.TEMP
            Metric.BODY_COMPOSITION -> Palette.Light.BODY to Palette.Dark.BODY
            Metric.STRESS -> Palette.Light.STRESS to Palette.Dark.STRESS
        }
    }
}
