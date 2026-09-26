package com.heartline.wear.tile

import android.content.Context
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.ProgressIndicatorColors
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.circularProgressIndicator
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconButton
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.layoutString
import com.heartline.shared.design.Palette
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Severity
import com.heartline.wear.R
import com.heartline.wear.ui.screens.label
import java.util.Locale

/*
 * Tiles in the Samsung Health style: black, a coloured arc along the round edge, one large
 * number with its icon in the middle, a small chart, and a round button at the bottom. No
 * rectangular cards.
 */

private val TRACK = TileColors.of(0x26FFFFFF)
private val SUBTLE = TileColors.of(Palette.Dark.ON_SURFACE_VARIANT)
private val WHITE = TileColors.of(Palette.Dark.ON_BACKGROUND)

/**
 * The tile layout with a coloured arc along the screen edge (open at the bottom, where the button
 * sits). [progress]: how full the arc is, 0–1.
 */
internal fun MaterialScope.edgeTile(
    title: String,
    progress: Float,
    arcColor: LayoutColor,
    main: MaterialScope.() -> LayoutElementBuilders.LayoutElement,
    button: (MaterialScope.() -> LayoutElementBuilders.LayoutElement)? = null,
): LayoutElementBuilders.LayoutElement = LayoutElementBuilders.Box.Builder()
    .setWidth(expand())
    .setHeight(expand())
    .addContent(edgeArc(ARC_SPAN, TRACK))
    .addContent(edgeArc(ARC_SPAN * progress.coerceIn(0.015f, 1f), arcColor))
    .addContent(
        primaryLayout(
            titleSlot = { text(title.layoutString, typography = Typography.TITLE_SMALL, color = WHITE) },
            mainSlot = main,
            bottomSlot = button,
        ),
    )
    .build()

/** Degrees of the edge arc: from about 7 to 5 o'clock over the top, leaving room for the button. */
private const val ARC_SPAN = 280f

/** An arc along the screen edge starting at the arc's left end, [length] degrees clockwise. */
private fun edgeArc(length: Float, color: LayoutColor): LayoutElementBuilders.LayoutElement = LayoutElementBuilders.Arc.Builder()
    .setAnchorAngle(androidx.wear.protolayout.DimensionBuilders.degrees(-ARC_SPAN / 2))
    .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
    .addContent(
        LayoutElementBuilders.ArcLine.Builder()
            .setLength(androidx.wear.protolayout.DimensionBuilders.degrees(length))
            .setThickness(dp(5f))
            .setColor(color.prop)
            .setStrokeCap(LayoutElementBuilders.StrokeCapProp.Builder().setValue(LayoutElementBuilders.STROKE_CAP_ROUND).build())
            .build(),
    )
    .build()

/** The round bottom button (One UI / Samsung Health "Measure"). */
internal fun MaterialScope.edgeButton(context: Context, route: String, label: String, color: LayoutColor) =
    textEdgeButton(onClick = context.launch(route), colors = ButtonColors(containerColor = color, labelColor = TileColors.of(0xFF000000))) {
        text(label.layoutString, typography = TileType.button)
    }

/** Icon, the value large, and its unit small, on one line. */
internal fun MaterialScope.hero(metric: Metric, value: String, unit: String?, color: LayoutColor = TileColors.metric(metric)): LayoutElementBuilders.LayoutElement {
    val row = LayoutElementBuilders.Row.Builder()
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .addContent(icon(TileIcons.id(metric), width = dp(24f), height = dp(24f), tintColor = color))
        .addContent(gap(6f))
        .addContent(text(value.layoutString, typography = TileType.big, color = WHITE))
    unit?.let {
        row.addContent(gap(3f))
        row.addContent(text(it.layoutString, typography = Typography.LABEL_SMALL, color = SUBTLE))
    }
    return row.build()
}

internal fun gap(width: Float = 0f, height: Float = 0f): LayoutElementBuilders.Spacer =
    LayoutElementBuilders.Spacer.Builder().setWidth(dp(width)).setHeight(dp(height)).build()

