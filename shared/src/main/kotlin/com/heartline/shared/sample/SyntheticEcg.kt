package com.heartline.shared.sample

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates a realistic-looking single-lead ECG (mV) as a sum of Gaussian P, Q, R, S and T
 * waves per beat. Used by previews, screenshots and the fake sensor.
 */
object SyntheticEcg {
    const val SAMPLE_RATE_HZ = 500

    private data class Wave(val center: Double, val width: Double, val amplitude: Double)

    // Offsets are fractions of a 1 s beat; they are scaled by sqrt(RR) (Bazett-like).
    private val template = listOf(
        Wave(-0.20, 0.025, 0.12),
        Wave(-0.035, 0.010, -0.10),
        Wave(0.0, 0.011, 1.05),
        Wave(0.035, 0.011, -0.22),
        Wave(0.28, 0.045, 0.28)
    )

    /**
     * @param irregularity 0 = perfectly regular; ~0.25 gives AFib-like beat-to-beat variation
     *   (P waves are also suppressed when > 0.15).
     */
    fun generate(
        durationSec: Double,
        heartRateBpm: Double = 72.0,
        irregularity: Double = 0.02,
        noiseMv: Double = 0.015,
        seed: Int = 7
    ): FloatArray {
        val random = Random(seed)
        val n = (durationSec * SAMPLE_RATE_HZ).toInt()
        val out = FloatArray(n)
        val meanRr = 60.0 / heartRateBpm
        val pScale = if (irregularity > 0.15) 0.15 else 1.0

        var beat = 0.25
        while (beat < durationSec + 1) {
            val rr = meanRr * (1 + irregularity * (random.nextDouble() * 2 - 1))
            val scale = kotlin.math.sqrt(meanRr)
            for ((index, wave) in template.withIndex()) {
                val amp = if (index == 0) wave.amplitude * pScale else wave.amplitude
                val center = beat + wave.center * scale
                val width = wave.width * scale
                val from = ((center - 5 * width) * SAMPLE_RATE_HZ).toInt().coerceAtLeast(0)
                val to = ((center + 5 * width) * SAMPLE_RATE_HZ).toInt().coerceAtMost(n - 1)
                for (i in from..to) {
                    val t = i.toDouble() / SAMPLE_RATE_HZ
                    val d = (t - center) / width
                    out[i] += (amp * exp(-0.5 * d * d)).toFloat()
                }
            }
            beat += rr
        }
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE_HZ
            val wander = 0.04 * sin(2 * PI * 0.25 * t)
            out[i] += (wander + noiseMv * (random.nextDouble() * 2 - 1)).toFloat()
        }
        return out
    }
}
