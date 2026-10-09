// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.heartline.phone.R
import com.heartline.phone.link.PhoneRoutes
import com.heartline.phone.link.WatchRoutes
import com.heartline.phone.ui.bp.label
import com.heartline.phone.ui.components.label
import com.heartline.shared.model.Metric

/** Phone routes the widgets open (handled by HeartlineApp's deep-link mapping). */
object WidgetRoutes {
    const val HEART_RATE = "heart_rate"
    const val ECG = "ecg"
    const val BLOOD_PRESSURE = "blood_pressure"
    val BP_CALIBRATION = PhoneRoutes.BP_CALIBRATION

    /** The phone runs the measurement and follows it (all but ECG and body composition). */
    fun measure(metric: Metric) = "measure/${metric.name}"

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

/** Launcher cell sizes (One UI phones): 1×1, 2×1, 2×2, 4×1, 4×2, 4×4. */
internal object Cells {
    val ONE = DpSize(50.dp, 50.dp)
    val TWO_BY_ONE = DpSize(110.dp, 50.dp)
    val SMALL = DpSize(110.dp, 110.dp)
    val FOUR_BY_ONE = DpSize(250.dp, 50.dp)
    val WIDE = DpSize(250.dp, 110.dp)
    val TALL = DpSize(250.dp, 250.dp)
}

private fun Context.px(dp: Float) = (dp * resources.displayMetrics.density).toInt()

private val Context.density get() = resources.displayMetrics.density

/**
 * Measures [metric]: from the phone, which follows the watch measuring; ECG and body composition
 * (fingers on the watch's keys) open on the watch, or without a watch in the app.
 */
private fun measure(metric: Metric) =
    if (metric.measuresOnPhone) measureOnPhone(WidgetRoutes.measure(metric)) else measureOnWatch(WidgetRoutes.watch(metric), WidgetRoutes.metric(metric))

/** The metric's small picture for a tile: its own chart where it has one, else the last readings' trend. */
@Composable
private fun MetricVisual(model: WidgetModel, metric: Metric, widthDp: Float, heightDp: Float) {
    val context = LocalContext.current
    val s = model.snapshot
    val colors = model.colors
    val modifier = GlanceModifier.size(widthDp.dp, heightDp.dp)
    when {
        // Range bars need some height to read; a small tile shows the trend line instead.
        metric == Metric.HEART_RATE && s.heartRate != null && heightDp >= 40f -> Chart(
            WidgetCharts.rangeBars(s.heartRate.recent(model.nowSlot), 12, context.px(widthDp), context.px(heightDp), context.density, axisLabels = false),
            metric,
            colors,
            modifier,
            context.getString(R.string.widget_hr_recent),
        )
        metric == Metric.ECG && s.ecgRecent.isNotEmpty() -> Box(modifier, contentAlignment = Alignment.CenterStart) { ResultDots(s.ecgRecent, colors) }
        metric == Metric.BLOOD_PRESSURE && s.bp != null -> Chart(
            WidgetCharts.bandBar(BpBar.bands, BpBar.position(s.bp.systolic, s.bp.diastolic), context.px(widthDp), context.px(heightDp.coerceAtMost(18f)), context.density),
            metric,
            colors,
            GlanceModifier.size(widthDp.dp, heightDp.coerceAtMost(18f).dp),
        )
        (s.history[metric]?.size ?: 0) >= 2 -> Chart(
            WidgetCharts.sparkline(s.history.getValue(metric), context.px(widthDp), context.px(heightDp), context.density),
            metric,
            colors,
            modifier,
        )
        else -> Spacer(modifier)
    }
}

/**
 * One metric as a Samsung Health–style tile, at any size from 1×1 to 4×2: the value large, its
 * status, and a small chart. The metric is chosen when the widget is added.
 */
class MetricTileWidget : HeartlineWidget() {
    override val sizes = setOf(Cells.ONE, Cells.TWO_BY_ONE, Cells.SMALL, Cells.FOUR_BY_ONE, Cells.WIDE)

    @Composable
    override fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val metric = model.style.metric ?: Metric.HEART_RATE
        val info = WidgetMetrics.info(context, model.snapshot, metric)
        val size = LocalSize.current
        when {
            size.height < Cells.SMALL.height && size.width < Cells.TWO_BY_ONE.width -> TinyTile(model, info)
            size.height < Cells.SMALL.height -> SlimTile(model, info, wide = size.width >= Cells.FOUR_BY_ONE.width)
            size.width < Cells.WIDE.width -> SquareTile(model, info)
            else -> WideTile(model, info)
        }
    }

