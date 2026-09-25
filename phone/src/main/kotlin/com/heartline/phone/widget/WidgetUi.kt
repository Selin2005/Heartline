package com.heartline.phone.widget

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
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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

/** The widget's rounded background with the user's opacity; the whole widget opens [onClick]. */
@Composable
fun WidgetSurface(colors: WidgetColors, onClick: Action?, padding: Dp = 14.dp, description: String? = null, content: @Composable () -> Unit) {
    var modifier = GlanceModifier.fillMaxSize()
        .appWidgetBackground()
        .cornerRadius(26.dp)
        .rounded(R.drawable.widget_shape_26, 26.dp, colors.background, colors.opaque)
        .padding(padding)
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
        Image(ImageProvider(metric.widgetIcon), contentDescription = null, modifier = GlanceModifier.size(size * 0.6f), colorFilter = ColorFilter.tint(colors.metric(metric)))
    }
}

/** Badge + metric name, the header of every card. */
@Composable
fun MetricHeader(metric: Metric, title: String, colors: WidgetColors, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
        MetricBadge(metric, colors, 22.dp)
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
            Text(it, style = TextStyle(color = colors.onSurfaceVariant, fontSize = (size.value * 0.42f).sp), maxLines = 1, modifier = GlanceModifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
fun Caption(text: String, colors: WidgetColors, maxLines: Int = 1, color: ColorProvider = colors.onSurfaceVariant) {
    Text(text, style = TextStyle(color = color, fontSize = 11.sp), maxLines = maxLines)
}

/** Pill button (One UI style) that fills its row. */
@Composable
fun PillButton(text: String, onClick: Action, colors: WidgetColors, modifier: GlanceModifier = GlanceModifier.fillMaxWidth(), background: ColorProvider = colors.primary) {
    Box(
        modifier.height(34.dp).shape(R.drawable.widget_shape_pill, background).clickable(onClick),
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

/** Card inside a larger widget. */
@Composable
fun WidgetCard(colors: WidgetColors, onClick: Action?, modifier: GlanceModifier = GlanceModifier, content: @Composable () -> Unit) {
    var m = modifier.rounded(R.drawable.widget_shape_16, 16.dp, colors.card, colors.opaque).padding(10.dp)
    if (onClick != null) m = m.clickable(onClick)
    Column(m) { content() }
}
