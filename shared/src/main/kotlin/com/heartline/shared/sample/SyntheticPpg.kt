// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sample

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic wrist PPG (arbitrary units, 100 Hz): each pulse is a systolic Gaussian plus a
 * reflected diastolic wave. [stiffness] 0..1 models stiffer arteries / higher pressure:
 * faster upstroke, earlier and larger reflection, narrower pulse.
 */
object SyntheticPpg {
    const val SAMPLE_RATE_HZ = 100

    fun generate(seconds: Double, heartRateBpm: Double = 70.0, stiffness: Double = 0.5, noise: Double = 0.01, seed: Int = 1): FloatArray {
        val random = Random(seed)
        val n = (seconds * SAMPLE_RATE_HZ).toInt()
        val out = FloatArray(n)
        val rr = 60.0 / heartRateBpm
        val sysWidth = 0.075 - 0.025 * stiffness
        val reflectDelay = 0.30 - 0.12 * stiffness
        val reflectAmp = 0.30 + 0.35 * stiffness
        var beat = 0.1
        while (beat < seconds + 1) {
            val beatRr = rr * (1 + 0.02 * (random.nextDouble() * 2 - 1))
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE_HZ - beat
                if (t < -0.2 || t > 1.2) continue
                val sys = exp(-0.5 * ((t - 0.12) / sysWidth).let { it * it })
                val dia = reflectAmp * exp(-0.5 * ((t - 0.12 - reflectDelay) / (sysWidth * 1.6)).let { it * it })
                out[i] += (sys + dia).toFloat()
            }
            beat += beatRr
        }
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE_HZ
            out[i] += (0.15 * sin(2 * PI * 0.2 * t) + noise * (random.nextDouble() * 2 - 1)).toFloat()
        }
        return out
    }
}
