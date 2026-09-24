package com.heartline.phone.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.dsp.decimate

/**
 * An ECG strip drawn on standard paper: 25 mm/s, 10 mm/mV, 1 mm minor and 5 mm major grid.
 * [mmSize] is the on-screen size of one millimetre of paper.
 */
@Composable
fun EcgStrip(
    samples: FloatArray,
    sampleRateHz: Int,
    modifier: Modifier = Modifier,
    mmSize: Dp = 4.dp,
    heightMm: Int = 30,
    contentDescription: String? = null,
    noisySeconds: List<Int> = emptyList(),
) {
    val colors = HeartlineTheme.colors
    val widthMm = samples.size.toFloat() / sampleRateHz * MM_PER_SECOND
    Canvas(modifier.width(mmSize * widthMm).height(mmSize * heightMm).describe(contentDescription)) {
        val mm = mmSize.toPx()
        val cols = widthMm.toInt()
        for (i in 0..cols) {
            val x = i * mm
            drawLine(
                if (i % 5 == 0) colors.ecgGridMajor else colors.ecgGridMinor,
                Offset(x, 0f),
                Offset(x, size.height),
                strokeWidth = if (i % 5 == 0) 1.2f else 0.8f,
            )
        }
        for (j in 0..heightMm) {
            val y = j * mm
            drawLine(
                if (j % 5 == 0) colors.ecgGridMajor else colors.ecgGridMinor,
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = if (j % 5 == 0) 1.2f else 0.8f,
            )
        }
        // Seconds the analysis treated as noise are shaded, so the user sees which part didn't count.
        noisySeconds.forEach { s ->
            drawRect(colors.statusWarn.copy(alpha = 0.12f), Offset(s * MM_PER_SECOND * mm, 0f), Size(MM_PER_SECOND * mm, size.height))
        }
        val baselineY = size.height * 0.62f
        val pxPerSample = MM_PER_SECOND * mm / sampleRateHz
        val pxPerMv = MM_PER_MV * mm
        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = i * pxPerSample
            val y = baselineY - v * pxPerMv
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, colors.ecgTrace, style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
    }
}

/** Small, grid-less waveform preview for cards and list rows. */
@Composable
fun MiniWave(samples: FloatArray, color: Color, modifier: Modifier = Modifier, strokeWidth: Dp = 1.8.dp, contentDescription: String? = null) {
    val decimated = remember(samples) { decimate(samples, 400) }
    Canvas(modifier.describe(contentDescription)) {
        if (decimated.isEmpty()) return@Canvas
        val min = decimated.min()
        val max = decimated.max()
        val range = (max - min).coerceAtLeast(0.1f)
        val path = Path()
        decimated.forEachIndexed { i, v ->
            val x = i * size.width / (decimated.size - 1)
            val y = size.height - (v - min) / range * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = strokeWidth.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
    }
}

const val MM_PER_SECOND = 25f
const val MM_PER_MV = 10f

/** Charts are drawn on a Canvas; this gives TalkBack a spoken summary instead. */
fun Modifier.describe(description: String?): Modifier =
    if (description == null) this else semantics { contentDescription = description }