    /** 1×1: the icon above a short value, like One UI's single-cell widgets. */
    @Composable
    private fun TinyTile(model: WidgetModel, info: MetricInfo) {
        val context = LocalContext.current
        val colors = model.colors
        WidgetSurface(colors, openApp(context, WidgetRoutes.metric(info.metric)), padding = 4.dp, description = listOfNotNull(info.title, info.value, info.unit).joinToString(" ")) {
            Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                MetricIcon(info.metric, colors, if (info.hasData) 16.dp else 24.dp)
                info.tinyValue?.let {
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        it,
                        style = TextStyle(
                            color = info.severity?.let(colors::severity) ?: colors.onBackground,
                            fontSize = if (it.length > 5) 12.sp else 17.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    /** 2×1 and 4×1: badge, value and status in a row; 4×1 adds the trend. */
    @Composable
    private fun SlimTile(model: WidgetModel, info: MetricInfo, wide: Boolean) {
        val context = LocalContext.current
        val colors = model.colors
        WidgetSurface(colors, openApp(context, WidgetRoutes.metric(info.metric)), padding = 8.dp) {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                MetricBadge(info.metric, colors, 32.dp)
                Spacer(GlanceModifier.width(8.dp))
                Column(GlanceModifier.defaultWeight()) {
                    if (info.hasData) {
                        ValueText(info.value!!, info.unit, colors, size = if (info.value.length > 7) 15.sp else 20.sp, color = info.severity?.takeIf { info.metric == Metric.ECG }?.let(colors::severity) ?: colors.onBackground)
                        Caption(if (wide) listOfNotNull(info.title, info.at).joinToString(" · ") else info.shortTitle, colors)
                    } else {
                        Text(info.title, style = TextStyle(color = colors.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                        Caption(context.getString(R.string.widget_none_yet), colors)
                    }
                }
                if (wide && info.hasData) {
                    Spacer(GlanceModifier.width(8.dp))
                    MetricVisual(model, info.metric, 80f, 30f)
                }
            }
        }
    }

    /** 2×2: header, big value, status and the metric's small chart. */
    @Composable
    private fun SquareTile(model: WidgetModel, info: MetricInfo) {
        val context = LocalContext.current
        val colors = model.colors
        WidgetSurface(colors, openApp(context, WidgetRoutes.metric(info.metric))) {
            Column(GlanceModifier.fillMaxSize()) {
                MetricHeader(info.metric, info.title, colors)
                Spacer(GlanceModifier.defaultWeight())
                if (!info.hasData) {
                    Caption(context.getString(R.string.widget_none_yet), colors, maxLines = 2, size = 12.sp)
                    Spacer(GlanceModifier.height(8.dp))
                    PillButton(context.getString(R.string.widget_measure), measure(info.metric), colors, background = colors.metric(info.metric))
                } else {
                    TileValue(info, colors)
                    Spacer(GlanceModifier.height(6.dp))
                    MetricVisual(model, info.metric, (LocalSize.current.width.value - 28f).coerceAtLeast(60f), 22f)
                    Spacer(GlanceModifier.height(4.dp))
                    info.at?.let { Caption(it, colors) }
                }
            }
        }
    }

    /** 4×2: header with time, value and status on the left, a larger chart on the right. */
    @Composable
    private fun WideTile(model: WidgetModel, info: MetricInfo) {
        val context = LocalContext.current
        val colors = model.colors
        WidgetSurface(colors, openApp(context, WidgetRoutes.metric(info.metric))) {
            Column(GlanceModifier.fillMaxSize()) {
                MetricHeader(info.metric, info.title, colors, trailing = info.at)
                Spacer(GlanceModifier.defaultWeight())
                if (!info.hasData) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Caption(context.getString(R.string.widget_none_yet), colors, size = 12.sp)
                        Spacer(GlanceModifier.defaultWeight())
                        PillButton(context.getString(R.string.widget_measure), measure(info.metric), colors, GlanceModifier.width(110.dp), background = colors.metric(info.metric))
                    }
                } else {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(GlanceModifier.defaultWeight()) { TileValue(info, colors) }
                        Spacer(GlanceModifier.width(10.dp))
                        MetricVisual(model, info.metric, (LocalSize.current.width.value * 0.45f).coerceAtMost(160f), 48f)
                    }
                }
            }
        }
    }
}

/** A tile's value line and its status below it. */
@Composable
private fun TileValue(info: MetricInfo, colors: WidgetColors) {
    if (info.metric == Metric.ECG) {
        Text(info.value!!, style = TextStyle(color = colors.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 2)
    } else {
        ValueText(info.value!!, info.unit, colors, size = if (info.value.length > 5) 26.sp else 32.sp)
    }
    info.status?.let { status ->
        val severity = info.severity
        if (severity != null) StatusLine(status, severity, colors) else Caption(status, colors, size = 12.sp)
    }
}

/**
 * 1×1 (or 2×1) button that starts one measurement on the watch, like a Samsung app shortcut.
 * The metric is chosen when the widget is added.
 */
class MeasureButtonWidget : HeartlineWidget() {
    override val sizes = setOf(Cells.ONE, Cells.TWO_BY_ONE)

    @Composable
    override fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val colors = model.colors
        val metric = model.style.metric ?: Metric.ECG
        val label = context.getString(R.string.widget_measure_metric, context.getString(metric.shortLabel))
        val size = LocalSize.current
        WidgetSurface(colors, measure(metric), padding = 4.dp, description = label) {
            if (size.width < Cells.TWO_BY_ONE.width) {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    RoundMetricButton(metric, null, measure(metric), colors, size = 40.dp)
                }
            } else {
                Row(GlanceModifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundMetricButton(metric, null, measure(metric), colors, size = 36.dp)
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text(context.getString(R.string.widget_measure), style = TextStyle(color = colors.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        Caption(context.getString(metric.title), colors)
                    }
                }
            }
        }
    }
}

/** Heart rate: current value, today's resting and range, and the last six hours as bars. */
class HeartRateWidget : HeartlineWidget() {
    override val sizes = setOf(Cells.SMALL, Cells.WIDE)

    @Composable
    override fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val colors = model.colors
        val hr = model.snapshot.heartRate
        val size = LocalSize.current
        WidgetSurface(colors, openApp(context, WidgetRoutes.HEART_RATE)) {
            Column(GlanceModifier.fillMaxSize()) {
                MetricHeader(Metric.HEART_RATE, context.getString(R.string.metric_hr), colors, trailing = hr?.at?.takeIf { size.width >= Cells.WIDE.width })
                Spacer(GlanceModifier.defaultWeight())
                if (hr == null) {
                    Caption(context.getString(R.string.widget_no_data), colors, maxLines = 2, size = 12.sp)
                    Spacer(GlanceModifier.height(8.dp))
                    PillButton(context.getString(R.string.widget_measure), measure(Metric.HEART_RATE), colors, background = colors.metric(Metric.HEART_RATE))
                } else if (size.width >= Cells.WIDE.width) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(GlanceModifier.defaultWeight()) {
                            ValueText("${hr.bpm}", context.getString(R.string.unit_bpm), colors, size = 34.sp)
                            Caption(rangeCaption(context, hr), colors, size = 12.sp)
                        }
                        Spacer(GlanceModifier.width(10.dp))
                        MetricVisual(model, Metric.HEART_RATE, 130f, 56f)
                    }
                } else {
                    ValueText("${hr.bpm}", context.getString(R.string.unit_bpm), colors, size = 32.sp)
                    Caption(rangeCaption(context, hr), colors)
                    Spacer(GlanceModifier.height(6.dp))
                    MetricVisual(model, Metric.HEART_RATE, (size.width.value - 28f).coerceAtLeast(60f), 24f)
                }
            }
        }
    }
}

