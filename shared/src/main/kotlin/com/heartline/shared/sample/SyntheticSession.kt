// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sample

import com.heartline.shared.bp.BpSessionInput
import com.heartline.shared.bp.ImuStreams
import com.heartline.shared.bp.PreciseInput
import com.heartline.shared.bp.SensorStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A synthetic multi-sensor session with a known pressure (algorithm-6 tests): every channel is
 * generated from the same beats, so their timings agree the way real physiology makes them.
 *
 * Each beat starts ejecting at t_e. The ECG R peak is [Spec.pepMs] earlier (pre-ejection
 * period); the ballistocardiogram's I wave is at t_e, J 40 ms later; the wrist PPG pulse starts
 * after the transit time, which shortens by 1/[Spec.mmHgPerMs] ms per mmHg of systolic pressure
 * and lengthens over the arm segment when the hand is raised (lower local pressure). Raising the
 * arm also changes the PPG amplitude along a bell curve centred where the hydrostatic drop equals
 * the mean pressure (Shaltis 2008).
 */
object SyntheticSession {
    const val MMHG_PER_CM = 0.78

    data class Spec(
        val seconds: Double = 30.0,
        val heartRateStart: Double = 70.0,
        val heartRateEnd: Double = heartRateStart,
        val systolic: Double = 110.0,
        val diastolic: Double = 72.0,
        /** Pulse-wave shape stiffness 0..1 for the green and infrared PPG (vasoconstriction narrows green more). */
        val stiffness: Double = 0.4,
        val irStiffness: Double = stiffness,
        val perfusionIndex: Double = 1.0,
        val irPerfusionIndex: Double = 0.6,
        /** Transit time heart → wrist at [refSystolic], ms, and its pressure sensitivity (mmHg per ms, negative). */
        val pttMs: Double = 180.0,
        val refSystolic: Double = 110.0,
        val mmHgPerMs: Double = -0.8,
        val pepMs: Double = 100.0,
        val armFraction: Double = 0.5,
        /** Forearm elevation over time, degrees (positive: hand up), and the shoulder → wrist length. */
        val pitchDeg: (Double) -> Double = { 0.0 },
        val armCm: Double = 56.0,
        val bcgAmplitude: Double = 0.03,
        val accelNoise: Double = 0.01,
        val accelRateHz: Double = 200.0,
        val irregular: Double = 0.0,
        val precise: Boolean = false,
        val withIr: Boolean = true,
        val withImu: Boolean = true,
        val startNs: Long = 1_700_000_000_000_000_000L,
        val seed: Int = 1
    ) {
        val map: Double get() = diastolic + (systolic - diastolic) / 3.0
    }

    data class Session(val input: BpSessionInput, val ejectionNs: LongArray, val spec: Spec)

