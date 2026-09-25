package com.heartline.wear.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.math.exp

/**
 * Bedside-monitor view of the live heart rate: a sweeping trace with one complex per heartbeat,
 * paced by the measured rate. The wrist sensor measures the pulse optically (no electrical
 * signal), so the complex is a drawn beat marker, not a recorded ECG; the real ECG is the ECG
 * measurement.
 */
@Composable
fun HeartMonitor(bpm: Int?, color: Color, modifier: Modifier = Modifier, animate: Boolean = true) {
    val rate by rememberUpdatedState(bpm)
    val buffer = remember { MonitorBuffer(WINDOW) }
    var written by remember { mutableLongStateOf(if (animate) 0L else buffer.prefill(bpm ?: 66)) }
    if (animate) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (last == 0L) last = now
                    val due = ((now - last) / NANOS_PER_SAMPLE).toInt()
                    if (due > 0) {
                        last += due * NANOS_PER_SAMPLE
                        repeat(due.coerceAtMost(WINDOW)) { buffer.next(rate) }
                        written = buffer.count
                    }
                }
            }
        }
    }
    SweepTrace(
        samples = buffer.snapshot(written),
        endIndex = written,
        windowSamples = WINDOW,
        color = color,
        modifier = modifier,
        minRange = 1f,
    )
}

private const val RATE_HZ = 100
private const val WINDOW = 3 * RATE_HZ
private const val NANOS_PER_SAMPLE = 1_000_000_000L / RATE_HZ

/** Generates the trace sample by sample: a new complex starts every 60/bpm seconds. */
private class MonitorBuffer(private val size: Int) {
    private val ring = FloatArray(size)
    var count = 0L
        private set
    private var sinceBeat = 0

    fun next(bpm: Int?) {
        val period = bpm?.takeIf { it in 25..240 }?.let { RATE_HZ * 60 / it }
        val value = if (period == null) {
            sinceBeat = 0
            0f
        } else {
            if (sinceBeat >= period) sinceBeat = 0
            complex(sinceBeat++ / RATE_HZ.toFloat())
        }
        ring[(count % size).toInt()] = value
        count++
    }

    fun prefill(bpm: Int): Long {
        repeat(size * 2 - size / 6) { next(bpm) }
        return count
    }

    /** The last window, oldest first. */
    fun snapshot(end: Long): FloatArray {
        val n = minOf(end, size.toLong()).toInt()
        return FloatArray(n) { i -> ring[((end - n + i) % size).toInt()] }
    }
}

/** A clean P-QRS-T shape (in mV-like units) [t] seconds after the beat starts. */
private fun complex(t: Float): Float {
    fun bump(centre: Float, width: Float, height: Float) = height * exp(-((t - centre) * (t - centre)) / (2 * width * width))
    return bump(0.08f, 0.022f, 0.12f) +
        bump(0.175f, 0.008f, -0.12f) +
        bump(0.20f, 0.010f, 1.0f) +
        bump(0.225f, 0.010f, -0.25f) +
        bump(0.45f, 0.040f, 0.28f)
}
