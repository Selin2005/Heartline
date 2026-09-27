// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import com.heartline.shared.ecg.RPeakDetector

/**
 * Precise mode (finger on the key): ECG, the green PPG that comes with it (one clock, 500 Hz)
 * and the accelerometer at the same time.
 *
 * - PAT: R peak → PPG upstroke ([PulseArrival]).
 * - PEP: R peak → ballistocardiogram I wave ([WristBcg.afterRPeaks]). Under stress PEP can halve
 *   (PMC9975268), which is why PAT alone reads a stressed, fast heart as high pressure.
 * - PTT = PAT − PEP: the transit time through the arteries only.
 *
 * Without a clear ballistocardiogram PEP falls back to Weissler's rate relation
 * (PEP ≈ 131 − 0.4·HR ms), shortened in a compensating state, and is flagged as estimated.
 */
object TransitTimes {
    data class Result(
        val patMs: Double,
        val pepMs: Double,
        val pepMeasured: Boolean,
        val pttMs: Double,
        val beats: Int,
        val heartRateBpm: Double,
        val bcg: WristBcg.Result?
    )

    /** In a compensating (sympathetic) state the pre-ejection period shortens by about this share. */
    const val STRESS_PEP = 0.75

    /**
     * @param startNs wall-clock time of the first ECG sample, so R peaks line up with the accelerometer.
     */
    fun compute(ecg: FloatArray, ppg: FloatArray, fs: Int, startNs: Long, accel: SensorStream?, state: HemodynamicState): Result? {
        val pat = PulseArrival.compute(ecg, ppg, fs) ?: return null
        val peaks = RPeakDetector.detect(ecg, fs)
        if (peaks.size < 2) return null
        val rr = peaks.toList().zipWithNext { a, b -> (b - a) * 1000.0 / fs }.sorted()
        val hr = 60_000.0 / rr[rr.size / 2]
        val rNs = LongArray(peaks.size) { startNs + (peaks[it] * 1e9 / fs).toLong() }
        val bcg = accel?.let { WristBcg.afterRPeaks(it, rNs) }
        val measured = WristBcg.preEjectionMs(bcg)?.takeIf { it in 40.0..pat.medianMs - 40 }
        val pep = measured ?: weisslerPep(hr, state)
        return Result(pat.medianMs, pep, measured != null, pat.medianMs - pep, pat.beats, hr, bcg)
    }

    fun weisslerPep(heartRateBpm: Double, state: HemodynamicState): Double =
        (131.0 - 0.4 * heartRateBpm).coerceIn(50.0, 140.0) * if (state == HemodynamicState.COMPENSATORY) STRESS_PEP else 1.0
}
