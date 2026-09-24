package com.heartline.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import com.heartline.shared.design.Palette
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Severity
import com.heartline.wear.R

val Inter = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Watch UI always uses the dark palette on an OLED-black background (MASTER_PLAN §3.1). */
object WearColors {
    val background = Color(Palette.Watch.BACKGROUND)
    val surface = Color(Palette.Watch.SURFACE)
    val surfaceHigh = Color(Palette.Watch.SURFACE_HIGH)
    val onSurface = Color(Palette.Dark.ON_BACKGROUND)
    val onSurfaceVariant = Color(Palette.Dark.ON_SURFACE_VARIANT)
    val primary = Color(Palette.Dark.PRIMARY)
    val ecg = Color(Palette.Dark.ECG)
    val warn = Color(Palette.Dark.STATUS_WARN)

    fun metric(metric: Metric): Color = Color(
        when (metric) {
            Metric.ECG -> Palette.Dark.ECG
            Metric.BLOOD_PRESSURE -> Palette.Dark.BP
            Metric.HEART_RATE -> Palette.Dark.HEART_RATE
            Metric.SPO2 -> Palette.Dark.SPO2
            Metric.SKIN_TEMPERATURE -> Palette.Dark.TEMP
            Metric.BODY_COMPOSITION -> Palette.Dark.BODY
            Metric.STRESS -> Palette.Dark.STRESS
        },
    )

    fun severity(severity: Severity): Color = when (severity) {
        Severity.NORMAL -> Color(Palette.Dark.STATUS_NORMAL)
        Severity.WARN -> Color(Palette.Dark.STATUS_WARN)
        Severity.ALERT -> Color(Palette.Dark.STATUS_ALERT)
        Severity.NEUTRAL -> onSurfaceVariant
    }
}

private val scheme = ColorScheme(
    primary = WearColors.primary,
    onPrimary = Color.White,
    primaryContainer = WearColors.primary,
    onPrimaryContainer = Color.White,
    background = WearColors.background,
    onBackground = WearColors.onSurface,
    onSurface = WearColors.onSurface,
    onSurfaceVariant = WearColors.onSurfaceVariant,
    surfaceContainerLow = WearColors.surface,
    surfaceContainer = WearColors.surface,
    surfaceContainerHigh = WearColors.surfaceHigh,
    error = Color(Palette.Dark.STATUS_ALERT),
)

private fun style(size: Int, line: Int, weight: FontWeight) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

private val typography = Typography(
    defaultFontFamily = Inter,
).let {
    it.copy(
        displayLarge = style(40, 44, FontWeight.Light),
        displayMedium = style(34, 38, FontWeight.Light),
        titleLarge = style(18, 22, FontWeight.SemiBold),
        titleMedium = style(16, 20, FontWeight.SemiBold),
        titleSmall = style(14, 18, FontWeight.SemiBold),
        bodyLarge = style(15, 20, FontWeight.Normal),
        bodyMedium = style(14, 18, FontWeight.Normal),
        bodySmall = style(12, 16, FontWeight.Normal),
        labelLarge = style(15, 18, FontWeight.SemiBold),
        labelMedium = style(14, 17, FontWeight.Medium),
        labelSmall = style(12, 14, FontWeight.Medium),
    )
}

@Composable
fun HeartlineWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
