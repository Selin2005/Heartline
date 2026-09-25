package com.heartline.shared.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Second-order IIR section (RBJ audio-EQ cookbook), Direct Form I. */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double
) {
    fun apply(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        // Start from a steady state on the first sample to avoid a large step transient.
        if (input.isNotEmpty()) {
            val dcGain = (b0 + b1 + b2) / (1 + a1 + a2)
            x1 = input[0].toDouble()
            x2 = x1
            y1 = if (dcGain.isFinite()) x1 * dcGain else 0.0
            y2 = y1
        }
        for (i in input.indices) {
            val x = input[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            out[i] = y.toFloat()
        }
        return out
    }

    /** Sample-by-sample state for live (causal) filtering. */
    inner class Stream {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        /** Sets the state as if [x] had been the input forever (no step transient). */
        fun settle(x: Double) {
            val dcGain = (b0 + b1 + b2) / (1 + a1 + a2)
            x1 = x
            x2 = x
            y1 = if (dcGain.isFinite()) x * dcGain else 0.0
            y2 = y1
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    fun stream() = Stream()

    companion object {
        private val Q_BUTTERWORTH = 1 / sqrt(2.0)

        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun lowPass(cutoffHz: Double, fs: Double, q: Double = Q_BUTTERWORTH): Biquad {
            val w = 2 * PI * cutoffHz / fs
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun highPass(cutoffHz: Double, fs: Double, q: Double = Q_BUTTERWORTH): Biquad {
            val w = 2 * PI * cutoffHz / fs
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        /**
         * The notch of scipy.signal.iirnotch (bandwidth w0/Q at −3 dB), for models whose reference
         * preprocessing uses it (ECGFounder). Slightly different from the cookbook [notch].
         */
        fun iirNotch(centerHz: Double, fs: Double, q: Double = 30.0): Biquad {
            val w0 = 2 * PI * centerHz / fs
            val beta = kotlin.math.tan(w0 / q / 2)
            val gain = 1 / (1 + beta)
            val c = cos(w0)
            return Biquad(gain, -2 * gain * c, gain, -2 * gain * c, 2 * gain - 1)
        }

        fun notch(centerHz: Double, fs: Double, q: Double = 30.0): Biquad {
            val w = 2 * PI * centerHz / fs
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make(1.0, -2 * c, 1.0, 1 + alpha, -2 * c, 1 - alpha)
        }
    }
}

/** Forward-backward filtering: zero phase, squared magnitude response. For offline analysis. */
fun FloatArray.filtFilt(vararg stages: Biquad): FloatArray {
    var x = this
    for (s in stages) x = s.apply(x)
    x.reverse()
    for (s in stages) x = s.apply(x)
    x.reverse()
    return x
}
