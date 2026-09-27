// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The arm-raise maneuver (precise mode): the user holds the watch arm at heart level, raises it
 * overhead, then lets it hang. Raising the wrist by h lowers the pressure there by exactly
 * ρ·g·h = 0.78 mmHg per cm, a pressure change known without any cuff.
 *
 * - **Sign.** From the raised phase, which way a raised hand tilts the accelerometer.
 * - **In-session slope** (McCombie 2007; hydrostatic PAT calibration): beat-to-beat arrival time
 *   against the known local pressure change gives this user's sensitivity *now*, in whatever
 *   state the body is in, instead of the one from the calibration day ([TransitEstimator]).
 * - **Mean pressure without a cuff** (Shaltis & Asada, IEEE TBME 2008): the pulse amplitude is
 *   largest where the pressure across the artery wall is near zero, i.e. where the hydrostatic
 *   drop equals the mean pressure. A raised arm reaches about 45 mmHg, so the peak (with a fall
 *   on both sides) is only seen when the mean pressure is very low; otherwise only a lower bound
 *   is known ([Result.mapLowerBound]).
 */
object HydrostaticCalibration {
    /** mmHg per cm of height (blood density 1.06 g/ml). */
    const val MMHG_PER_CM = 0.78

    /** Shoulder → wrist length as a share of body height (anthropometric average). */
    const val ARM_SHARE_OF_HEIGHT = 0.33

    /** Minimum spread of local pressure the maneuver must cover to measure a slope, mmHg. */
    const val MIN_RANGE_MMHG = 20.0

    /** A peak counts only when the amplitude has fallen to this share of it at both ends of the range. */
    const val EDGE_RATIO = 0.9

    /** Pressure the strap and tissue put on the vessel: transmural zero is this far above zero. */
    const val TISSUE_MMHG = 5.0

    data class Result(
        /** +1 or −1: multiply the raw pitch to make a raised hand positive. */
        val sign: Double,
        /** Local pressure offsets covered, mmHg (0 = heart level). */
        val minOffset: Double,
        val maxOffset: Double,
        val beats: Int,
        /** In-session slope for PAT / ECG PTT, when precise mode gave beat-to-beat arrival times. */
        val patSlope: TransitEstimator.SlopeObservation?,
        /** Mean arterial pressure at heart level from the amplitude peak, when the peak was reached. */
        val map: Double?,
        val mapSd: Double?,
        /** Without a peak: the mean pressure is at least this (the largest drop reached, plus tissue pressure). */
        val mapLowerBound: Double?
    )

    /**
     * @param ppg raw PPG, [ppgTimesNs] its sample times (wall clock), [fs] its rate.
     * @param raisedNs time window when the user was asked to hold the arm up (for the sign).
     * @param patBeats per-beat (time ns, arrival time ms) in precise mode.
     */
    fun analyse(
        ppg: FloatArray,
        ppgTimesNs: LongArray,
        fs: Int,
        accel: SensorStream,
        heightCm: Double?,
        raisedNs: LongRange? = null,
        patBeats: List<Pair<Long, Double>> = emptyList()
    ): Result? {
        val pulses = PpgFeatures.pulses(ppg, fs) ?: return null
        if (pulses.size < 12 || ppgTimesNs.size != ppg.size) return null
        val armCm = (heightCm?.takeIf { it > 100 } ?: 170.0) * ARM_SHARE_OF_HEIGHT
        val imu = ImuStreams(accel, null, null)
        fun pitchAt(from: Long, to: Long) = imu.meanGravity(from, to)?.let { ImuStreams.pitchDeg(it) }
        val rawPitch = pulses.map { p -> pitchAt(ppgTimesNs[p.foot], ppgTimesNs[p.nextFoot]) }
        // The raised phase must read as a raised hand: that fixes the sign.
        val sign = raisedNs?.let { w -> pitchAt(w.first, w.last) }?.let { if (it < 0) -1.0 else 1.0 } ?: 1.0
        val beats = pulses.indices.mapNotNull { i ->
            val pitch = rawPitch[i]?.times(sign) ?: return@mapNotNull null
            val offset = MMHG_PER_CM * armCm * sin(Math.toRadians(pitch))
            Triple(ppgTimesNs[pulses[i].foot], offset, pulses[i].amplitude)
        }
        if (beats.size < 12) return null
        val minOffset = beats.minOf { it.second }
        val maxOffset = beats.maxOf { it.second }

        val patSlope = slopeFromPat(beats, patBeats)
        val (map, mapSd) = mapFromAmplitude(beats.map { it.second }, beats.map { it.third }) ?: (null to null)
        return Result(
            sign,
            minOffset,
            maxOffset,
            beats.size,
            patSlope,
            map,
            mapSd,
            if (map == null && maxOffset - minOffset >= MIN_RANGE_MMHG) maxOffset + TISSUE_MMHG else null
        )
    }

