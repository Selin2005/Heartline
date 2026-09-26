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

    /**
     * A more physiological recording for algorithm-5 scenarios. Unlike [generate]:
     * - the pulse rate can ramp from [heartRateStart] to [heartRateEnd] over the recording;
     * - ejection shortens with rate (LVET ≈ 413 − 1.7·HR ms, Weissler 1968), so the pulse
     *   narrows at a fast rate even when the arteries are unchanged;
     * - the pulse amplitude can ramp ([amplitudeStart] → [amplitudeEnd]) and the output is raw
     *   watch-style light intensity (large offset, upside down) with a perfusion index of about
     *   [perfusionIndex] % × amplitude;
     * - [ectopicBeats]: indices of premature beats (early, weak, then a compensatory pause and a
     *   stronger beat); [irregular]: atrial-fibrillation-like random intervals (± that share).
     */
    data class Scenario(
        val seconds: Double = 20.0,
        val heartRateStart: Double = 70.0,
        val heartRateEnd: Double = heartRateStart,
        val stiffness: Double = 0.5,
        val amplitudeStart: Double = 1.0,
        val amplitudeEnd: Double = amplitudeStart,
        val perfusionIndex: Double = 1.0,
        val ectopicBeats: Set<Int> = emptySet(),
        val irregular: Double = 0.0,
        val noise: Double = 0.01,
        val seed: Int = 1
    )

    fun scenario(s: Scenario): FloatArray {
        val random = Random(s.seed)
        val n = (s.seconds * SAMPLE_RATE_HZ).toInt()
        val pulse = FloatArray(n)
        var beat = 0.1
        var index = 0
        var boost = 1.0
        while (beat < s.seconds + 1) {
            val progress = (beat / s.seconds).coerceIn(0.0, 1.0)
            val hr = s.heartRateStart + (s.heartRateEnd - s.heartRateStart) * progress
            val amp = s.amplitudeStart + (s.amplitudeEnd - s.amplitudeStart) * progress
            val rr = 60.0 / hr
            val ejection = ((413 - 1.7 * hr) / (413 - 1.7 * 70)).coerceIn(0.5, 1.3)
            val sysWidth = (0.075 - 0.025 * s.stiffness) * ejection
            val sysPeak = 0.12 * ejection
            val reflectDelay = (0.30 - 0.12 * s.stiffness) * ejection
            val reflectAmp = 0.30 + 0.35 * s.stiffness
            var height = amp * boost
            boost = 1.0
            var next = rr * (1 + 0.02 * (random.nextDouble() * 2 - 1))
            if (s.irregular > 0) next = rr * (1 + s.irregular * (random.nextDouble() * 2 - 1))
            if (index + 1 in s.ectopicBeats) next = rr * 0.65
            if (index in s.ectopicBeats) {
                // Weak premature beat, compensatory pause, then a stronger beat.
                height *= 0.6
                next = rr * 1.35
                boost = 1.3
            }
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE_HZ - beat
                if (t < -0.2 || t > 1.2) continue
                val sys = exp(-0.5 * ((t - sysPeak) / sysWidth).let { it * it })
                val dia = reflectAmp * exp(-0.5 * ((t - sysPeak - reflectDelay) / (sysWidth * 1.6)).let { it * it })
                pulse[i] += (height * (sys + dia)).toFloat()
            }
            beat += next
            index++
        }
        // Raw light intensity: offset DC, falls as blood volume rises (Galaxy Watch green PPG).
        val dc = 200_000.0
        val gain = dc * s.perfusionIndex / 100.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE_HZ
            val v = pulse[i] + 0.15 * sin(2 * PI * 0.2 * t) + s.noise * (random.nextDouble() * 2 - 1)
            (dc - gain * v).toFloat()
        }
    }
}
