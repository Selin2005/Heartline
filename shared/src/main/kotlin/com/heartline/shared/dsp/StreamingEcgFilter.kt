package com.heartline.shared.dsp

import kotlin.math.abs

/**
 * Causal 0.5–40 Hz band-pass with 50/60 Hz notches for the live ECG strip. The raw watch signal
 * carries a DC offset and slow drift that would push a fixed-scale trace off screen.
 * After a touch (or any jump larger than [jumpMv]) the state is re-seeded on the new level, so the
 * settling step doesn't ring through the filter for seconds.
 */
open class StreamingEcgFilter(
    fs: Int,
    mains: List<Double> = listOf(50.0, 60.0),
    private val jumpMv: Float = 1.5f,
    lowHz: Double = 0.5,
    highHz: Double = 40.0
) {
    private val stages = buildList {
        val f = fs.toDouble()
        add(Biquad.highPass(lowHz, f).stream())
        add(Biquad.lowPass(highHz, f).stream())
        mains.filter { it < f / 2 }.forEach { add(Biquad.notch(it, f).stream()) }
    }
    private var last: Float? = null

    fun process(chunk: FloatArray): FloatArray = FloatArray(chunk.size) { i ->
        val x = chunk[i]
        val previous = last
        if (!x.isFinite()) return@FloatArray 0f
        if (previous == null || abs(x - previous) > jumpMv) reseed(x)
        last = x
        var y = x.toDouble()
        stages.forEach { y = it.process(y) }
        y.toFloat()
    }

    /** Call when contact starts again. */
    fun reset() {
        last = null
    }

    /** Constant input [x]: the high-pass settles on it (output 0), so every later stage settles on 0. */
    private fun reseed(x: Float) {
        stages.forEachIndexed { i, stage -> stage.settle(if (i == 0) x.toDouble() else 0.0) }
    }
}

/**
 * Live PPG for display: 0.5–8 Hz band-pass. Raw watch PPG is light intensity in large ADC counts
 * (and upside down); a jump of [jump] counts (contact change) re-seeds the filter.
 */
class StreamingPpgFilter(fs: Int, jump: Float = Float.MAX_VALUE) :
    StreamingEcgFilter(fs, mains = emptyList(), jumpMv = jump, lowHz = 0.5, highHz = 8.0)
