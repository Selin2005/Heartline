// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The wrist ballistocardiogram: the tiny recoil of the body each time the heart ejects blood,
 * felt by the watch's accelerometer (Yousefian & Mukkamala, Sci Rep 2019; SeismoWatch 2017).
 * Single beats are buried in noise, so the accelerometer is averaged over many beats, aligned on
 * a marker whose timing is known (the PPG pulse foot or the ECG R peak).
 *
 * The I wave marks the start of ejection. Aligned on the PPG foot, it shows up [iLagMs] before
 * it: the pulse transit time from the heart to the wrist, without the pre-ejection period that
 * makes ECG-based arrival times unreliable under stress. Aligned on the R peak, the I wave comes
 * [iLagMs] after it: the pre-ejection period itself.
 */
object WristBcg {
    /** Uniform rate the accelerometer is resampled to, Hz. */
    const val FS = 250

    /** Below this true rate the recoil (≈ 5–15 Hz) can't be resolved. */
    const val MIN_RATE_HZ = 80.0
    const val MIN_BEATS = 12

    /**
     * [iLagMs] / [jLagMs]: time of the I and J waves relative to the trigger (negative = before).
     * [quality]: agreement of the odd-beat and even-beat averages (0..1). [axis]: the watch
     * axis (0..2) that carried the recoil.
     */
    data class Result(val iLagMs: Double, val jLagMs: Double, val quality: Double, val beats: Int, val axis: Int, val amplitude: Double)

    /** Aligned on PPG pulses (their steepest upstroke, wall-clock ns): the I wave precedes them by the transit time. */
    fun beforePpgFeet(accel: SensorStream, feetNs: LongArray): Result? =
        analyse(accel, feetNs, preMs = 450, postMs = 100, searchFromMs = -400, searchToMs = -40)

    /** Aligned on ECG R peaks (wall-clock ns): the I wave follows R by the pre-ejection period. */
    fun afterRPeaks(accel: SensorStream, rNs: LongArray): Result? =
        analyse(accel, rNs, preMs = 100, postMs = 450, searchFromMs = 30, searchToMs = 250)

    /** Transit time from the ballistocardiogram to the PPG foot, ms, when the average is clear enough. */
    fun transitMs(r: Result?): Double? = r?.takeIf { it.quality >= MIN_QUALITY }?.let { -it.iLagMs }

    /** Pre-ejection period from R to the I wave, ms, when clear enough. */
    fun preEjectionMs(r: Result?): Double? = r?.takeIf { it.quality >= MIN_QUALITY }?.iLagMs

    const val MIN_QUALITY = 0.5

    private fun analyse(accel: SensorStream, triggersNs: LongArray, preMs: Int, postMs: Int, searchFromMs: Int, searchToMs: Int): Result? {
        if (accel.size < 50 || accel.rateHz() < MIN_RATE_HZ || triggersNs.size < MIN_BEATS) return null
        val t0 = accel.timestampsNs.first()
        val t1 = accel.timestampsNs.last()
        val n = ((t1 - t0) / 1e9 * FS).toInt()
        if (n < FS * 5) return null
        val axes = (0 until 3).map { c ->
            val resampled = resample(accel, c, t0, n)
            resampled.filtFilt(Biquad.highPass(1.0, FS.toDouble()), Biquad.lowPass(20.0, FS.toDouble()))
        }
        val pre = preMs * FS / 1000
        val post = postMs * FS / 1000
        val len = pre + post
        val starts = triggersNs.map { ((it - t0) / 1e9 * FS).toInt() - pre }.filter { it >= 0 && it + len < n }
        if (starts.size < MIN_BEATS) return null
        var best: Result? = null
        for ((axis, x) in axes.withIndex()) {
            val beats = starts.map { s ->
                DoubleArray(len) { x[s + it].toDouble() }.let { b ->
                    val m = b.average()
                    DoubleArray(len) {
                        b[it] -
                            m
                    }
                }
            }
            val template = DoubleArray(len) { k -> beats.sumOf { it[k] } / beats.size }
            val from = (pre + searchFromMs * FS / 1000).coerceIn(1, len - 2)
            val to = (pre + searchToMs * FS / 1000).coerceIn(from + 2, len - 1)
            // J is the largest deflection in the search window; its sign sets the polarity.
            val jIdx = (from..to).maxBy { abs(template[it]) }
            val sign = if (template[jIdx] >= 0) 1.0 else -1.0
            val iFrom = (jIdx - MAX_IJ_MS * FS / 1000).coerceAtLeast(0)
            val iIdx = (iFrom until jIdx).minByOrNull { sign * template[it] } ?: continue
            // Split-half reliability: do the averages of the odd and the even beats agree? (Single
            // beats are mostly noise, so their own correlation says little.)
            val odd = DoubleArray(len) { k -> beats.filterIndexed { i, _ -> i % 2 == 1 }.sumOf { it[k] } }
            val even = DoubleArray(len) { k -> beats.filterIndexed { i, _ -> i % 2 == 0 }.sumOf { it[k] } }
            val quality = correlation(odd, even).coerceIn(0.0, 1.0)
            val amplitude = abs(template[jIdx] - template[iIdx])
            val r = Result(
                (iIdx - pre) * 1000.0 / FS,
                (jIdx - pre) * 1000.0 / FS,
                quality,
                beats.size,
                axis,
                amplitude
            )
            if (best == null || r.quality * r.amplitude > best.quality * best.amplitude) best = r
        }
        return best
    }

    /** The I wave precedes J by at most this, ms. */
    private const val MAX_IJ_MS = 120

    private fun resample(s: SensorStream, column: Int, t0: Long, n: Int): FloatArray {
        val cols = s.columns.size
        val out = FloatArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = t0 + (i * 1e9 / FS).toLong()
            while (j < s.size - 2 && s.timestampsNs[j + 1] < t) j++
            val ta = s.timestampsNs[j]
            val tb = s.timestampsNs[j + 1]
            val a = s.values[j * cols + column]
            val b = s.values[(j + 1) * cols + column]
            val f = if (tb > ta) ((t - ta).toDouble() / (tb - ta)).coerceIn(0.0, 1.0) else 0.0
            out[i] = (a + (b - a) * f).toFloat()
        }
        return out
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val am = a.average()
        val bm = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            num += (a[i] - am) * (b[i] - bm)
            da += (a[i] - am) * (a[i] - am)
            db += (b[i] - bm) * (b[i] - bm)
        }
        return if (da <= 0 || db <= 0) 0.0 else num / sqrt(da * db)
    }
}