internal fun MaterialScope.line(value: String, color: LayoutColor = SUBTLE, typography: Int = Typography.LABEL_SMALL) =
    text(value.layoutString, typography = typography, color = color, maxLines = 1)

/** A dot and a coloured status ("● Normal"). */
internal fun MaterialScope.status(value: String, severity: Severity): LayoutElementBuilders.LayoutElement =
    LayoutElementBuilders.Row.Builder()
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .addContent(dot(TileColors.severity(severity), 6f))
        .addContent(gap(4f))
        .addContent(line(value, TileColors.severity(severity)))
        .build()

internal fun dot(color: LayoutColor, size: Float): LayoutElementBuilders.LayoutElement = LayoutElementBuilders.Box.Builder()
    .setWidth(dp(size))
    .setHeight(dp(size))
    .setModifiers(background(color, size / 2))
    .build()

private fun background(color: LayoutColor, radius: Float) = ModifiersBuilders.Modifiers.Builder()
    .setBackground(
        ModifiersBuilders.Background.Builder()
            .setColor(color.prop)
            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(radius)).build())
            .build(),
    )
    .build()

/**
 * A small bar chart: one rounded bar per value, its height within [range]; the last bar full
 * colour, earlier ones dimmer. Null values leave a gap. Floating bars when [low] gives each
 * bar's bottom.
 */
internal fun bars(values: List<Float?>, range: ClosedFloatingPointRange<Float>, color: LayoutColor, height: Float = 26f, width: Float = 6f, low: List<Float?>? = null): LayoutElementBuilders.LayoutElement {
    val row = LayoutElementBuilders.Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    fun h(v: Float) = ((v - range.start) / span).coerceIn(0f, 1f) * height
    val dim = LayoutColor((color.staticArgb and 0x00FFFFFF) or (0x80 shl 24))
    values.forEachIndexed { i, v ->
        if (i > 0) row.addContent(gap(width = 3f))
        val top = v?.let(::h)
        val bottom = low?.getOrNull(i)?.let(::h) ?: 0f
        val barHeight = top?.let { (it - bottom).coerceAtLeast(width) }
        row.addContent(
            LayoutElementBuilders.Column.Builder()
                .setHeight(dp(height))
                .setWidth(dp(width))
                .addContent(gap(height = (height - (top ?: 0f)).coerceAtLeast(0f)))
                .apply {
                    if (barHeight != null) {
                        addContent(
                            LayoutElementBuilders.Box.Builder()
                                .setWidth(dp(width))
                                .setHeight(dp(barHeight))
                                .setModifiers(background(if (i == values.lastIndex) color else dim, width / 2))
                                .build(),
                        )
                    }
                }
                .build(),
        )
    }
    return row.build()
}

internal fun column(vararg items: LayoutElementBuilders.LayoutElement?, clickable: ModifiersBuilders.Clickable? = null): LayoutElementBuilders.LayoutElement =
    LayoutElementBuilders.Column.Builder()
        .setWidth(expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        .apply { if (clickable != null) setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(clickable).build()) }
        .apply { items.filterNotNull().forEach { addContent(it) } }
        .build()

/** Heart: live heart rate with an arc for where it sits in today's range, and today's hourly bars. */
class HeartTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData): LayoutElementBuilders.LayoutElement {
        val bpm = data.heartRate
        val lo = data.heartMin ?: 40
        val hi = data.heartMax ?: 180
        val progress = bpm?.let { if (hi > lo) (it - lo).toFloat() / (hi - lo) else 0.5f } ?: 0f
        val hours = data.heartHours
        // Each hour's highest, from a floor a little under the day's lowest.
        val range = (hours.mapNotNull { it?.first }.minOrNull() ?: 40).toFloat() - 12f..(hours.mapNotNull { it?.last }.maxOrNull() ?: 180).toFloat()
        return edgeTile(
            context.getString(R.string.metric_hr),
            progress,
            TileColors.metric(Metric.HEART_RATE),
            main = {
                column(
                    hero(Metric.HEART_RATE, bpm?.toString() ?: "–", context.getString(R.string.unit_bpm)),
                    line(if (data.heartMin != null && data.heartMax != null) context.getString(R.string.tile_today_range_text, data.heartMin, data.heartMax) else context.getString(R.string.tile_not_measured)),
                    gap(height = 4f),
                    hours.takeIf { it.any { h -> h != null } }?.let {
                        bars(it.map { h -> h?.last?.toFloat() }, range, TileColors.metric(Metric.HEART_RATE), height = 26f, width = 6f)
                    },
                    clickable = context.launch(TileRoutes.HEART_RATE),
                )
            },
            button = { edgeButton(context, TileRoutes.ECG, context.getString(R.string.metric_ecg), TileColors.metric(Metric.ECG)) },
        )
    }
}

