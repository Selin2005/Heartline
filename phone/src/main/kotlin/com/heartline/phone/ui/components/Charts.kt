package com.heartline.phone.ui.components

import androidx.compose.foundation.Canvas
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

/** One point on a day chart: minute of day (0..1439) with average and range. */
data class RangePoint(val minuteOfDay: Int, val avg: Int, val min: Int, val max: Int)

/**
 * Samsung Health-style day chart: faint min–max range per sample, the average as a line,
 * and hour labels underneath. Gaps longer than 10 minutes break the line.
 */
@Composable
fun DayRangeChart(points: List<RangePoint>, color: Color, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val colors = HeartlineTheme.colors
    Column(modifier.describe(contentDescription)) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val grid = colors.divider
            for (i in 0..3) {
                val y = size.height * i / 3
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            if (points.isEmpty()) return@Canvas
            val lo = (points.minOf { it.min } - 5).toFloat()
            val hi = (points.maxOf { it.max } + 5).toFloat()
            fun x(m: Int) = m / 1440f * size.width
            fun y(v: Int) = size.height - (v - lo) / (hi - lo) * size.height
            points.forEach { p ->
                drawLine(color.copy(alpha = 0.22f), Offset(x(p.minuteOfDay), y(p.max)), Offset(x(p.minuteOfDay), y(p.min)), strokeWidth = 3f)
            }
            val path = Path()
            var prev: RangePoint? = null
            points.forEach { p ->
                if (prev == null || p.minuteOfDay - prev!!.minuteOfDay > 10) path.moveTo(x(p.minuteOfDay), y(p.avg)) else path.lineTo(x(p.minuteOfDay), y(p.avg))
                prev = p
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "24").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

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
