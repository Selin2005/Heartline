package com.heartline.wear.tile

import android.content.Context
import com.heartline.shared.design.Palette
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Severity
import com.heartline.wear.R
import com.heartline.wear.ui.screens.label
import java.util.Locale

/** The Heartline cards that sit in the watch's tile stack (one service each). */
enum class CardKind { HEART, BLOOD_PRESSURE, ECG, SPO2, STRESS, BODY, TODAY, WELLNESS, MEASURE }

/** One tappable icon inside a card (wellness readings, measure shortcuts, today's check-ins). */
data class CardItem(val metric: Metric, val value: String?, val label: String, val route: String, val done: Boolean = false)

/**
 * What a card shows, independent of how it is drawn: its icon and title, the value, a status line
 * (coloured when it judges the value), a detail line, small bars, or a row of items. The whole
 * card opens [route]; items open their own.
 */
data class CardModel(
    val kind: CardKind,
    val metric: Metric,
    val title: String,
    val value: String?,
    val unit: String? = null,
    val status: String? = null,
    val statusColor: Long? = null,
    val detail: String? = null,
    val bars: List<Float?> = emptyList(),
    val barRange: ClosedFloatingPointRange<Float> = 0f..1f,
    val accent: Long = CardColors.metric(metric),
    val items: List<CardItem> = emptyList(),
    val route: String,
) {
    val hasValue: Boolean get() = value != null
}

/** Colours of the cards: the watch's dark palette. */
object CardColors {
    const val BACKGROUND = 0xFF1C1C1F
    const val TEXT = Palette.Dark.ON_BACKGROUND
    const val SUBTLE = Palette.Dark.ON_SURFACE_VARIANT
    const val TRACK = 0x33FFFFFFL

    fun metric(metric: Metric): Long = when (metric) {
        Metric.ECG -> Palette.Dark.ECG
        Metric.BLOOD_PRESSURE -> Palette.Dark.BP
        Metric.HEART_RATE -> Palette.Dark.HEART_RATE
        Metric.SPO2 -> Palette.Dark.SPO2
        Metric.SKIN_TEMPERATURE -> Palette.Dark.TEMP
        Metric.BODY_COMPOSITION -> Palette.Dark.BODY
        Metric.STRESS -> Palette.Dark.STRESS
    }

    fun severity(severity: Severity): Long = when (severity) {
        Severity.NORMAL -> Palette.Dark.STATUS_NORMAL
        Severity.WARN -> Palette.Dark.STATUS_WARN
        Severity.ALERT -> Palette.Dark.STATUS_ALERT
        Severity.NEUTRAL -> Palette.Dark.ON_SURFACE_VARIANT
    }
}

object CardModels {
    fun of(context: Context, kind: CardKind, d: TileData): CardModel {
        val model = build(context, kind, d)
        // Nothing measured yet: say so rather than show a lone dash.
        return if (!model.hasValue && model.items.isEmpty() && model.detail == null) model.copy(detail = context.getString(R.string.tile_not_measured)) else model
    }