/** Blood pressure: latest reading and category; the arc shows how long the calibration still lasts. */
class BpTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData): LayoutElementBuilders.LayoutElement {
        val days = data.bpDaysLeft
        return edgeTile(
            context.getString(R.string.metric_bp),
            days?.let { it / 28f } ?: 0f,
            TileColors.metric(Metric.BLOOD_PRESSURE),
            main = {
                column(
                    hero(Metric.BLOOD_PRESSURE, data.lastBp ?: "–", context.getString(R.string.complication_mmhg)),
                    data.bpCategory?.let { status(context.getString(it.label), it.severity()) } ?: line(context.getString(R.string.tile_not_measured)),
                    gap(height = 2f),
                    line(days?.let { context.getString(R.string.tile_bp_days, it) } ?: context.getString(R.string.tile_calibrate_on_phone)),
                    clickable = context.launch(TileRoutes.BLOOD_PRESSURE),
                )
            },
            button = {
                edgeButton(
                    context,
                    TileRoutes.BLOOD_PRESSURE,
                    context.getString(if (days != null) R.string.tile_measure else R.string.tile_calibrate),
                    TileColors.metric(Metric.BLOOD_PRESSURE),
                )
            },
        )
    }
}

/** Round shortcut buttons (Samsung "shortcuts" tile) that start a measurement, and "All". */
class QuickMeasureTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData) = primaryLayout(
        titleSlot = { text(context.getString(R.string.tile_quick_title).layoutString, typography = Typography.TITLE_SMALL, color = WHITE) },
        mainSlot = {
            fun MaterialScope.metricButton(metric: Metric) = iconButton(
                onClick = context.launch(TileRoutes.measure(metric)),
                iconContent = { icon(TileIcons.id(metric), tintColor = TileColors.metric(metric)) },
                width = expand(),
                height = expand(),
                colors = ButtonColors(containerColor = TileColors.metricContainer(metric), iconColor = TileColors.metric(metric)),
            )
            LayoutElementBuilders.Column.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .addContent(
                    buttonGroup(height = expand()) {
                        buttonGroupItem { metricButton(Metric.ECG) }
                        buttonGroupItem { metricButton(Metric.BLOOD_PRESSURE) }
                        buttonGroupItem { metricButton(Metric.SPO2) }
                    },
                )
                .addContent(gap(height = 6f))
                .addContent(
                    buttonGroup(height = expand()) {
                        buttonGroupItem { metricButton(Metric.STRESS) }
                        buttonGroupItem { metricButton(Metric.BODY_COMPOSITION) }
                        buttonGroupItem { metricButton(Metric.SKIN_TEMPERATURE) }
                    },
                )
                .build()
        },
        bottomSlot = {
            textEdgeButton(onClick = context.launch(TileRoutes.LAUNCHER)) { text(context.getString(R.string.tile_all).layoutString, typography = TileType.button) }
        },
    )
}

