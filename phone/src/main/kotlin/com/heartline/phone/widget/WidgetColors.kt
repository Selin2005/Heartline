package com.heartline.phone.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.heartline.shared.design.Palette
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Severity

/**
 * Widget colours from the app palette. SYSTEM follows the phone's light/dark mode; LIGHT and DARK
 * are fixed. The background carries the user's opacity; text and cards stay fully opaque.
 */
class WidgetColors(private val style: WidgetStyle) {
    private fun pick(light: Long, dark: Long, alpha: Float = 1f): ColorProvider {
        val l = Color(light).copy(alpha = alpha)
        val d = Color(dark).copy(alpha = alpha)
        return when (style.theme) {
            WidgetTheme.SYSTEM -> ColorProvider(day = l, night = d)
            WidgetTheme.LIGHT -> ColorProvider(l)
            WidgetTheme.DARK -> ColorProvider(d)
        }
    }

    /** A fully opaque widget can use drawable shapes everywhere (see WidgetUi.shape). */
    val opaque = style.opacity >= 100

    val background = pick(Palette.Light.SURFACE, Palette.Dark.SURFACE, style.opacity / 100f)

    /** Inner cards: a touch darker/lighter than the widget, and never transparent below 40 %. */
    val card = pick(Palette.Light.SURFACE_VARIANT, Palette.Dark.SURFACE_VARIANT, (style.opacity / 100f).coerceAtLeast(0.4f))
    val onBackground = pick(Palette.Light.ON_BACKGROUND, Palette.Dark.ON_BACKGROUND)
    val onSurfaceVariant = pick(Palette.Light.ON_SURFACE_VARIANT, Palette.Dark.ON_SURFACE_VARIANT)
    val primary = pick(Palette.Light.PRIMARY, Palette.Dark.PRIMARY)
    val onPrimary = ColorProvider(Color.White)

    fun metric(metric: Metric): ColorProvider = when (metric) {
        Metric.ECG -> pick(Palette.Light.ECG, Palette.Dark.ECG)
        Metric.BLOOD_PRESSURE -> pick(Palette.Light.BP, Palette.Dark.BP)
        Metric.HEART_RATE -> pick(Palette.Light.HEART_RATE, Palette.Dark.HEART_RATE)
        Metric.SPO2 -> pick(Palette.Light.SPO2, Palette.Dark.SPO2)
        Metric.SKIN_TEMPERATURE -> pick(Palette.Light.TEMP, Palette.Dark.TEMP)
        Metric.BODY_COMPOSITION -> pick(Palette.Light.BODY, Palette.Dark.BODY)
        Metric.STRESS -> pick(Palette.Light.STRESS, Palette.Dark.STRESS)
    }

    /** Soft pastel behind a metric icon (like the app's IconBadge), pre-blended so it is opaque. */
    fun metricTint(metric: Metric): ColorProvider {
        val (l, d) = metricArgb(metric)
        return pick(blend(l, Palette.Light.SURFACE_VARIANT, 0.2f), blend(d, Palette.Dark.SURFACE_VARIANT, 0.28f))
    }

    fun severity(severity: Severity): ColorProvider = when (severity) {
        Severity.NORMAL -> pick(Palette.Light.STATUS_NORMAL, Palette.Dark.STATUS_NORMAL)
        Severity.WARN -> pick(Palette.Light.STATUS_WARN, Palette.Dark.STATUS_WARN)
        Severity.ALERT -> pick(Palette.Light.STATUS_ALERT, Palette.Dark.STATUS_ALERT)
        Severity.NEUTRAL -> onSurfaceVariant
    }

    companion object {
        /** [fg] at [amount] over [bg], as an opaque ARGB colour. */
        fun blend(fg: Long, bg: Long, amount: Float): Long {
            fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toFloat()
            fun mix(shift: Int) = (ch(fg, shift) * amount + ch(bg, shift) * (1 - amount)).toLong().coerceIn(0, 255) shl shift
            return 0xFF000000 or mix(16) or mix(8) or mix(0)
        }

        /** Metric colour usable on light and dark alike (bitmaps can't switch with the theme). */
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
