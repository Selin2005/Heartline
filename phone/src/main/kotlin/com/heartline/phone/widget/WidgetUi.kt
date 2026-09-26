// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.widget

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.heartline.phone.R
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Severity

/**
 * Rounded background for an opaque colour: a tinted drawable shape, which looks the same on every
 * Android version. (The tint ignores the colour's alpha, so translucent colours use [rounded].)
 */
fun GlanceModifier.shape(@DrawableRes shape: Int, color: ColorProvider): GlanceModifier =
    background(ImageProvider(shape), contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(color))

/** Rounded background that keeps a translucent colour: the shape when opaque, else a clipped fill (Android 12+). */
fun GlanceModifier.rounded(@DrawableRes shape: Int, radius: Dp, color: ColorProvider, opaque: Boolean): GlanceModifier =
    if (opaque) shape(shape, color) else cornerRadius(radius).background(color)

@get:DrawableRes
val Metric.widgetIcon: Int
    get() = when (this) {
        Metric.ECG -> R.drawable.ic_metric_ecg
        Metric.BLOOD_PRESSURE -> R.drawable.ic_metric_bp
        Metric.HEART_RATE -> R.drawable.ic_metric_heart
        Metric.SPO2 -> R.drawable.ic_metric_spo2
        Metric.SKIN_TEMPERATURE -> R.drawable.ic_metric_temp
        Metric.BODY_COMPOSITION -> R.drawable.ic_metric_body
        Metric.STRESS -> R.drawable.ic_metric_stress
    }

/**
 * The widget's rounded background with the user's opacity; the whole widget opens [onClick].
 * On Android 12+ the corners follow the launcher's own widget radius, like One UI's widgets.
 */
@Composable
fun WidgetSurface(colors: WidgetColors, onClick: Action?, padding: Dp = 14.dp, description: String? = null, content: @Composable () -> Unit) {
    var modifier = GlanceModifier.fillMaxSize().appWidgetBackground()
    modifier = if (Build.VERSION.SDK_INT >= 31) {
        modifier.cornerRadius(android.R.dimen.system_app_widget_background_radius).background(colors.background)
    } else {
        modifier.cornerRadius(26.dp).rounded(R.drawable.widget_shape_26, 26.dp, colors.background, colors.opaque)
    }
    modifier = modifier.padding(padding)
    if (onClick != null) modifier = modifier.clickable(onClick)
    if (description != null) modifier = modifier.semantics { contentDescription = description }
    Box(modifier) { content() }
}

/** Round tinted badge with the metric icon, like the app's IconBadge. */
@Composable
fun MetricBadge(metric: Metric, colors: WidgetColors, size: Dp = 26.dp) {
    Box(
        GlanceModifier.size(size).shape(R.drawable.widget_shape_circle, colors.metricTint(metric)),
        contentAlignment = Alignment.Center,
    ) {
        MetricIcon(metric, colors, size * 0.58f)
    }
}

/** The metric's icon in its colour, without a badge (Samsung Health card headers). */
@Composable
fun MetricIcon(metric: Metric, colors: WidgetColors, size: Dp = 18.dp) {
    Image(ImageProvider(metric.widgetIcon), contentDescription = null, modifier = GlanceModifier.size(size), colorFilter = ColorFilter.tint(colors.metric(metric)))
}

/** Icon + metric name at the top-left of a card; [trailing] (a time) on the right. */
@Composable
fun MetricHeader(metric: Metric, title: String, colors: WidgetColors, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
        MetricIcon(metric, colors, 18.dp)
        Spacer(GlanceModifier.width(6.dp))
        Text(title, style = TextStyle(color = colors.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        trailing?.let { Text(it, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp), maxLines = 1) }
    }
}