private fun rangeCaption(context: Context, hr: WidgetSnapshot.HeartRate): String = listOfNotNull(
    hr.resting?.let { context.getString(R.string.widget_resting, it) },
    if (hr.min != null && hr.max != null) "${hr.min}–${hr.max}" else null,
).joinToString(" · ")

/** ECG: last result with its colour, the last few results, and a button that records on the watch. */
class EcgWidget : HeartlineWidget() {
    override val sizes = setOf(Cells.SMALL)

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
                    Caption(context.getString(R.string.widget_no_ecg), colors, maxLines = 2, size = 12.sp)
                } else {
                    Text(context.getString(ecg.result.label), style = TextStyle(color = colors.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 2)
                    Spacer(GlanceModifier.height(4.dp))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ResultDots(model.snapshot.ecgRecent, colors, 7.dp)
                        Spacer(GlanceModifier.width(6.dp))
                        Caption(ecg.at, colors)
                    }
                }
                Spacer(GlanceModifier.height(8.dp))
                PillButton(context.getString(R.string.widget_record_ecg), measure(Metric.ECG), colors, background = colors.metric(Metric.ECG))
            }
        }
    }
}

/** Blood pressure: last reading on the category bar, calibration state, and the right next step. */
class BpWidget : HeartlineWidget() {
    override val sizes = setOf(Cells.SMALL)

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
                    StatusLine(context.getString(bp.category.label), bp.category.severity(), colors)
                    Spacer(GlanceModifier.height(4.dp))
                    MetricVisual(model, Metric.BLOOD_PRESSURE, (LocalSize.current.width.value - 28f).coerceAtLeast(60f), 14f)
                } else {
                    Caption(context.getString(R.string.widget_no_bp), colors, maxLines = 2, size = 12.sp)
                    Caption(calibrationText(context, calibration), colors)
                }
                Spacer(GlanceModifier.height(8.dp))
                if (calibration is WidgetSnapshot.Calibration.Valid) {
                    PillButton(context.getString(R.string.widget_measure), measure(Metric.BLOOD_PRESSURE), colors, background = colors.metric(Metric.BLOOD_PRESSURE))
                } else {
                    PillButton(context.getString(R.string.widget_calibrate), openApp(context, WidgetRoutes.BP_CALIBRATION), colors, background = colors.metric(Metric.BLOOD_PRESSURE))
                }
            }
        }
    }
}

