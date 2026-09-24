package com.heartline.shared.dsp

import kotlin.math.abs

/**
 * Causal 0.5–40 Hz band-pass with 50/60 Hz notches for the live ECG strip. The raw watch signal
 * carries a DC offset and slow drift that would push a fixed-scale trace off screen.
 * After a touch (or any jump larger than [jumpMv]) the state is re-seeded on the new level, so the
 * settling step doesn't ring through the filter for seconds.
 */
class StreamingEcgFilter(fs: Int, mains: List<Double> = listOf(50.0, 60.0), private val jumpMv: Float = 1.5f) {
    private val stages = buildList {
        val f = fs.toDouble()
        add(Biquad.highPass(0.5, f).stream())
        add(Biquad.lowPass(40.0, f).stream())
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