/** Big number with a small unit on the same baseline row. */
@Composable
fun ValueText(value: String, unit: String?, colors: WidgetColors, size: TextUnit = 30.sp, color: ColorProvider = colors.onBackground) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Bold), maxLines = 1)
        unit?.let {
            Spacer(GlanceModifier.width(3.dp))
            Text(it, style = TextStyle(color = colors.onSurfaceVariant, fontSize = (size.value * 0.42f).coerceAtLeast(10f).sp), maxLines = 1, modifier = GlanceModifier.padding(bottom = (size.value * 0.12f).dp))
        }
    }
}

@Composable
fun Caption(text: String, colors: WidgetColors, maxLines: Int = 1, color: ColorProvider = colors.onSurfaceVariant, size: TextUnit = 11.sp) {
    Text(text, style = TextStyle(color = color, fontSize = size), maxLines = maxLines)
}

/** A small coloured status dot (normal / attention / alert). */
@Composable
fun Dot(colors: WidgetColors, severity: Severity, size: Dp = 8.dp) {
    Box(GlanceModifier.size(size).shape(R.drawable.widget_shape_circle, colors.severity(severity))) {}
}

/** A status line with a dot in front ("● Normal"). */
@Composable
fun StatusLine(text: String, severity: Severity, colors: WidgetColors, size: TextUnit = 12.sp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(colors, severity, 7.dp)
        Spacer(GlanceModifier.width(5.dp))
        Text(text, style = TextStyle(color = colors.severity(severity), fontSize = size, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/** Pill button (One UI style) that fills its row. */
@Composable
fun PillButton(text: String, onClick: Action, colors: WidgetColors, modifier: GlanceModifier = GlanceModifier.fillMaxWidth(), background: ColorProvider = colors.primary, height: Dp = 34.dp) {
    Box(
        modifier.height(height).shape(R.drawable.widget_shape_pill, background).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(color = colors.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/** Round icon button in the metric's colour (quick-measure bar). */
@Composable
fun RoundMetricButton(metric: Metric, label: String?, onClick: Action, colors: WidgetColors, size: Dp = 44.dp, modifier: GlanceModifier = GlanceModifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.clickable(onClick).semantics { contentDescription = label ?: metric.name }) {
        Box(GlanceModifier.size(size).shape(R.drawable.widget_shape_circle, colors.metricTint(metric)), contentAlignment = Alignment.Center) {
            Image(ImageProvider(metric.widgetIcon), contentDescription = null, modifier = GlanceModifier.size(size * 0.5f), colorFilter = ColorFilter.tint(colors.metric(metric)))
        }
        label?.let {
            Spacer(GlanceModifier.height(4.dp))
            Text(it, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp), maxLines = 1)
        }
    }
}

/** A tinted chip with the metric's icon and a label (One UI "quick action"). */
@Composable
fun MetricChip(metric: Metric, label: String, onClick: Action, colors: WidgetColors, modifier: GlanceModifier = GlanceModifier) {
    Row(
        modifier.height(38.dp).shape(R.drawable.widget_shape_pill, colors.metricTint(metric)).clickable(onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MetricIcon(metric, colors, 16.dp)
        Spacer(GlanceModifier.width(5.dp))
        Text(label, style = TextStyle(color = colors.onBackground, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/** A chart made of tinted mask layers (see [ChartLayer]), so it follows light and dark like the text. */
@Composable
fun Chart(layers: List<ChartLayer>, metric: Metric, colors: WidgetColors, modifier: GlanceModifier, description: String? = null) {
    Box(modifier) {
        layers.forEach { layer ->
            Image(
                ImageProvider(layer.mask),
                contentDescription = description.takeIf { layer === layers.last() },
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                colorFilter = ColorFilter.tint(colors.chart(layer.tone, metric)),
            )
        }
    }
}

/** A row of dots for the last results (oldest first), coloured by how each turned out. */
@Composable
fun ResultDots(results: List<Severity>, colors: WidgetColors, size: Dp = 8.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        results.forEachIndexed { i, s ->
            if (i > 0) Spacer(GlanceModifier.width(4.dp))
            Dot(colors, s, if (i == results.lastIndex) size + 3.dp else size)
        }
    }
}