/** Stress: three-colour gauge with the score, level and HRV. */
class StressWidget : HeartlineWidget() {
    override val sizes = setOf(Cells.SMALL)

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
                    Chart(WidgetCharts.gauge(stress?.score, context.px(w), context.px(h), context.density), Metric.STRESS, colors, GlanceModifier.fillMaxSize())
                    Text(stress?.score?.toString() ?: "–", style = TextStyle(color = colors.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold))
                }
                Spacer(GlanceModifier.height(4.dp))
                if (stress == null) {
                    PillButton(context.getString(R.string.widget_measure), measure(Metric.STRESS), colors, background = colors.metric(Metric.STRESS))
                } else {
                    StatusLine(
                        listOfNotNull(context.getString(stress.level.label()), stress.hrvMs?.let { context.getString(R.string.widget_hrv, it) }).joinToString(" · "),
                        stress.level.severity(),
                        colors,
                    )
                    Caption(stress.at, colors)
                }
            }
        }
    }
}

/** Measurement shortcuts on the watch: round buttons when narrow, labelled chips when there is room. */
class QuickMeasureWidget : HeartlineWidget() {
    override val sizes = setOf(Cells.TWO_BY_ONE, Cells.FOUR_BY_ONE, Cells.WIDE)

    private val all = listOf(Metric.ECG, Metric.BLOOD_PRESSURE, Metric.SPO2, Metric.STRESS, Metric.SKIN_TEMPERATURE, Metric.BODY_COMPOSITION)

    @Composable
    override fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val colors = model.colors
        val size = LocalSize.current
        WidgetSurface(colors, onClick = null, padding = 8.dp, description = context.getString(R.string.widget_quick_title)) {
            when {
                // 4×2: a title and two rows of labelled chips.
                size.height >= Cells.WIDE.height -> Column(GlanceModifier.fillMaxSize()) {
                    Text(
                        context.getString(R.string.widget_quick_title),
                        style = TextStyle(color = colors.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        modifier = GlanceModifier.padding(start = 6.dp, top = 2.dp, bottom = 6.dp),
                    )
                    all.chunked(3).forEachIndexed { i, row ->
                        if (i > 0) Spacer(GlanceModifier.height(6.dp))
                        Row(GlanceModifier.fillMaxWidth()) {
                            row.forEachIndexed { j, metric ->
                                if (j > 0) Spacer(GlanceModifier.width(6.dp))
                                MetricChip(metric, context.getString(metric.shortLabel), measure(metric), colors, GlanceModifier.defaultWeight())
                            }
                        }
                    }
                }
                else -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    val metrics = if (size.width < Cells.FOUR_BY_ONE.width) all.take(3) else all
                    metrics.forEach { metric ->
                        Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                            RoundMetricButton(metric, null, measure(metric), colors, size = 38.dp, modifier = GlanceModifier.semantics { contentDescription = context.getString(metric.title) })
                        }
                    }
                }
            }
        }
    }
}

