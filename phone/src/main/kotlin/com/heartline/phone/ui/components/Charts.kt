package com.heartline.phone.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.heartline.shared.hr.HrBuckets
import com.heartline.shared.hr.RangeBucket
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.heartline.phone.ui.theme.HeartlineTheme

/**
 * Heart-rate range chart (Samsung Health style): one rounded bar per interval from that interval's
 * lowest to highest heart rate, on a round-number axis at the right, with the resting heart rate as
 * a dashed guide. Tap a bar to see its time and range; the selected bar is highlighted.
 *
 * @param slots how many intervals the x-axis holds (144 ten-minute slots for a day, 7 or 30 days).
 * @param label text for a bucket index, shown when it is selected.
 */
@Composable
fun RangeBarChart(
    buckets: List<RangeBucket>,
    slots: Int,
    color: Color,
    xLabels: List<String>,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    resting: Int? = null,
    initiallySelected: Int? = null,
    contentDescription: String? = null,
) {
    val colors = HeartlineTheme.colors
    val axis = remember(buckets) { HrBuckets.axis(buckets) }
    var selected by remember(buckets) { mutableStateOf(initiallySelected?.let { i -> buckets.firstOrNull { it.index == i } }) }
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    Column(modifier.describe(contentDescription)) {
        // Selected interval, or the whole period's range.
        val header = selected?.let { "${label(it.index)} · ${it.min}–${it.max}" }
            ?: buckets.takeIf { it.isNotEmpty() }?.let { "${it.minOf { b -> b.min }}–${it.maxOf { b -> b.max }}" }
        Text(
            header?.let { "$it bpm" } ?: "–",
            style = MaterialTheme.typography.labelLarge,
            color = if (selected != null) color else colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .pointerInput(buckets) {
                    detectTapGestures { offset ->
                        val plot = size.width - AXIS_WIDTH.toPx()
                        val index = (offset.x / plot * slots).toInt()
                        selected = buckets.minByOrNull { kotlin.math.abs(it.index - index) }?.takeIf { kotlin.math.abs(it.index - index) <= maxOf(1, slots / 48) }
                    }
                },
        ) {
            val plotWidth = size.width - AXIS_WIDTH.toPx()
            val span = (axis.last - axis.first).toFloat()
            fun y(v: Int) = size.height - (v - axis.first) / span * size.height
            // Horizontal grid at round values, labelled on the right.
            val step = if (span > 80) 40 else 20
            var g = (axis.first + step - 1) / step * step
            while (g <= axis.last) {
                drawLine(colors.divider, Offset(0f, y(g)), Offset(plotWidth, y(g)), strokeWidth = 1f)
                val text = textMeasurer.measure("$g", axisStyle)
                val top = (y(g) - text.size.height / 2).coerceIn(0f, size.height - text.size.height)
                drawText(text, topLeft = Offset(plotWidth + 6.dp.toPx(), top))
                g += step
            }
            resting?.takeIf { it in axis }?.let { r ->
                drawLine(
                    colors.onSurfaceVariant.copy(alpha = 0.6f),
                    Offset(0f, y(r)),
                    Offset(plotWidth, y(r)),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
            val slot = plotWidth / slots
            val barWidth = (slot * 0.62f).coerceIn(2.dp.toPx(), 14.dp.toPx())
            buckets.forEach { b ->
                val x = b.index * slot + slot / 2
                val top = y(b.max)
                // A single value still shows as a round dot.
                val bottom = maxOf(y(b.min), top + barWidth)
                val isSelected = selected?.index == b.index
                drawRoundRect(
                    if (selected == null || isSelected) color else color.copy(alpha = 0.35f),
                    Offset(x - barWidth / 2, top),
                    Size(barWidth, bottom - top),
                    CornerRadius(barWidth / 2),
                )
            }
            selected?.let { b ->
                val x = b.index * slot + slot / 2
                drawLine(color.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = AXIS_WIDTH), horizontalArrangement = Arrangement.SpaceBetween) {
            xLabels.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

private val AXIS_WIDTH = 30.dp

/** Rounded bars for a week; null values show an empty track. */
@Composable
fun WeekBars(values: List<Float?>, labels: List<String>, color: Color, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val colors = HeartlineTheme.colors
    Column(modifier.describe(contentDescription)) {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val max = (values.filterNotNull().maxOrNull() ?: 1f).coerceAtLeast(1f)
            val slot = size.width / values.size
            val barWidth = slot * 0.42f
            values.forEachIndexed { i, v ->
                val left = i * slot + (slot - barWidth) / 2
                drawRoundRect(colors.surfaceVariant, Offset(left, 0f), Size(barWidth, size.height), CornerRadius(barWidth / 2))
                if (v != null) {
                    val h = (v / max) * size.height
                    drawRoundRect(color, Offset(left, size.height - h), Size(barWidth, h), CornerRadius(barWidth / 2))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
