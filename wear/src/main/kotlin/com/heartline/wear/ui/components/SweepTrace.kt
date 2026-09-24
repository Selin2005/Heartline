package com.heartline.wear.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Live trace drawn like a bedside monitor: a write head sweeps left to right over a fixed window
 * and overwrites the previous sweep, leaving a short erase gap ahead of it. The picture stays
 * still (no scrolling), so the beat shape is easy to read.
 *
 * @param samples the most recent samples, oldest first (at most one window).
 * @param endIndex total samples received so far; positions the write head.
 * @param paper draw ECG-paper squares (0.2 s wide, same size vertically).
 * @param centered true for signals that swing around zero (filtered ECG); false for PPG-like
 *   signals, which are auto-ranged between their min and max.
 */
@Composable
fun SweepTrace(
    samples: FloatArray,
    endIndex: Long,
    windowSamples: Int,
    color: Color,
    modifier: Modifier = Modifier,
    paper: Boolean = true,
    centered: Boolean = true,
    minRange: Float = 0.3f,
    samplesPerSquare: Int = windowSamples / 15,
) {
    // Auto-gain from a robust amplitude, eased so the trace doesn't jump from beat to beat.
    val target = remember(samples) { robustRange(samples, centered).coerceAtLeast(minRange) }
    val range by animateFloatAsState(target, tween(700), label = "gain")
    val offset = remember(samples) { if (centered) 0f else samples.minOrNull() ?: 0f }
    Canvas(modifier) {
        if (paper) drawPaper(color, samplesPerSquare.toFloat() / windowSamples)
        if (samples.size < 2) {
            drawLine(color.copy(alpha = 0.45f), Offset(0f, size.height * 0.55f), Offset(size.width, size.height * 0.55f), 1.5.dp.toPx())
            return@Canvas
        }
        val gap = windowSamples / 25
        val baseline = if (centered) size.height * 0.58f else size.height * 0.92f
        val scale = if (centered) size.height * 0.46f / range else size.height * 0.84f / range
        val path = Path()
        var previousSlot = -1
        val first = endIndex - samples.size
        var head = Offset.Zero
        for (k in gap until samples.size) {
            val slot = ((first + k) % windowSamples).toInt()
            val x = slot * size.width / (windowSamples - 1)
            val y = (baseline - (samples[k] - offset) * scale).coerceIn(1f, size.height - 1f)
            if (previousSlot < 0 || slot < previousSlot) path.moveTo(x, y) else path.lineTo(x, y)
            previousSlot = slot
            head = Offset(x, y)
        }
        // Soft glow under a crisp line.
        drawPath(path, color.copy(alpha = 0.22f), style = Stroke(width = 5.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
        drawPath(path, color, style = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
        drawCircle(color, radius = 2.8.dp.toPx(), center = head)
        drawCircle(Color.White.copy(alpha = 0.8f), radius = 1.2.dp.toPx(), center = head)
    }
}

/** ECG-paper squares; every fifth line (1 s at the default scale) slightly stronger. */
private fun DrawScope.drawPaper(color: Color, squareFraction: Float) {
    val step = size.width * squareFraction
    if (step < 2f) return
    val minor = color.copy(alpha = 0.10f)
    val major = color.copy(alpha = 0.22f)
    var i = 0
    var x = 0f
    while (x <= size.width + 0.5f) {
        drawLine(if (i % 5 == 0) major else minor, Offset(x, 0f), Offset(x, size.height), 1f)
        x += step
        i++
    }
    var y = size.height * 0.58f
    while (y >= 0f) {
        drawLine(minor, Offset(0f, y), Offset(size.width, y), 1f)
        y -= step
    }
    y = size.height * 0.58f + step
    while (y <= size.height) {
        drawLine(minor, Offset(0f, y), Offset(size.width, y), 1f)
        y += step
    }
}

/** 98th-percentile amplitude (centred) or spread (uncentred), ignoring a few outliers. */
private fun robustRange(samples: FloatArray, centered: Boolean): Float {
    if (samples.isEmpty()) return 0f
    val values = if (centered) samples.map { abs(it) }.sorted() else samples.sorted()
    val hi = values[((values.size - 1) * 0.98).toInt()]
    return if (centered) hi else hi - values[((values.size - 1) * 0.02).toInt()]
}