/** Today's heart-rate range chart with resting, min and max. */
class HeartDayWidget : HeartlineWidget() {
    override val sizes = setOf(Cells.WIDE, Cells.TALL)

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
                    Caption(context.getString(R.string.widget_no_data), colors, maxLines = 2, size = 12.sp)
                    Spacer(GlanceModifier.defaultWeight())
                } else {
                    val chartW = (size.width.value - 28f).coerceAtLeast(80f)
                    val chartH = (size.height.value - 28f - 22f - 34f - 14f).coerceAtLeast(40f)
                    Chart(
                        WidgetCharts.rangeBars(hr.day, 48, context.px(chartW), context.px(chartH), context.density, resting = hr.resting, xLabels = listOf("00", "06", "12", "18", "24")),
                        Metric.HEART_RATE,
                        colors,
                        GlanceModifier.fillMaxWidth().defaultWeight(),
                        context.getString(R.string.widget_hr_chart),
                    )
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
        Text(value?.toString() ?: "–", style = TextStyle(color = colors.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold))
    }
}

/**
 * Dashboard: the app's Home at a glance, as a grid of metric cells on one surface (no cards
 * within the card). 4×4 adds a greeting and measure-on-watch buttons.
 */
class DashboardWidget : HeartlineWidget() {
    override val sizes = setOf(DpSize(180.dp, 110.dp), Cells.WIDE, Cells.TALL)

    private val order = listOf(Metric.HEART_RATE, Metric.ECG, Metric.BLOOD_PRESSURE, Metric.SPO2, Metric.STRESS, Metric.SKIN_TEMPERATURE)

    @Composable
    override fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val colors = model.colors
        val size = LocalSize.current
        val infos = order.map { WidgetMetrics.info(context, model.snapshot, it) }
        WidgetSurface(colors, onClick = null, padding = 12.dp, description = context.getString(R.string.widget_dashboard_title)) {
            when {
                size.height >= Cells.TALL.height -> Column(GlanceModifier.fillMaxSize()) {
                    Row(GlanceModifier.fillMaxWidth().clickable(openApp(context, "")), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            model.snapshot.name?.let { context.getString(R.string.widget_dashboard_named, it) } ?: context.getString(R.string.widget_dashboard_title),
                            style = TextStyle(color = colors.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    infos.chunked(2).forEach { row ->
                        CellRow(row, colors, GlanceModifier.fillMaxWidth().defaultWeight())
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        listOf(Metric.ECG, Metric.BLOOD_PRESSURE, Metric.SPO2, Metric.STRESS, Metric.BODY_COMPOSITION).forEach { metric ->
                            Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
                                RoundMetricButton(metric, null, measure(metric), colors, size = 36.dp, modifier = GlanceModifier.semantics { contentDescription = context.getString(metric.title) })
                            }
                        }
                    }
                }
                size.width >= Cells.WIDE.width -> Column(GlanceModifier.fillMaxSize()) {
                    CellRow(infos.take(2), colors, GlanceModifier.fillMaxWidth().defaultWeight())
                    CellRow(infos.drop(2).take(2), colors, GlanceModifier.fillMaxWidth().defaultWeight())
                }
                else -> Column(GlanceModifier.fillMaxSize()) {
                    CellRow(infos.take(1), colors, GlanceModifier.fillMaxWidth().defaultWeight())
                    CellRow(infos.drop(2).take(1), colors, GlanceModifier.fillMaxWidth().defaultWeight())
                }
            }
        }
    }

    @Composable
    private fun CellRow(row: List<MetricInfo>, colors: WidgetColors, modifier: GlanceModifier) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            row.forEachIndexed { i, info ->
                if (i > 0) Spacer(GlanceModifier.width(8.dp))
                Cell(info, colors, GlanceModifier.defaultWeight().fillMaxHeight())
            }
        }
    }

    /** Badge, then the value with the metric's name under it. */
    @Composable
    private fun Cell(info: MetricInfo, colors: WidgetColors, modifier: GlanceModifier) {
        val context = LocalContext.current
        Row(modifier.clickable(openApp(context, WidgetRoutes.metric(info.metric))), verticalAlignment = Alignment.CenterVertically) {
            MetricBadge(info.metric, colors, 30.dp)
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                val value = if (info.metric == Metric.ECG) info.tinyValue else info.value
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (info.metric == Metric.ECG && info.severity != null) {
                        Dot(colors, info.severity, 7.dp)
                        Spacer(GlanceModifier.width(4.dp))
                    }
                    ValueText(value ?: "–", info.unit?.takeIf { value != null && (value.length) <= 4 }, colors, size = 18.sp)
                }
                Caption(info.shortTitle, colors)
            }
        }
    }
}