    private fun build(context: Context, kind: CardKind, d: TileData): CardModel {
        fun s(id: Int, vararg args: Any) = context.getString(id, *args)
        return when (kind) {
            CardKind.HEART -> {
                val hours = d.heartHours
                val lows = hours.mapNotNull { it?.first }
                val highs = hours.mapNotNull { it?.last }
                CardModel(
                    kind, Metric.HEART_RATE, s(R.string.metric_hr), d.heartRate?.toString(), s(R.string.unit_bpm),
                    status = if (d.heartMin != null && d.heartMax != null) s(R.string.tile_today_range_text, d.heartMin, d.heartMax) else null,
                    detail = d.lastEcg?.let { s(R.string.tile_last_ecg, it) },
                    bars = hours.map { it?.last?.toFloat() },
                    barRange = ((lows.minOrNull() ?: 40) - 12f)..(highs.maxOrNull() ?: 180).toFloat(),
                    route = TileRoutes.HEART_RATE,
                )
            }
            CardKind.BLOOD_PRESSURE -> CardModel(
                kind, Metric.BLOOD_PRESSURE, s(R.string.metric_bp), d.lastBp, s(R.string.complication_mmhg),
                status = d.bpCategory?.let { s(it.label) },
                statusColor = d.bpCategory?.let { CardColors.severity(it.severity()) },
                detail = d.bpDaysLeft?.let { s(R.string.tile_bp_days, it) } ?: s(R.string.tile_calibrate_on_phone),
                route = TileRoutes.BLOOD_PRESSURE,
            )
            CardKind.ECG -> CardModel(
                kind, Metric.ECG, s(R.string.metric_ecg), d.ecgResult?.let { s(it.shortLabel) },
                status = d.lastEcg,
                statusColor = d.ecgResult?.let { CardColors.severity(it.severity) },
                route = TileRoutes.ECG,
            )
            CardKind.SPO2 -> CardModel(
                kind, Metric.SPO2, s(R.string.metric_spo2), d.spo2?.toString(), "%",
                bars = d.spo2History.map { it.toFloat() },
                barRange = ((d.spo2History.minOrNull() ?: 90) - 3f)..100f,
                route = TileRoutes.measure(Metric.SPO2),
            )
            CardKind.STRESS -> CardModel(
                kind, Metric.STRESS, s(R.string.metric_stress), d.stressScore?.toString(),
                status = d.stressLevel?.let { level -> listOfNotNull(s(level.label), d.hrvMs?.let { s(R.string.stress_hrv, it) }).joinToString(" · ") },
                statusColor = d.stressLevel?.let { CardColors.severity(it.severity()) },
                bars = d.stressHistory.map { it.toFloat() },
                barRange = ((d.stressHistory.minOrNull() ?: 0) - 15f).coerceAtLeast(0f)..(d.stressHistory.maxOrNull() ?: 100).toFloat(),
                route = TileRoutes.measure(Metric.STRESS),
            )
            CardKind.BODY -> d.body.let { b ->
                CardModel(
                    kind, Metric.BODY_COMPOSITION, s(R.string.tile_body), b?.let { String.format(Locale.US, "%.1f", it.fatPercent) }, "%",
                    status = b?.fatChange?.takeIf { kotlin.math.abs(it) >= 0.1f }?.let { s(R.string.tile_body_change, String.format(Locale.US, "%+.1f", it)) } ?: s(R.string.tile_body_fat),
                    statusColor = b?.fatChange?.takeIf { it <= -0.1f }?.let { CardColors.severity(Severity.NORMAL) },
                    detail = b?.let { listOfNotNull(it.muscleKg?.let { m -> s(R.string.tile_body_muscle, m) }, it.weightKg?.let { w -> s(R.string.tile_body_weight, w) }).joinToString(" · ").ifEmpty { null } },
                    route = TileRoutes.measure(Metric.BODY_COMPOSITION),
                )
            }
            CardKind.TODAY -> {
                val checks = TodayChecks.of(d)
                val done = checks.count { it.second }
                val next = checks.firstOrNull { !it.second }?.first
                CardModel(
                    kind, Metric.HEART_RATE, Greeting.title(context, d.name), "$done/${checks.size}",
                    status = if (next == null) s(R.string.tile_today_all_done) else s(R.string.tile_today_next, s(next.labelRes)),
                    detail = d.streak.takeIf { it >= 2 }?.let { s(R.string.streak_days, it) },
                    accent = d.accent.argb,
                    items = checks.map { (m, ok) -> CardItem(m, null, s(m.labelRes), TileRoutes.measure(m), ok) },
                    route = next?.let { TileRoutes.measure(it) } ?: TileRoutes.LAUNCHER,
                )
            }
            CardKind.WELLNESS -> CardModel(
                kind, Metric.SPO2, s(R.string.tile_wellness), null,
                items = listOf(
                    CardItem(Metric.SPO2, d.spo2?.let { "$it%" }, s(R.string.tile_spo2_short), TileRoutes.measure(Metric.SPO2)),
                    CardItem(Metric.STRESS, d.stressScore?.toString(), s(R.string.metric_stress), TileRoutes.measure(Metric.STRESS)),
                    CardItem(Metric.SKIN_TEMPERATURE, d.temperature, s(R.string.tile_temp_short), TileRoutes.measure(Metric.SKIN_TEMPERATURE)),
                ),
                route = TileRoutes.LAUNCHER,
            )
            CardKind.MEASURE -> CardModel(
                kind, Metric.ECG, s(R.string.tile_quick_title), null,
                items = listOf(Metric.ECG, Metric.BLOOD_PRESSURE, Metric.SPO2, Metric.STRESS, Metric.BODY_COMPOSITION, Metric.SKIN_TEMPERATURE)
                    .map { CardItem(it, null, s(it.labelRes), TileRoutes.measure(it)) },
                route = TileRoutes.LAUNCHER,
            )
        }
    }
}
