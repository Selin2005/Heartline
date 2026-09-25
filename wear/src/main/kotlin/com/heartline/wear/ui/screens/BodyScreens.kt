package com.heartline.wear.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.heartline.shared.body.BodyLevel
import com.heartline.shared.body.BodyRange
import com.heartline.shared.body.BodyReport
import com.heartline.shared.body.BodyType
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Severity
import com.heartline.wear.R
import com.heartline.wear.ui.components.ActionScreen
import com.heartline.wear.ui.components.CenteredValue
import com.heartline.wear.ui.components.isSmallRound
import com.heartline.wear.ui.theme.WearColors
import kotlin.math.roundToInt

/**
 * Today's weight before body composition (asked when the last one is over a month old): a big
 * value adjusted by the bezel (0.1 kg a step) or the − / + buttons.
 */
@Composable
fun WeightConfirmScreen(initialKg: Float, onConfirm: (Float) -> Unit) {
    var kg by remember { mutableFloatStateOf(if (initialKg > 0) initialKg else 70f) }
    var rotary by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun step(d: Float) {
        kg = ((kg + d) * 10).roundToInt() / 10f
        kg = kg.coerceIn(25f, 300f)
    }
    ActionScreen(stringResource(R.string.action_continue), onAction = { onConfirm(kg) }) {
        Column(
            Modifier
                .onRotaryScrollEvent {
                    rotary += it.verticalScrollPixels
                    while (rotary > ROTARY_STEP_PX) {
                        step(0.1f)
                        rotary -= ROTARY_STEP_PX
                    }
                    while (rotary < -ROTARY_STEP_PX) {
                        step(-0.1f)
                        rotary += ROTARY_STEP_PX
                    }
                    true
                }
                .focusRequester(focus)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.body_weight_title), style = MaterialTheme.typography.titleSmall, color = WearColors.metric(Metric.BODY_COMPOSITION))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                FilledTonalIconButton(onClick = { step(-0.1f) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Remove, contentDescription = "−0.1") }
                CenteredValue(
                    "%.1f".format(kg),
                    "kg",
                    if (isSmallRound()) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
                    MaterialTheme.typography.bodyMedium,
                )
                FilledTonalIconButton(onClick = { step(0.1f) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Add, contentDescription = "+0.1") }
            }
            Text(
                stringResource(R.string.body_weight_hint),
                style = MaterialTheme.typography.bodySmall,
                color = WearColors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val ROTARY_STEP_PX = 24f

/**
 * Body composition result as one scrolling page (bezel or swipe): a hero ring with body fat and
 * body type, then a card per measure, each animating in (count-up, range bar, fade and slide)
 * as it scrolls into view. [previous]: the last result, for the change on each card.
 */
@Composable
fun BodyResultScreen(report: BodyReport, previous: BodyReport?, onDone: () -> Unit = {}, animate: Boolean = true, firstItem: Int = 0) {
    val state = rememberTransformingLazyColumnState(initialAnchorItemIndex = firstItem)
    val color = WearColors.metric(Metric.BODY_COMPOSITION)
    ScreenScaffold(scrollState = state, edgeButton = {
        EdgeButton(onClick = onDone, buttonSize = if (isSmallRound()) EdgeButtonSize.ExtraSmall else EdgeButtonSize.Small) {
            Text(stringResource(R.string.action_done))
        }
    }) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { BodyHero(report, color, animate) }
            val cards = buildList {
                report.weightKg?.let { add(Stat(R.string.body_weight, it, 1, "kg", previous?.weightKg, null)) }
                report.skeletalMuscleKg?.let {
                    add(Stat(R.string.body_muscle_label, it, 1, "kg", previous?.skeletalMuscleKg, report.skeletalMusclePercent?.let { p -> report.ranges.muscle to p }))
                }
                report.fatMassKg?.let { add(Stat(R.string.body_fat_mass, it, 1, "kg", previous?.fatMassKg, null)) }
                add(Stat(R.string.body_fat, report.bodyFatPercent, 1, "%", previous?.bodyFatPercent, report.ranges.bodyFat to report.bodyFatPercent))
                report.bmi?.let { add(Stat(R.string.body_bmi, it, 1, "", previous?.bmi, report.ranges.bmi to it)) }
                report.bodyWaterKg?.let {
                    add(Stat(R.string.body_water, it, 1, "kg", previous?.bodyWaterKg, report.bodyWaterPercent?.let { p -> report.ranges.water to p }))
                }
                report.bmrKcal?.let { add(Stat(R.string.body_bmr, it.toFloat(), 0, "kcal", previous?.bmrKcal?.toFloat(), null)) }
                report.fatFreeMassKg?.let { add(Stat(R.string.body_fat_free, it, 1, "kg", previous?.fatFreeMassKg, null)) }
            }
            items(cards.size) { i -> StatCard(cards[i], color, animate) }
        }
    }
}

private data class Stat(val label: Int, val value: Float, val decimals: Int, val unit: String, val previous: Float?, val range: Pair<BodyRange, Float>?)

/** 0 → 1 once, when the item first appears (lazy items compose as they scroll into view). */
@Composable
private fun appear(animate: Boolean, durationMs: Int = 900): Float {
    val a = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) }
    return a.value
}

