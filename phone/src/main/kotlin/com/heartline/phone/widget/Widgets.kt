package com.heartline.phone.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.heartline.phone.R
import com.heartline.phone.link.PhoneRoutes
import com.heartline.phone.link.WatchRoutes
import com.heartline.phone.ui.bp.label
import com.heartline.phone.ui.components.label
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Severity
import com.heartline.shared.profile.StressLevel

/** Phone routes the widgets open (handled by HeartlineApp's deep-link mapping). */
object WidgetRoutes {
    const val HEART_RATE = "heart_rate"
    const val ECG = "ecg"
    const val BLOOD_PRESSURE = "blood_pressure"
    val BP_CALIBRATION = PhoneRoutes.BP_CALIBRATION

    fun metric(metric: Metric) = when (metric) {
        Metric.HEART_RATE -> HEART_RATE
        Metric.ECG -> ECG
        Metric.BLOOD_PRESSURE -> BLOOD_PRESSURE
        else -> "metric/${metric.name}"
    }

    fun watch(metric: Metric) = when (metric) {
        Metric.HEART_RATE -> WatchRoutes.HEART_RATE
        Metric.ECG -> WatchRoutes.ECG
        Metric.BLOOD_PRESSURE -> WatchRoutes.BLOOD_PRESSURE
        else -> WatchRoutes.quick(metric)
    }
}

private val SMALL = DpSize(110.dp, 110.dp)
private val WIDE = DpSize(250.dp, 110.dp)
private val TALL = DpSize(250.dp, 250.dp)

private fun Context.bitmapPx(dp: Float) = (dp * resources.displayMetrics.density).toInt()

private fun BpCategory.severity() = when (this) {
    BpCategory.NORMAL -> Severity.NORMAL
    BpCategory.ELEVATED, BpCategory.HIGH_STAGE_1 -> Severity.WARN
    BpCategory.HIGH_STAGE_2, BpCategory.CRISIS -> Severity.ALERT
}

/** Short names for the round buttons, where full names don't fit. */
private val Metric.shortLabel: Int
    get() = when (this) {
        Metric.ECG -> R.string.widget_short_ecg
        Metric.BLOOD_PRESSURE -> R.string.widget_short_bp
        Metric.HEART_RATE -> R.string.widget_short_hr
        Metric.SPO2 -> R.string.widget_short_spo2
        Metric.SKIN_TEMPERATURE -> R.string.widget_short_temp
        Metric.BODY_COMPOSITION -> R.string.widget_short_body
        Metric.STRESS -> R.string.widget_short_stress
    }

private fun StressLevel.severity() = when (this) {
    StressLevel.LOW -> Severity.NORMAL
    StressLevel.MEDIUM -> Severity.WARN
    StressLevel.HIGH -> Severity.ALERT
}

private fun StressLevel.label() = when (this) {
    StressLevel.LOW -> R.string.widget_stress_low
    StressLevel.MEDIUM -> R.string.widget_stress_medium
    StressLevel.HIGH -> R.string.widget_stress_high
}

@Composable
private fun Dot(colors: WidgetColors, severity: Severity) {
    Box(GlanceModifier.size(10.dp).shape(R.drawable.widget_shape_circle, colors.severity(severity))) {}
}

/** Heart rate: current value, today's resting and range; wide adds the last six hours. */
class HeartRateWidget : HeartlineWidget() {
    override val sizes = setOf(SMALL, WIDE)