    /**
     * Arrival time against the local pressure (−offset): its slope over the arm segment, turned into
     * mmHg per ms for the whole path ([TransitEstimator.ARM_FRACTION]).
     */
    private fun slopeFromPat(beats: List<Triple<Long, Double, Double>>, pat: List<Pair<Long, Double>>): TransitEstimator.SlopeObservation? {
        if (pat.size < 12) return null
        // Each PAT beat takes the offset of the PPG pulse nearest in time.
        val pairs = pat.mapNotNull { (t, ms) ->
            val nearest = beats.minByOrNull { abs(it.first - t) } ?: return@mapNotNull null
            if (abs(nearest.first - t) > 1_000_000_000L) null else -nearest.second to ms
        }
        if (pairs.size < 12) return null
        val x = pairs.map { it.first }
        val y = pairs.map { it.second }
        if (x.max() - x.min() < MIN_RANGE_MMHG) return null
        val (slope, se) = regress(x, y) ?: return null
        // ms per mmHg of local pressure; must be negative (higher pressure, faster pulse).
        if (slope >= -1e-3) return null
        val mmHgPerMs = TransitEstimator.ARM_FRACTION / slope
        // Relative error of the slope, plus 30 % for the arm-fraction and arm-length assumptions.
        val rel = sqrt((se / abs(slope)).pow(2) + 0.3.pow(2))
        return TransitEstimator.SlopeObservation(mmHgPerMs, abs(mmHgPerMs) * rel)
    }

    /**
     * Fits amplitude = c + A·exp(−(x − m)² / 2w²) over the offsets x; m + tissue pressure is the mean
     * pressure. Accepted only when the peak lies inside the covered range and clearly stands out.
     */
    internal fun mapFromAmplitude(x: List<Double>, a: List<Double>): Pair<Double, Double>? {
        if (x.size < 12 || x.max() - x.min() < MIN_RANGE_MMHG) return null
        var best: DoubleArray? = null
        var bestErr = Double.MAX_VALUE
        var m = x.min()
        while (m <= x.max()) {
            var w = 8.0
            while (w <= 40.0) {
                val g = x.map { exp(-(it - m).pow(2) / (2 * w * w)) }
                val fit = linear(g, a)
                if (fit != null && fit.second > 0) {
                    val err = x.indices.sumOf { (a[it] - fit.first - fit.second * g[it]).pow(2) }
                    if (err < bestErr) {
                        bestErr = err
                        best = doubleArrayOf(m, w, fit.first, fit.second)
                    }
                }
                w += 2.0
            }
            m += 1.0
        }
        val (peak, width, c, amp) = best ?: return null
        val mean = a.average()
        val total = a.sumOf { (it - mean).pow(2) }
        val r2 = if (total <= 0) 0.0 else 1 - bestErr / total

        // The peak must be inside the range with a real fall on both sides (the fitted curve at
        // each end at most [EDGE_RATIO] of the peak), a clear bump and a good fit.
        fun curve(v: Double) = c + amp * exp(-(v - peak).pow(2) / (2 * width * width))
        val top = c + amp
        if (curve(x.min()) > EDGE_RATIO * top || curve(x.max()) > EDGE_RATIO * top || r2 < 0.5 || amp < 0.15 * top) return null
        val sd = 4.0 + width * 0.2 + (1 - r2) * 10
        return peak + TISSUE_MMHG to sd
    }

    /** Least-squares intercept and slope of a on g. */
    private fun linear(g: List<Double>, a: List<Double>): Pair<Double, Double>? {
        val gm = g.average()
        val am = a.average()
        val sxx = g.sumOf { (it - gm).pow(2) }
        if (sxx < 1e-9) return null
        val slope = g.indices.sumOf { (g[it] - gm) * (a[it] - am) } / sxx
        return (am - slope * gm) to slope
    }

    /** Least-squares slope of y on x and its standard error. */
    private fun regress(x: List<Double>, y: List<Double>): Pair<Double, Double>? {
        val n = x.size
        val xm = x.average()
        val ym = y.average()
        val sxx = x.sumOf { (it - xm).pow(2) }
        if (sxx < 1e-9 || n < 3) return null
        val b = x.indices.sumOf { (x[it] - xm) * (y[it] - ym) } / sxx
        val resid = x.indices.sumOf { (y[it] - ym - b * (x[it] - xm)).pow(2) } / (n - 2)
        return b to sqrt(resid / sxx)
    }
}
