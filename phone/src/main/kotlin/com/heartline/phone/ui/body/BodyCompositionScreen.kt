package com.heartline.phone.ui.body

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.CardTitle
import com.heartline.phone.ui.components.Chip
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.SectionHeader
import com.heartline.phone.ui.components.TonalPillButton
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.ecg.EmptyCard
import com.heartline.phone.ui.model.BodyDetailUi
import com.heartline.phone.ui.model.BodyEntryUi
import com.heartline.phone.ui.model.BodySpan
import com.heartline.phone.ui.model.BodyTrend
import com.heartline.phone.ui.model.of
import com.heartline.phone.ui.theme.HeartlineColors
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.body.BodyLevel
import com.heartline.shared.body.BodyRange
import com.heartline.shared.body.BodyReport
import com.heartline.shared.body.BodyType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Body composition, one long page that reveals more as it scrolls (the title collapses into the
 * top bar): a hero ring of the latest result, the weight / body fat / muscle / fat mass trend,
 * a card per measure with its reference range and change, the body type grid, the sensor's own
 * values and the history. Every block animates in as it scrolls into view.
 */
@Composable
fun BodyCompositionScreen(
    state: BodyDetailUi,
    onBack: (() -> Unit)? = null,
    onMeasureOnWatch: (() -> Unit)? = null,
    animate: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
    advancedOpen: Boolean = false,
) {
    val colors = HeartlineTheme.colors
    val color = colors.body
    val latest = state.latest
    ReachabilityScaffold(
        title = stringResource(R.string.metric_body),
        subtitle = latest?.let { stringResource(R.string.bp_last_measured, "${it.date} ${it.time}") },
        onBack = onBack,
        listState = listState,
    ) {
        onMeasureOnWatch?.let { measure ->
            item { TonalPillButton(stringResource(R.string.action_measure_on_watch), onClick = measure, modifier = Modifier.gutter(), color = color) }
        }
        if (latest == null) {
            item { EmptyCard(stringResource(R.string.ecg_empty_title), stringResource(R.string.metric_empty_body)) }
        } else {
            val r = latest.report
            val prev = state.previous?.report
            item(key = "hero") { Appear(animate) { t -> HeroCard(r, color, t) } }
            if (state.entries.size >= 2) item(key = "trend") { Appear(animate) { t -> TrendCard(state, color, t, animate) } }
            item(key = "measures") { SectionHeader(stringResource(R.string.body_section_measures)) }
            val stats = buildList {
                r.weightKg?.let { add(StatUi(R.string.body_weight, it, "kg", prev?.weightKg, null, better = 0)) }
                r.bmi?.let { add(StatUi(R.string.body_bmi, it, "", prev?.bmi, r.ranges.bmi to it, better = 0)) }
                add(StatUi(R.string.body_fat_percent, r.bodyFatPercent, "%", prev?.bodyFatPercent, r.ranges.bodyFat to r.bodyFatPercent, better = -1))
                r.fatMassKg?.let { add(StatUi(R.string.body_fat_mass, it, "kg", prev?.fatMassKg, null, better = -1)) }
                r.skeletalMuscleKg?.let { add(StatUi(R.string.body_trend_muscle, it, "kg", prev?.skeletalMuscleKg, r.skeletalMusclePercent?.let { p -> r.ranges.muscle to p }, better = 1)) }
                r.bodyWaterKg?.let { add(StatUi(R.string.detail_water, it, "kg", prev?.bodyWaterKg, r.bodyWaterPercent?.let { p -> r.ranges.water to p }, better = 0)) }
                r.bmrKcal?.let { add(StatUi(R.string.detail_bmr, it.toFloat(), "kcal", prev?.bmrKcal?.toFloat(), null, better = 0, decimals = 0)) }
                r.fatFreeMassKg?.let { add(StatUi(R.string.body_fat_free, it, "kg", prev?.fatFreeMassKg, null, better = 1)) }
            }
            stats.chunked(2).forEachIndexed { i, pair ->
                item(key = "stats-$i") {
                    Appear(animate) { t ->
                        Row(Modifier.gutter(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { StatCard(it, state.entries, colors, t, Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            r.bodyType?.let { type -> item(key = "type") { Appear(animate) { t -> BodyTypeCard(type, color, t, animate) } } }
            item(key = "advanced") { Appear(animate) { AdvancedCard(r, colors, advancedOpen) } }
            item(key = "history-title") { SectionHeader(stringResource(R.string.bp_history)) }
            item(key = "history") { Appear(animate) { t -> HistoryCard(state.entries.take(12), colors, t) } }
        }
        item {
            Text(
                stringResource(R.string.about_body),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}

/** Fades and slides a block in the first time it scrolls into view, and hands its 0 → 1 progress to [content]. */
@Composable
private fun Appear(animate: Boolean, durationMs: Int = 900, content: @Composable (Float) -> Unit) {
    val a = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) }
    val t = a.value
    Box(Modifier.graphicsLayer { alpha = t.coerceIn(0f, 1f); translationY = (1 - t) * 40.dp.toPx() }) { content(t) }
}

@Composable
private fun HeroCard(r: BodyReport, color: Color, t: Float) {
    val colors = HeartlineTheme.colors
    val fatColor = colors.statusWarn
    val otherColor = colors.onSurfaceVariant.copy(alpha = 0.35f)
    RoundedCard(Modifier.gutter()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(156.dp), contentAlignment = Alignment.Center) {
                val shares = r.shares
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 16.dp.toPx()
                    val inset = stroke / 2 + 4.dp.toPx()
                    val arc = Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(colors.surfaceVariant, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    val segs = shares?.let { listOf(it[0] to fatColor, it[1] to color, it[2] to otherColor) } ?: listOf(r.bodyFatPercent / 100f to fatColor)
                    var start = -90f
                    segs.forEach { (share, c) ->
                        val sweep = 360f * share * t
                        if (sweep > 3f) {
                            // Soft glow behind each segment, then the segment, with a small gap between segments.
                            drawArc(c.copy(alpha = 0.16f), start + 1.5f, sweep - 3f, false, Offset(inset, inset), arc, style = Stroke(stroke * 1.8f))
                            drawArc(c, start + 1.5f, sweep - 3f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                        }
                        start += 360f * share * t
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        String.format(Locale.US, "%.1f", r.bodyFatPercent * t),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onBackground,
                    )
                    Text(stringResource(R.string.body_fat_percent_short), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                r.weightKg?.let { HeroFigure(stringResource(R.string.body_weight), String.format(Locale.US, "%.1f", it * t), "kg") }
                r.bmi?.let { HeroFigure(stringResource(R.string.body_bmi), String.format(Locale.US, "%.1f", it * t), "") }
                r.bodyType?.let {
                    Text(
                        stringResource(it.label),
                        style = MaterialTheme.typography.labelLarge,
                        color = color,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(color.copy(alpha = 0.16f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (r.shares != null) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(
                    Triple(fatColor, R.string.body_legend_fat, r.fatMassKg),
                    Triple(color, R.string.body_legend_muscle, r.skeletalMuscleKg),
                    Triple(otherColor, R.string.body_legend_other, r.weightKg?.let { w -> w - (r.fatMassKg ?: 0f) - (r.skeletalMuscleKg ?: 0f) }),
                ).forEach { (c, label, kg) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(c))
                        Text(
                            stringResource(label) + (kg?.let { " " + String.format(Locale.US, "%.1f kg", it) } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroFigure(label: String, value: String, unit: String) {
    val colors = HeartlineTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
private fun TrendCard(state: BodyDetailUi, color: Color, t: Float, animate: Boolean) {
    val colors = HeartlineTheme.colors
    var trend by rememberSaveable { mutableStateOf(BodyTrend.WEIGHT) }
    var span by rememberSaveable { mutableStateOf(BodySpan.QUARTER) }
    val points = state.points(trend, span)
    RoundedCard(Modifier.gutter()) {
        CardTitle(stringResource(R.string.body_trend_title))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            BodyTrend.entries.forEach { Chip(stringResource(it.label), it == trend, onClick = { trend = it }) }
        }
        Spacer(Modifier.height(14.dp))
        // The line re-draws itself whenever the series or span changes.
        val draw = remember(trend, span) { Animatable(if (animate) 0f else 1f) }
        LaunchedEffect(trend, span) { draw.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing)) }
        LineChart(points, color, draw.value * t, unit = trend.unit, modifier = Modifier.fillMaxWidth().height(170.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BodySpan.entries.forEach { Chip(stringResource(it.label), it == span, onClick = { span = it }) }
        }
        Spacer(Modifier.height(16.dp))
        Row {
            listOf(
                Triple(BodyTrend.WEIGHT, R.string.body_weight, 0),
                Triple(BodyTrend.FAT_MASS, R.string.body_fat_mass, -1),
                Triple(BodyTrend.MUSCLE, R.string.detail_muscle, 1),
            ).forEach { (tr, label, better) ->
                val d = state.change(tr, span)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(
                        d?.let { String.format(Locale.US, "%+.1f kg", it) } ?: "–",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = d?.let { deltaColor(it, better, colors) } ?: colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A line with a gradient fill that draws itself left to right ([progress]); tap for a point's value. */
@Composable
private fun LineChart(points: List<Pair<Long, Float>>, color: Color, progress: Float, unit: String, modifier: Modifier) {
    val colors = HeartlineTheme.colors
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    if (points.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.body_trend_more), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        return
    }
    val min = points.minOf { it.second }
    val max = points.maxOf { it.second }
    val pad = ((max - min) * 0.25f).coerceAtLeast(0.5f)
    val lo = min - pad
    val hi = max + pad
    val t0 = points.first().first
    val t1 = points.last().first
    Column(modifier) {
        Text(
            String.format(Locale.US, "%.1f %s", points[selected.coerceIn(0, points.lastIndex)].second, unit).trim(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.onBackground,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        selected = points.indices.minBy { i -> abs(xOf(points[i].first, t0, t1, size.width.toFloat()) - tap.x) }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            fun y(v: Float) = h - (v - lo) / (hi - lo) * h
            val xy = points.map { Offset(xOf(it.first, t0, t1, w), y(it.second)) }
            // Guide lines.
            for (k in 0..3) drawLine(colors.divider, Offset(0f, h * k / 3f), Offset(w, h * k / 3f), strokeWidth = 1.dp.toPx())
            val line = Path().apply {
                moveTo(xy[0].x, xy[0].y)
                for (i in 1 until xy.size) {
                    val a = xy[i - 1]
                    val b = xy[i]
                    val mid = (a.x + b.x) / 2
                    cubicTo(mid, a.y, mid, b.y, b.x, b.y)
                }
            }
            val shown = w * progress
            clipRect(0f, -20f, shown + 8f, h + 20f) {
                val fill = Path().apply {
                    addPath(line)
                    lineTo(xy.last().x, h)
                    lineTo(xy.first().x, h)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0f)), startY = 0f, endY = h))
                drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                xy.forEachIndexed { i, p ->
                    val sel = i == selected
                    drawCircle(colors.surface, radius = (if (sel) 7 else 4).dp.toPx(), center = p)
                    drawCircle(color, radius = (if (sel) 5 else 3).dp.toPx(), center = p)
                }
            }
            if (progress >= 1f) {
                val p = xy[selected.coerceIn(0, xy.lastIndex)]
                drawLine(color.copy(alpha = 0.4f), Offset(p.x, 0f), Offset(p.x, h), strokeWidth = 1.5.dp.toPx())
            }
        }
    }
}

private fun xOf(t: Long, t0: Long, t1: Long, w: Float): Float = if (t1 == t0) w / 2 else (t - t0).toFloat() / (t1 - t0) * w

private data class StatUi(
    val label: Int,
    val value: Float,
    val unit: String,
    val previous: Float?,
    val range: Pair<BodyRange, Float>?,
    /** +1: higher is better, −1: lower is better, 0: neither (the change is shown neutral). */
    val better: Int,
    val decimals: Int = 1,
)

@Composable
private fun StatCard(s: StatUi, entries: List<BodyEntryUi>, colors: HeartlineColors, t: Float, modifier: Modifier) {
    val level = s.range?.let { (range, v) -> range.level(v) }
    RoundedCard(modifier, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(s.label), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
            level?.let {
                Text(
                    stringResource(it.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = it.color(colors),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(it.color(colors).copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            val v = s.value * t
            Text(
                if (s.decimals == 0) "${v.roundToInt()}" else String.format(Locale.US, "%.1f", v),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground,
            )
            if (s.unit.isNotEmpty()) Text(" ${s.unit}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
        s.previous?.let { p ->
            val d = s.value - p
            if (abs(d) >= if (s.decimals == 0) 1f else 0.05f) {
                Text(
                    (if (d > 0) "▲ " else "▼ ") + if (s.decimals == 0) "${abs(d).roundToInt()}" else String.format(Locale.US, "%.1f", abs(d)),
                    style = MaterialTheme.typography.labelMedium,
                    color = deltaColor(d, s.better, colors),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (s.range != null) {
            RangeBar(s.range.first, s.range.second, t, colors, Modifier.fillMaxWidth().height(12.dp))
        } else {
            // No reference range: a sparkline of the recent history instead.
            val series = entries.take(10).reversed().mapNotNull { e -> statValue(s.label, e.report) }
            Sparkline(series, colors.body, t, Modifier.fillMaxWidth().height(22.dp))
        }
    }
}

private fun statValue(label: Int, r: BodyReport): Float? = when (label) {
    R.string.body_weight -> r.weightKg
    R.string.body_fat_mass -> r.fatMassKg
    R.string.detail_bmr -> r.bmrKcal?.toFloat()
    R.string.body_fat_free -> r.fatFreeMassKg
    else -> null
}

@Composable
private fun Sparkline(values: List<Float>, color: Color, t: Float, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max().let { if (it - lo < 1e-3f) lo + 1f else it }
        val pts = values.mapIndexed { i, v -> Offset(size.width * i / (values.size - 1), size.height - (v - lo) / (hi - lo) * size.height * 0.8f - size.height * 0.1f) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        clipRect(0f, -4f, size.width * t + 4f, size.height + 4f) {
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color, 3.dp.toPx(), pts.last())
        }
    }
}

/** Low / standard / high (/ very high) bands, and a marker that slides to the value. */
@Composable
private fun RangeBar(range: BodyRange, value: Float, t: Float, colors: HeartlineColors, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val y = size.height / 2
        val h = 5.dp.toPx()
        fun x(v: Float) = range.position(v) * w
        val bands = mutableListOf(Triple(0f, x(range.low), BodyLevel.LOW), Triple(x(range.low), x(range.high), BodyLevel.STANDARD))
        val vh = range.veryHigh
        if (vh != null) {
            bands += Triple(x(range.high), x(vh), BodyLevel.HIGH)
            bands += Triple(x(vh), w, BodyLevel.VERY_HIGH)
        } else {
            bands += Triple(x(range.high), w, BodyLevel.HIGH)
        }
        bands.forEach { (a, b, l) -> if (b - a > 3f) drawLine(l.color(colors).copy(alpha = 0.8f), Offset(a + 2f, y), Offset(b - 2f, y), strokeWidth = h, cap = StrokeCap.Round) }
        val r = size.height / 2
        val mx = (x(value) * t).coerceIn(r, w - r)
        drawCircle(colors.surface, r, Offset(mx, y))
        drawCircle(range.level(value).color(colors), r - 2.5.dp.toPx(), Offset(mx, y))
    }
}

@Composable
private fun BodyTypeCard(type: BodyType, color: Color, t: Float, animate: Boolean) {
    val colors = HeartlineTheme.colors
    val pulse = if (animate) {
        val inf = rememberInfiniteTransition(label = "type")
        val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "p")
        p
    } else {
        0.4f
    }
    RoundedCard(Modifier.gutter()) {
        CardTitle(stringResource(R.string.body_type_title))
        Spacer(Modifier.height(4.dp))
        Text(stringResource(type.description), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Row {
            // Y axis: body fat, high at the top.
            Column(Modifier.width(56.dp).height(64.dp * 3), verticalArrangement = Arrangement.SpaceAround) {
                listOf(R.string.level_high, R.string.level_standard, R.string.level_low).forEach {
                    Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in 2 downTo 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (col in 0..2) {
                            val cell = BodyType.entries.first { it.row == row && it.column == col }
                            val mine = cell == type
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(58.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (mine) color.copy(alpha = 0.22f * t) else colors.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (mine) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawCircle(color.copy(alpha = 0.35f * (1 - pulse)), radius = (8f + 18f * pulse).dp.toPx() * t)
                                        drawCircle(color, radius = 7.dp.toPx() * t)
                                    }
                                }
                                Text(
                                    stringResource(cell.label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (mine) colors.onBackground else colors.onSurfaceVariant,
                                    fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 2.dp, end = 2.dp, bottom = 4.dp),
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
                Row {
                    listOf(R.string.level_low, R.string.level_standard, R.string.level_high).forEach {
                        Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    }
                }
            }
        }
        Text(
            stringResource(R.string.body_type_axes),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun AdvancedCard(r: BodyReport, colors: HeartlineColors, initiallyOpen: Boolean) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(20.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.body_advanced_title), style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                Text(stringResource(R.string.body_advanced_subtitle), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
            )
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                r.phaseAngleDeg?.let { pa ->
                    Column {
                        AdvancedRow(stringResource(R.string.body_phase_angle), String.format(Locale.US, "%.1f°", pa), colors)
                        Spacer(Modifier.height(6.dp))
                        RangeBar(r.ranges.phaseAngle, pa, 1f, colors, Modifier.fillMaxWidth().height(12.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.body_phase_angle_about), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                r.impedanceOhm?.let { AdvancedRow(stringResource(R.string.body_impedance), String.format(Locale.US, "%.0f Ω", it), colors) }
                r.skeletalMusclePercent?.let { AdvancedRow(stringResource(R.string.body_muscle_percent), String.format(Locale.US, "%.1f %%", it), colors) }
                r.bodyWaterPercent?.let { AdvancedRow(stringResource(R.string.body_water_percent), String.format(Locale.US, "%.1f %%", it), colors) }
                r.fatFreeMassKg?.let { ffm ->
                    r.weightKg?.let { w -> AdvancedRow(stringResource(R.string.body_fat_free_percent), String.format(Locale.US, "%.1f %%", 100f * ffm / w), colors) }
                }
                r.heightCm?.let { AdvancedRow(stringResource(R.string.body_height_used), String.format(Locale.US, "%.0f cm", it), colors) }
            }
        }
    }
}

@Composable
private fun AdvancedRow(label: String, value: String, colors: HeartlineColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
    }
}

@Composable
private fun HistoryCard(entries: List<BodyEntryUi>, colors: HeartlineColors, t: Float) {
    RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
        entries.forEachIndexed { i, e ->
            val r = e.report
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = ((t * entries.size) - i * 0.6f).coerceIn(0f, 1f) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(r.fatLevel.color(colors)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        String.format(Locale.US, "%.1f %%", r.bodyFatPercent) + (r.weightKg?.let { String.format(Locale.US, " · %.1f kg", it) } ?: ""),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onBackground,
                    )
                    Text("${e.date} · ${e.time}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                r.skeletalMuscleKg?.let {
                    Text(String.format(Locale.US, "%.1f kg", it), style = MaterialTheme.typography.labelLarge, color = colors.body)
                }
            }
            if (i < entries.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 42.dp).height(1.dp).background(colors.divider))
        }
    }
}

private fun deltaColor(d: Float, better: Int, colors: HeartlineColors): Color = when {
    better == 0 || abs(d) < 0.05f -> colors.onSurfaceVariant
    (d > 0) == (better > 0) -> colors.statusNormal
    else -> colors.statusWarn
}

private fun BodyLevel.color(colors: HeartlineColors): Color = when (this) {
    BodyLevel.LOW -> Color(0xFF4A90E2)
    BodyLevel.STANDARD -> colors.statusNormal
    BodyLevel.HIGH -> colors.statusWarn
    BodyLevel.VERY_HIGH -> colors.statusAlert
}

private val BodyLevel.label: Int
    get() = when (this) {
        BodyLevel.LOW -> R.string.level_low
        BodyLevel.STANDARD -> R.string.level_standard
        BodyLevel.HIGH -> R.string.level_high
        BodyLevel.VERY_HIGH -> R.string.level_very_high
    }

private val BodyTrend.label: Int
    get() = when (this) {
        BodyTrend.WEIGHT -> R.string.body_weight
        BodyTrend.BODY_FAT -> R.string.body_fat_percent_short
        BodyTrend.MUSCLE -> R.string.body_trend_muscle
        BodyTrend.FAT_MASS -> R.string.body_fat_mass
    }

private val BodyTrend.unit: String
    get() = when (this) {
        BodyTrend.BODY_FAT -> "%"
        else -> "kg"
    }

private val BodySpan.label: Int
    get() = when (this) {
        BodySpan.MONTH -> R.string.span_month
        BodySpan.QUARTER -> R.string.span_quarter
        BodySpan.YEAR -> R.string.span_year
        BodySpan.ALL -> R.string.span_all
    }

val BodyType.label: Int
    get() = when (this) {
        BodyType.SLIM -> R.string.body_type_slim
        BodyType.LEAN -> R.string.body_type_lean
        BodyType.ATHLETIC -> R.string.body_type_athletic
        BodyType.UNDER_EXERCISED -> R.string.body_type_under_exercised
        BodyType.BALANCED -> R.string.body_type_balanced
        BodyType.MUSCULAR -> R.string.body_type_muscular
        BodyType.HIDDEN_OVERWEIGHT -> R.string.body_type_hidden_overweight
        BodyType.OVERFAT -> R.string.body_type_overfat
        BodyType.SOLIDLY_BUILT -> R.string.body_type_solidly_built
    }

private val BodyType.description: Int
    get() = when (this) {
        BodyType.SLIM -> R.string.body_type_slim_about
        BodyType.LEAN -> R.string.body_type_lean_about
        BodyType.ATHLETIC -> R.string.body_type_athletic_about
        BodyType.UNDER_EXERCISED -> R.string.body_type_under_exercised_about
        BodyType.BALANCED -> R.string.body_type_balanced_about
        BodyType.MUSCULAR -> R.string.body_type_muscular_about
        BodyType.HIDDEN_OVERWEIGHT -> R.string.body_type_hidden_overweight_about
        BodyType.OVERFAT -> R.string.body_type_overfat_about
        BodyType.SOLIDLY_BUILT -> R.string.body_type_solidly_built_about
    }