/** SpO₂, stress and skin temperature as three small rings with the number under each. */
class WellnessTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData) = primaryLayout(
        titleSlot = { text(context.getString(R.string.tile_wellness).layoutString, typography = Typography.TITLE_SMALL, color = WHITE) },
        mainSlot = {
            fun MaterialScope.ring(metric: Metric, value: String?, label: Int, progress: Float) = LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(context.launch(TileRoutes.measure(metric))).build())
                .addContent(
                    LayoutElementBuilders.Box.Builder()
                        .setWidth(dp(46f))
                        .setHeight(dp(46f))
                        .addContent(
                            circularProgressIndicator(
                                staticProgress = if (value == null) 0.015f else progress.coerceIn(0.03f, 1f),
                                strokeWidth = 4f,
                                colors = ProgressIndicatorColors(TileColors.metric(metric), TRACK),
                                size = expand(),
                            ),
                        )
                        .addContent(icon(TileIcons.id(metric), width = dp(20f), height = dp(20f), tintColor = TileColors.metric(metric)))
                        .build(),
                )
                .addContent(gap(height = 4f))
                .addContent(text((value ?: "–").layoutString, typography = TileType.value, color = WHITE))
                .addContent(line(context.getString(label)))
                .build()
            LayoutElementBuilders.Row.Builder()
                .setWidth(expand())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(ring(Metric.SPO2, data.spo2?.let { "$it%" }, R.string.tile_spo2_short, ((data.spo2 ?: 80) - 80) / 20f))
                .addContent(gap(width = 8f))
                .addContent(ring(Metric.STRESS, data.stressScore?.toString(), R.string.metric_stress, (data.stressScore ?: 0) / 100f))
                .addContent(gap(width = 8f))
                .addContent(ring(Metric.SKIN_TEMPERATURE, data.temperature, R.string.tile_temp_short, temperatureProgress(data.temperature)))
                .build()
        },
    )

    /** A change from baseline sits around the middle of the ring (±1 °C = empty/full). */
    private fun temperatureProgress(text: String?): Float {
        val v = text?.trimEnd('°')?.toFloatOrNull() ?: return 0f
        return if (text.startsWith("+") || text.startsWith("-")) 0.5f + v / 2f else 0.5f
    }
}

/** Stress: the score with its level; the arc is the score; recent scores as bars. */
class StressTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData): LayoutElementBuilders.LayoutElement {
        val color = data.stressLevel?.let { TileColors.severity(it.severity()) } ?: TileColors.metric(Metric.STRESS)
        return edgeTile(
            context.getString(R.string.metric_stress),
            (data.stressScore ?: 0) / 100f,
            color,
            main = {
                column(
                    hero(Metric.STRESS, data.stressScore?.toString() ?: "–", null),
                    data.stressLevel?.let { level ->
                        status(listOfNotNull(context.getString(level.label), data.hrvMs?.let { context.getString(R.string.stress_hrv, it) }).joinToString(" · "), level.severity())
                    } ?: line(context.getString(R.string.tile_not_measured)),
                    gap(height = 4f),
                    data.stressHistory.takeIf { it.size >= 2 }?.let { h -> bars(h.map(Int::toFloat), (h.min() - 15f).coerceAtLeast(0f)..h.max().toFloat(), color, height = 22f) },
                    clickable = context.launch(TileRoutes.measure(Metric.STRESS)),
                )
            },
            button = { edgeButton(context, TileRoutes.measure(Metric.STRESS), context.getString(R.string.tile_measure), TileColors.metric(Metric.STRESS)) },
        )
    }
}

/** Body composition: body fat large, muscle and weight, and the change since last time. */
class BodyTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData): LayoutElementBuilders.LayoutElement {
        val body = data.body
        return edgeTile(
            context.getString(R.string.tile_body),
            (body?.fatPercent ?: 0f) / 45f,
            TileColors.metric(Metric.BODY_COMPOSITION),
            main = {
                column(
                    hero(Metric.BODY_COMPOSITION, body?.let { String.format(Locale.US, "%.1f", it.fatPercent) } ?: "–", "%"),
                    line(context.getString(R.string.tile_body_fat)),
                    gap(height = 2f),
                    body?.let { b ->
                        line(
                            listOfNotNull(
                                b.muscleKg?.let { context.getString(R.string.tile_body_muscle, it) },
                                b.weightKg?.let { context.getString(R.string.tile_body_weight, it) },
                            ).joinToString(" · "),
                            WHITE,
                        )
                    },
                    body?.fatChange?.takeIf { kotlin.math.abs(it) >= 0.1f }?.let { change ->
                        line(context.getString(R.string.tile_body_change, String.format(Locale.US, "%+.1f", change)), if (change < 0) TileColors.severity(Severity.NORMAL) else SUBTLE)
                    },
                    clickable = context.launch(TileRoutes.measure(Metric.BODY_COMPOSITION)),
                )
            },
            button = { edgeButton(context, TileRoutes.measure(Metric.BODY_COMPOSITION), context.getString(R.string.tile_measure), TileColors.metric(Metric.BODY_COMPOSITION)) },
        )
    }
}

