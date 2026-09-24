package com.heartline.wear.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.heartline.shared.dsp.decimate

/**
 * Live waveform band: the newest sample is on the right and the trace fades in from the left,
 * like the SHM measuring screen.
 */
@Composable
fun LiveWave(samples: FloatArray, color: Color, modifier: Modifier = Modifier, fixedRangeMv: Float? = 1.6f) {
    val points = remember(samples) { decimate(samples, 240) }
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        // ECG uses a fixed mV scale; other signals (PPG, arbitrary units) are auto-scaled.
        val lo = if (fixedRangeMv == null) points.min() else 0f
        val range = fixedRangeMv ?: (points.max() - lo).coerceAtLeast(1e-3f)
        val mid = if (fixedRangeMv == null) size.height + lo * size.height / range else size.height * 0.6f
        val scale = size.height / range * (if (fixedRangeMv == null) 0.9f else 1f)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i * size.width / (points.size - 1)
            val y = (mid - v * scale).coerceIn(0f, size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color.copy(alpha = 0.6f), color)),
            style = Stroke(width = 2.2.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round),
        )
    }
}