    @Composable
    override fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val colors = model.colors
        val hr = model.snapshot.heartRate
        WidgetSurface(colors, openApp(context, WidgetRoutes.HEART_RATE)) {
            Column(GlanceModifier.fillMaxSize()) {
                MetricHeader(Metric.HEART_RATE, context.getString(R.string.metric_hr), colors)
                Spacer(GlanceModifier.defaultWeight())
                if (hr == null) {
                    Caption(context.getString(R.string.widget_no_data), colors, maxLines = 2)
                    Spacer(GlanceModifier.height(8.dp))
                    PillButton(context.getString(R.string.widget_measure), measureOnWatch(WatchRoutes.HEART_RATE, WidgetRoutes.HEART_RATE), colors)
                } else {
                    Row(verticalAlignment = Alignment.Bottom, modifier = GlanceModifier.fillMaxWidth()) {
                        Column(GlanceModifier.defaultWeight()) {
                            ValueText("${hr.bpm}", context.getString(R.string.unit_bpm), colors, size = 34.sp)
                            Caption(rangeCaption(context, hr), colors)
                            Caption(hr.at, colors)
                        }
                        if (LocalSize.current.width >= WIDE.width) {
                            Spacer(GlanceModifier.width(10.dp))
                            val w = 120f
                            val h = 56f
                            val bitmap = WidgetCharts.rangeBars(
                                hr.recent(model.nowSlot),
                                12,
                                context.bitmapPx(w),
                                context.bitmapPx(h),
                                WidgetColors.metricArgb(Metric.HEART_RATE).first.toInt(),
                                context.resources.displayMetrics.density,
                                axisLabels = false,
                            )
                            Image(ImageProvider(bitmap), context.getString(R.string.widget_hr_recent), GlanceModifier.size(w.dp, h.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun rangeCaption(context: Context, hr: WidgetSnapshot.HeartRate): String = listOfNotNull(
    hr.resting?.let { context.getString(R.string.widget_resting, it) },
    if (hr.min != null && hr.max != null) "${hr.min}–${hr.max}" else null,
).joinToString(" · ")

/** ECG: last result with its colour, and a button that starts an ECG on the watch. */
class EcgWidget : HeartlineWidget() {
    override val sizes = setOf(SMALL)

    @Composable
    override fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val colors = model.colors
        val ecg = model.snapshot.ecg
        WidgetSurface(colors, openApp(context, WidgetRoutes.ECG)) {
            Column(GlanceModifier.fillMaxSize()) {
                MetricHeader(Metric.ECG, context.getString(R.string.metric_ecg), colors)
                Spacer(GlanceModifier.defaultWeight())
                if (ecg == null) {
                    Caption(context.getString(R.string.widget_no_ecg), colors, maxLines = 2)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(colors, ecg.result.severity)
                        Spacer(GlanceModifier.width(6.dp))
                        Text(
                            context.getString(ecg.result.label),
                            style = TextStyle(color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                            maxLines = 2,
                        )
                    }
                    Caption(listOfNotNull(ecg.at, ecg.bpm?.let { context.getString(R.string.widget_bpm_value, it) }).joinToString(" · "), colors)
                }
                Spacer(GlanceModifier.height(8.dp))
                PillButton(context.getString(R.string.widget_record_ecg), measureOnWatch(WatchRoutes.ECG, WidgetRoutes.ECG), colors, background = colors.metric(Metric.ECG))
            }
        }
    }
}

/** Blood pressure: last reading and category, calibration state, and the right next step. */
class BpWidget : HeartlineWidget() {
    override val sizes = setOf(SMALL)

    @Composable
    override fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val colors = model.colors
        val bp = model.snapshot.bp
        val calibration = model.snapshot.calibration
        WidgetSurface(colors, openApp(context, WidgetRoutes.BLOOD_PRESSURE)) {
            Column(GlanceModifier.fillMaxSize()) {
                MetricHeader(Metric.BLOOD_PRESSURE, context.getString(R.string.metric_bp), colors)
                Spacer(GlanceModifier.defaultWeight())
                if (bp != null) {
                    ValueText("${bp.systolic}/${bp.diastolic}", context.getString(R.string.unit_mmhg), colors, size = 26.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(colors, bp.category.severity())
                        Spacer(GlanceModifier.width(5.dp))
                        Caption(context.getString(bp.category.label), colors)
                    }
                } else {
                    Caption(context.getString(R.string.widget_no_bp), colors, maxLines = 2)
                }
                Caption(calibrationText(context, calibration), colors)
                Spacer(GlanceModifier.height(8.dp))
                if (calibration is WidgetSnapshot.Calibration.Valid) {
                    PillButton(
                        context.getString(R.string.widget_measure),
                        measureOnWatch(WatchRoutes.BLOOD_PRESSURE, WidgetRoutes.BLOOD_PRESSURE),
                        colors,
                        background = colors.metric(Metric.BLOOD_PRESSURE),
                    )
                } else {
                    PillButton(context.getString(R.string.widget_calibrate), openApp(context, WidgetRoutes.BP_CALIBRATION), colors, background = colors.metric(Metric.BLOOD_PRESSURE))
                }
            }
        }
    }
}

private fun calibrationText(context: Context, calibration: WidgetSnapshot.Calibration) = when (calibration) {
    is WidgetSnapshot.Calibration.Valid -> context.getString(R.string.widget_calibration_days, calibration.daysLeft)
    WidgetSnapshot.Calibration.Expired -> context.getString(R.string.widget_calibration_expired)
    WidgetSnapshot.Calibration.None -> context.getString(R.string.widget_calibration_needed)
}

/** Stress: three-colour gauge with the score, level and HRV. */
class StressWidget : HeartlineWidget() {
    override val sizes = setOf(SMALL)

    @Composable
    override fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val colors = model.colors
        val stress = model.snapshot.stress
        WidgetSurface(colors, openApp(context, WidgetRoutes.metric(Metric.STRESS))) {
            Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                MetricHeader(Metric.STRESS, context.getString(R.string.metric_stress), colors)
                Spacer(GlanceModifier.defaultWeight())
                val w = 120f
                val h = 64f
                Box(GlanceModifier.size(w.dp, h.dp), contentAlignment = Alignment.BottomCenter) {
                    Image(
                        ImageProvider(WidgetCharts.gauge(stress?.score, context.bitmapPx(w), context.bitmapPx(h), context.resources.displayMetrics.density)),
                        contentDescription = null,
                        modifier = GlanceModifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        stress?.score?.toString() ?: "–",
                        style = TextStyle(color = colors.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                    )
                }
                Spacer(GlanceModifier.height(4.dp))
                if (stress == null) {
                    PillButton(
                        context.getString(R.string.widget_measure),
                        measureOnWatch(WidgetRoutes.watch(Metric.STRESS), WidgetRoutes.metric(Metric.STRESS)),
                        colors,
                        background = colors.metric(Metric.STRESS),
                    )
                } else {
                    Caption(
                        listOfNotNull(context.getString(stress.level.label()), stress.hrvMs?.let { context.getString(R.string.widget_hrv, it) }).joinToString(" · "),
                        colors,
                        color = colors.severity(stress.level.severity()),
                    )
                    Caption(stress.at, colors)
                }
            }
        }
    }
}

/** A row of round buttons, each opening that measurement straight on the watch. */
class QuickMeasureWidget : HeartlineWidget() {
    override val sizes = setOf(DpSize(110.dp, 50.dp), DpSize(250.dp, 50.dp), WIDE)

    private val all = listOf(Metric.ECG, Metric.BLOOD_PRESSURE, Metric.SPO2, Metric.STRESS, Metric.SKIN_TEMPERATURE, Metric.BODY_COMPOSITION)

    @Composable
    override fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val colors = model.colors
        val size = LocalSize.current
        val metrics = if (size.width < 250.dp) all.take(3) else all
        val labelled = size.height >= 110.dp
        WidgetSurface(colors, onClick = null, padding = 8.dp, description = context.getString(R.string.widget_quick_title)) {
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (labelled) {
                    Text(
                        context.getString(R.string.widget_quick_title),
                        style = TextStyle(color = colors.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        modifier = GlanceModifier.padding(start = 6.dp, bottom = 8.dp),
                    )
                }
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    metrics.forEach { metric ->
                        Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                            RoundMetricButton(
                                metric,
                                context.getString(metric.shortLabel).takeIf { labelled },
                                measureOnWatch(WidgetRoutes.watch(metric), WidgetRoutes.metric(metric)),
                                colors,
                                size = if (labelled) 44.dp else 38.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Today's heart-rate range chart with resting, min and max. */
class HeartDayWidget : HeartlineWidget() {
    override val sizes = setOf(WIDE, TALL)

    @Composable
    override fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val colors = model.colors
        val hr = model.snapshot.heartRate
        val size = LocalSize.current
        WidgetSurface(colors, openApp(context, WidgetRoutes.HEART_RATE)) {
            Column(GlanceModifier.fillMaxSize()) {
                MetricHeader(
                    Metric.HEART_RATE,
                    context.getString(R.string.widget_hr_today),
                    colors,
                    trailing = hr?.let { if (it.min != null && it.max != null) "${it.min}–${it.max} ${context.getString(R.string.unit_bpm)}" else null },
                )
                Spacer(GlanceModifier.height(6.dp))
                if (hr == null) {
                    Spacer(GlanceModifier.defaultWeight())
                    Caption(context.getString(R.string.widget_no_data), colors, maxLines = 2)
                    Spacer(GlanceModifier.defaultWeight())
                } else {
                    val chartW = (size.width.value - 28f).coerceAtLeast(80f)
                    val chartH = (size.height.value - 28f - 22f - 30f - 14f).coerceAtLeast(40f)
                    val bitmap = WidgetCharts.rangeBars(
                        hr.day,
                        48,
                        context.bitmapPx(chartW),
                        context.bitmapPx(chartH),
                        WidgetColors.metricArgb(Metric.HEART_RATE).first.toInt(),
                        context.resources.displayMetrics.density,
                        resting = hr.resting,
                        xLabels = listOf("00", "06", "12", "18", "24"),
                    )
                    Image(ImageProvider(bitmap), context.getString(R.string.widget_hr_chart), GlanceModifier.fillMaxWidth().defaultWeight(), contentScale = ContentScale.FillBounds)
                    Spacer(GlanceModifier.height(6.dp))
                    Row(GlanceModifier.fillMaxWidth()) {
                        Stat(context.getString(R.string.hr_resting), hr.resting, colors, GlanceModifier.defaultWeight())
                        Stat(context.getString(R.string.hr_min), hr.min, colors, GlanceModifier.defaultWeight())
                        Stat(context.getString(R.string.hr_max), hr.max, colors, GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int?, colors: WidgetColors, modifier: GlanceModifier) {
    Column(modifier) {
        Caption(label, colors)
        Text(value?.toString() ?: "–", style = TextStyle(color = colors.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

/** Dashboard: the app's Home at a glance; 4×2 shows four cards, 4×4 six cards plus measure-on-watch buttons. */
class DashboardWidget : HeartlineWidget() {
    override val sizes = setOf(DpSize(180.dp, 110.dp), WIDE, TALL)

    @Composable
    override fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val colors = model.colors
        val size = LocalSize.current
        val cards = cards(context, model.snapshot)
        WidgetSurface(colors, onClick = null, padding = 10.dp, description = context.getString(R.string.widget_dashboard_title)) {
            when {
                size.height >= TALL.height -> Column(GlanceModifier.fillMaxSize()) {
                    cards.take(6).chunked(2).forEach { row ->
                        CardRow(row, colors, GlanceModifier.fillMaxWidth().defaultWeight())
                        Spacer(GlanceModifier.height(8.dp))
                    }
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        listOf(Metric.ECG, Metric.BLOOD_PRESSURE, Metric.SPO2, Metric.STRESS, Metric.BODY_COMPOSITION).forEach { metric ->
                            Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                                RoundMetricButton(metric, null, measureOnWatch(WidgetRoutes.watch(metric), WidgetRoutes.metric(metric)), colors, size = 36.dp)
                            }
                        }
                    }
                }
                size.width >= WIDE.width -> Column(GlanceModifier.fillMaxSize()) {
                    CardRow(cards.take(2), colors, GlanceModifier.fillMaxWidth().defaultWeight(), compact = true)
                    Spacer(GlanceModifier.height(8.dp))
                    CardRow(cards.drop(2).take(2), colors, GlanceModifier.fillMaxWidth().defaultWeight(), compact = true)
                }
                else -> CardRow(cards.take(2), colors, GlanceModifier.fillMaxSize(), compact = true, iconOnly = true)
            }
        }
    }

    private data class CardData(val metric: Metric, val title: String, val value: String, val unit: String?, val caption: String, val severity: Severity? = null)

    private fun cards(context: Context, s: WidgetSnapshot): List<CardData> {
        val none = context.getString(R.string.widget_none_yet)
        return listOf(
            CardData(Metric.HEART_RATE, context.getString(R.string.metric_hr), s.heartRate?.bpm?.toString() ?: "–", context.getString(R.string.unit_bpm), s.heartRate?.at ?: none),
            s.ecg?.let { CardData(Metric.ECG, context.getString(R.string.metric_ecg), context.getString(it.result.label), null, it.at, it.result.severity) }
                ?: CardData(Metric.ECG, context.getString(R.string.metric_ecg), "–", null, none),
            CardData(
                Metric.BLOOD_PRESSURE,
                context.getString(R.string.metric_bp),
                s.bp?.let { "${it.systolic}/${it.diastolic}" } ?: "–",
                context.getString(R.string.unit_mmhg),
                s.bp?.at ?: calibrationText(context, s.calibration),
            ),
            CardData(Metric.SPO2, context.getString(R.string.metric_spo2), s.spo2?.value ?: "–", s.spo2?.unit, s.spo2?.at ?: none),
            CardData(Metric.STRESS, context.getString(R.string.metric_stress), s.stress?.score?.toString() ?: "–", null, s.stress?.at ?: none),
            CardData(Metric.SKIN_TEMPERATURE, context.getString(R.string.metric_skin_temp), s.temperature?.value ?: "–", s.temperature?.unit, s.temperature?.at ?: none),
            CardData(Metric.BODY_COMPOSITION, context.getString(R.string.metric_body), s.body?.value ?: "–", s.body?.unit, s.body?.at ?: none),
        )
    }

    @Composable
    private fun CardRow(row: List<CardData>, colors: WidgetColors, modifier: GlanceModifier, compact: Boolean = false, iconOnly: Boolean = false) {
        val context = LocalContext.current
        Row(modifier) {
            row.forEachIndexed { i, card ->
                WidgetCard(colors, openApp(context, WidgetRoutes.metric(card.metric)), GlanceModifier.defaultWeight().fillMaxHeight()) {
                    // Compact cards (4×2, 2×2) drop the time; the smallest keeps only the icon as header.
                    if (iconOnly) MetricBadge(card.metric, colors, 22.dp) else MetricHeader(card.metric, card.title, colors)
                    Spacer(GlanceModifier.defaultWeight())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        card.severity?.let {
                            Dot(colors, it)
                            Spacer(GlanceModifier.width(5.dp))
                        }
                        ValueText(card.value, card.unit, colors, size = if (card.severity != null) 15.sp else 22.sp)
                    }
                    if (!compact) Caption(card.caption, colors)
                }
                if (i < row.lastIndex) Spacer(GlanceModifier.width(8.dp))
            }
            // Keep a lone last card half-width.
            if (row.size == 1) {
                Spacer(GlanceModifier.width(8.dp))
                Box(GlanceModifier.defaultWeight()) {}
            }
        }
    }
}