    fun generate(spec: Spec): Session {
        val random = Random(spec.seed)
        val fs = if (spec.precise) 500 else 100
        val n = (spec.seconds * fs).toInt()
        // Beats (ejection starts), seconds from the start.
        val ejections = mutableListOf<Double>()
        var t = 0.3
        while (t < spec.seconds + 1) {
            ejections += t
            val hr = spec.heartRateStart + (spec.heartRateEnd - spec.heartRateStart) * (t / spec.seconds).coerceIn(0.0, 1.0)
            val jitter = if (spec.irregular > 0) spec.irregular else 0.02
            t += 60.0 / hr * (1 + jitter * (random.nextDouble() * 2 - 1))
        }
        fun offsetAt(sec: Double) = MMHG_PER_CM * spec.armCm * sin(spec.pitchDeg(sec) * PI / 180)
        fun transit(sec: Double) = spec.pttMs + (spec.systolic - spec.refSystolic) / spec.mmHgPerMs +
            spec.armFraction * offsetAt(sec) / -spec.mmHgPerMs

        // Amplitude along the Shaltis bell: largest where the drop reaches the mean pressure.
        fun amplitude(sec: Double): Double {
            val transmural = spec.map - offsetAt(sec) - 5.0
            return 1.0 + 1.5 * exp(-transmural * transmural / (2 * 15.0 * 15.0))
        }

        fun ppg(stiffness: Double, pi: Double): FloatArray {
            val pulse = DoubleArray(n)
            for (e in ejections) {
                val foot = e + transit(e) / 1000
                val hr = spec.heartRateStart + (spec.heartRateEnd - spec.heartRateStart) * (e / spec.seconds).coerceIn(0.0, 1.0)
                val ejection = ((413 - 1.7 * hr) / (413 - 1.7 * 70)).coerceIn(0.5, 1.3)
                val sysWidth = (0.075 - 0.025 * stiffness) * ejection
                val sysPeak = 0.12 * ejection
                val reflectDelay = (0.30 - 0.12 * stiffness) * ejection
                val reflectAmp = 0.30 + 0.35 * stiffness
                val a = amplitude(foot)
                val from = ((foot - 0.2) * fs).toInt().coerceAtLeast(0)
                val to = ((foot + 1.2) * fs).toInt().coerceAtMost(n - 1)
                for (i in from..to) {
                    val tt = i.toDouble() / fs - foot
                    val sys = exp(-0.5 * ((tt - sysPeak) / sysWidth).let { it * it })
                    val dia = reflectAmp * exp(-0.5 * ((tt - sysPeak - reflectDelay) / (sysWidth * 1.6)).let { it * it })
                    pulse[i] += a * (sys + dia)
                }
            }
            val dc = 200_000.0
            val gain = dc * pi / 100.0
            return FloatArray(n) { i ->
                val tt = i.toDouble() / fs
                (dc - gain * (pulse[i] + 0.1 * sin(2 * PI * 0.2 * tt) + 0.01 * (random.nextDouble() * 2 - 1))).toFloat()
            }
        }
        val green = ppg(spec.stiffness, spec.perfusionIndex)
        val times = LongArray(n) { spec.startNs + (it * 1e9 / fs).toLong() }
        val ir = if (spec.withIr && !spec.precise) ppg(spec.irStiffness, spec.irPerfusionIndex) else null

        val imu = if (spec.withImu) ImuStreams(accel(spec, ejections, random), null, null) else ImuStreams.EMPTY
        val precise = if (spec.precise) {
            val ecg = FloatArray(n)
            for (e in ejections) {
                val r = e - spec.pepMs / 1000
                val from = ((r - 0.1) * fs).toInt().coerceAtLeast(0)
                val to = ((r + 0.4) * fs).toInt().coerceAtMost(n - 1)
                for (i in from..to) {
                    val tt = i.toDouble() / fs - r
                    ecg[i] += (
                        1.2 * exp(-0.5 * (tt / 0.008).let { it * it }) - 0.15 * exp(-0.5 * ((tt - 0.025) / 0.01).let { it * it }) +
                            0.3 * exp(-0.5 * ((tt - 0.28) / 0.04).let { it * it })
                        ).toFloat()
                }
            }
            for (i in 0 until n) ecg[i] += (0.01 * (random.nextDouble() * 2 - 1)).toFloat()
            val raised = raisedWindow(spec)
            PreciseInput(ecg, green, fs, spec.startNs, raised)
        } else {
            null
        }
        return Session(
            BpSessionInput(
                green = if (spec.precise) green.copyOf() else green,
                fs = fs,
                greenTimesNs = times,
                ir = ir,
                imu = imu,
                precise = precise
            ),
            LongArray(ejections.size) { spec.startNs + (ejections[it] * 1e9).toLong() },
            spec
        )
    }

    /** The time window where the arm is raised above 45°, if any. */
    private fun raisedWindow(spec: Spec): LongRange? {
        val up = (0 until (spec.seconds * 10).toInt()).map { it / 10.0 }.filter { spec.pitchDeg(it) > 45 }
        if (up.isEmpty()) return null
        return spec.startNs + (up.first() * 1e9).toLong()..spec.startNs + (up.last() * 1e9).toLong()
    }

    /** Gravity from the forearm angle (watch x along the forearm) plus the ballistocardiogram on x. */
    private fun accel(spec: Spec, ejections: List<Double>, random: Random): SensorStream {
        val n = (spec.seconds * spec.accelRateHz).toInt()
        val t = LongArray(n)
        val v = FloatArray(n * 3)
        for (i in 0 until n) {
            val sec = (i + 0.2 * (random.nextDouble() - 0.5)) / spec.accelRateHz
            t[i] = spec.startNs + (sec * 1e9).toLong()
            val p = spec.pitchDeg(sec) * PI / 180
            var bcg = 0.0
            for (e in ejections) {
                val d = sec - e
                if (d < -0.2 || d > 0.4) continue
                // H, I, J, K waves (I at ejection start, J 40 ms later).
                bcg += 0.3 * exp(-0.5 * ((d + 0.04) / 0.012).let { it * it }) -
                    0.6 * exp(-0.5 * (d / 0.012).let { it * it }) +
                    1.0 * exp(-0.5 * ((d - 0.04) / 0.012).let { it * it }) -
                    0.5 * exp(-0.5 * ((d - 0.09) / 0.015).let { it * it })
            }
            fun noise() = spec.accelNoise * (random.nextDouble() * 2 - 1)
            v[i * 3] = (9.81 * sin(p) + spec.bcgAmplitude * bcg + noise()).toFloat()
            v[i * 3 + 1] = noise().toFloat()
            v[i * 3 + 2] = (9.81 * cos(p) + noise()).toFloat()
        }
        return SensorStream(ImuStreams.ACCEL, listOf("x", "y", "z"), t, v)
    }
}