/**
 * Today: a greeting with the user's name, which of the daily check-ins are done (the arc), and
 * the next one to do as the button.
 */
class TodayTileService : HeartlineTileService() {
    override fun MaterialScope.layout(context: Context, data: TileData): LayoutElementBuilders.LayoutElement {
        val checks = TodayChecks.of(data)
        val next = checks.firstOrNull { !it.second }?.first
        val done = checks.count { it.second }
        val title = Greeting.title(context, data.name)
        return edgeTile(
            title,
            done.toFloat() / checks.size,
            TileColors.of(Palette.Dark.PRIMARY),
            main = {
                val row = LayoutElementBuilders.Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                checks.forEachIndexed { i, (metric, ok) ->
                    if (i > 0) row.addContent(gap(width = 10f))
                    row.addContent(
                        LayoutElementBuilders.Box.Builder()
                            .setWidth(dp(40f))
                            .setHeight(dp(40f))
                            .setModifiers(
                                ModifiersBuilders.Modifiers.Builder()
                                    .setClickable(context.launch(TileRoutes.measure(metric)))
                                    .setBackground(
                                        ModifiersBuilders.Background.Builder()
                                            .setColor((if (ok) TileColors.metric(metric) else TileColors.metricContainer(metric)).prop)
                                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build())
                                            .build(),
                                    )
                                    .build(),
                            )
                            .addContent(icon(TileIcons.id(metric), width = dp(20f), height = dp(20f), tintColor = if (ok) TileColors.of(0xFF000000) else TileColors.metric(metric)))
                            .build(),
                    )
                }
                column(
                    line(context.getString(R.string.tile_today_done, done, checks.size), WHITE, Typography.LABEL_MEDIUM),
                    gap(height = 8f),
                    row.build(),
                    gap(height = 6f),
                    line(if (next == null) context.getString(R.string.tile_today_all_done) else context.getString(R.string.tile_today_next, context.getString(next.labelRes))),
                )
            },
            button = next?.let { metric ->
                { edgeButton(context, TileRoutes.measure(metric), context.getString(R.string.tile_measure), TileColors.metric(metric)) }
            },
        )
    }
}

/** The daily check-ins the Today tile tracks, in order, and whether each was done today. */
object TodayChecks {
    val DEFAULT = listOf(Metric.ECG, Metric.BLOOD_PRESSURE, Metric.SPO2)

    fun of(data: TileData, checks: List<Metric> = DEFAULT): List<Pair<Metric, Boolean>> = checks.map { it to (it in data.doneToday) }
}

/** "Good morning, Sara" (or without a name) by the time of day. */
object Greeting {
    fun part(hour: Int): Int = when (hour) {
        in 5..11 -> R.string.greeting_morning
        in 12..16 -> R.string.greeting_afternoon
        in 17..21 -> R.string.greeting_evening
        else -> R.string.greeting_night
    }

    fun title(context: Context, name: String?, hour: Int = java.time.LocalTime.now().hour): String {
        val part = context.getString(part(hour))
        return if (name.isNullOrBlank()) part else context.getString(R.string.greeting_named, part, name)
    }
}

private val Metric.labelRes: Int
    get() = when (this) {
        Metric.ECG -> R.string.metric_ecg
        Metric.BLOOD_PRESSURE -> R.string.metric_bp
        Metric.HEART_RATE -> R.string.metric_hr
        Metric.SPO2 -> R.string.metric_spo2
        Metric.SKIN_TEMPERATURE -> R.string.metric_skin_temp
        Metric.BODY_COMPOSITION -> R.string.metric_body
        Metric.STRESS -> R.string.metric_stress
    }