@Composable
private fun BodyHero(report: BodyReport, color: Color, animate: Boolean) {
    val t = appear(animate, 1_200)
    val small = isSmallRound()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.metric_body), style = MaterialTheme.typography.titleSmall, color = color)
        Box(Modifier.size(if (small) 104.dp else 124.dp).padding(top = 4.dp), contentAlignment = Alignment.Center) {
            val segments = listOf(WearColors.warn, color, WearColors.onSurfaceVariant.copy(alpha = 0.45f))
            val shares = report.shares
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 9.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(WearColors.surfaceHigh, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (shares != null) {
                    var start = -90f
                    shares.forEachIndexed { i, share ->
                        val sweep = 360f * share * t
                        if (sweep > 0.5f) drawArc(segments[i], start + 1.5f, (sweep - 3f).coerceAtLeast(0.5f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                        start += 360f * share * t
                    }
                } else {
                    drawArc(WearColors.warn, -90f, 360f * report.bodyFatPercent / 100f * t, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CenteredValue(
                    "%.1f".format(report.bodyFatPercent * t),
                    "%",
                    if (small) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
                    MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.body_fat), style = MaterialTheme.typography.labelSmall, color = WearColors.onSurfaceVariant)
            }
        }
        if (report.shares != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Legend(WearColors.warn, R.string.body_legend_fat)
                Legend(color, R.string.body_legend_muscle)
                Legend(WearColors.onSurfaceVariant, R.string.body_legend_other)
            }
        }
        report.bodyType?.let { type ->
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .graphicsLayer { alpha = t; scaleX = 0.8f + 0.2f * t; scaleY = 0.8f + 0.2f * t }
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.18f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(stringResource(type.label), style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = WearColors.onSurfaceVariant)
    }
}

@Composable
private fun StatCard(stat: Stat, color: Color, animate: Boolean) {
    val t = appear(animate)
    val level = stat.range?.let { (range, v) -> range.level(v) }
    val side = LocalConfiguration.current.screenWidthDp.dp * 0.09f
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = side, vertical = 3.dp)
            .graphicsLayer { alpha = t; translationY = (1 - t) * 24.dp.toPx() }
            .clip(RoundedCornerShape(22.dp))
            .background(WearColors.surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(stat.label), style = MaterialTheme.typography.labelMedium, color = WearColors.onSurfaceVariant, modifier = Modifier.weight(1f))
            level?.let { Text(stringResource(it.label), style = MaterialTheme.typography.labelSmall, color = it.color) }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val shown = stat.value * t
            Text(
                if (stat.decimals == 0) "${shown.roundToInt()}" else "%.${stat.decimals}f".format(shown),
                style = MaterialTheme.typography.titleLarge,
            )
            if (stat.unit.isNotEmpty()) {
                Text(" ${stat.unit}", style = MaterialTheme.typography.bodySmall, color = WearColors.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
            Box(Modifier.weight(1f))
            stat.previous?.let { p ->
                val d = stat.value - p
                val shownDelta = if (stat.decimals == 0) "${d.roundToInt()}" else "%.1f".format(kotlin.math.abs(d))
                if (kotlin.math.abs(d) >= if (stat.decimals == 0) 1f else 0.05f) {
                    Text(
                        (if (d > 0) "▲ " else "▼ ") + shownDelta.removePrefix("-"),
                        style = MaterialTheme.typography.labelSmall,
                        color = WearColors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp).graphicsLayer { alpha = t },
                    )
                }
            }
        }
        stat.range?.let { (range, v) -> RangeBar(range, v, t, Modifier.fillMaxWidth().padding(top = 4.dp).height(10.dp)) }
    }
}

/** Low / standard / high bands with a marker that slides to the value. */
@Composable
private fun RangeBar(range: BodyRange, value: Float, t: Float, modifier: Modifier) {
    val low = BodyLevel.LOW.color
    val ok = BodyLevel.STANDARD.color
    val high = BodyLevel.HIGH.color
    val veryHigh = BodyLevel.VERY_HIGH.color
    Canvas(modifier) {
        val y = size.height / 2
        val h = 4.dp.toPx()
        val w = size.width
        fun x(v: Float) = range.position(v) * w
        val bands = buildList {
            add(Triple(0f, x(range.low), low))
            add(Triple(x(range.low), x(range.high), ok))
            val vh = range.veryHigh
            if (vh != null) {
                add(Triple(x(range.high), x(vh), high))
                add(Triple(x(vh), w, veryHigh))
            } else {
                add(Triple(x(range.high), w, high))
            }
        }
        bands.forEach { (a, b, c) -> if (b > a) drawLine(c.copy(alpha = 0.85f), Offset(a + 1.5f, y), Offset(b - 1.5f, y), strokeWidth = h, cap = StrokeCap.Round) }
        val mx = range.position(value) * size.width * t
        drawCircle(Color.White, radius = size.height / 2, center = Offset(mx.coerceIn(size.height / 2, size.width - size.height / 2), y))
        drawCircle(range.level(value).color, radius = size.height / 2 - 2.dp.toPx(), center = Offset(mx.coerceIn(size.height / 2, size.width - size.height / 2), y))
    }
}

private val BodyLevel.color: Color
    get() = when (this) {
        BodyLevel.LOW -> Color(0xFF64B5F6)
        BodyLevel.STANDARD -> WearColors.severity(Severity.NORMAL)
        BodyLevel.HIGH -> WearColors.severity(Severity.WARN)
        BodyLevel.VERY_HIGH -> WearColors.severity(Severity.ALERT)
    }

private val BodyLevel.label: Int
    get() = when (this) {
        BodyLevel.LOW -> R.string.level_low
        BodyLevel.STANDARD -> R.string.level_standard
        BodyLevel.HIGH -> R.string.level_high
        BodyLevel.VERY_HIGH -> R.string.level_very_high
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
