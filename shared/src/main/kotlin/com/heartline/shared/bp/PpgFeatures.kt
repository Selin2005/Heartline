// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * Pulse-wave morphology of one recording, measured on the ensemble-averaged beat (see
 * docs/algorithms/BP_ALGORITHM.md). Fields added in algorithm 2 default to 0 so older data still decodes.
 */
@Serializable
data class PpgFeatureVector(
    val heartRateBpm: Double,
    /** Foot-to-peak time as a fraction of the beat. */
    val riseFraction: Double,
    /** Pulse width at half amplitude, fraction of the beat. */
    val widthFraction: Double,
    /** Area after the systolic peak / area before it (wave reflection). */
    val areaRatio: Double,
    /** Beats used; also a quality signal. */
    val beats: Int,
    /** 0..1: how closely the beats match their average. */
    val quality: Double,
    /** Systolic upstroke time (foot → peak), ms. */
    val upstrokeMs: Double = 0.0,
    /** Pulse width at 50 % amplitude, ms. */
    val width50Ms: Double = 0.0,
    /** Pulse width at 25 % amplitude, ms (diastolic runoff). */
    val width25Ms: Double = 0.0,
    /** Second-derivative (acceleration plethysmogram) wave ratios b/a and d/a (Takazawa 1998). */
    val apgBa: Double = 0.0,
    val apgDa: Double = 0.0,
    /** The raw signal was upside down (Galaxy Watch green PPG usually is) and was flipped. */
    val inverted: Boolean = false,
    val version: Int = 1,
    /**
     * Algorithm 3: systolic peak → diastolic peak (or inflection) time, ms. The reflected wave
     * returns sooner as arteries stiffen and pressure rises (stiffness index, Millasseau 2002).
     */
    val reflectionDelayMs: Double = 0.0,
    /** Height of the diastolic peak/inflection relative to the systolic peak (reflection index). */
    val reflectionIndex: Double = 0.0,
    /** Beat-to-beat variability of the kept beats (RMSSD, ms). */
    val rmssdMs: Double = 0.0,
    /** Skewness of the filtered signal: clean PPG is clearly skewed (Elgendi 2016 SQI). */
    val skewness: Double = 0.0,
    /** The ensemble beat resampled to [SHAPE_POINTS] points (0..1), for the phone's learned model. */
    val shape: List<Float> = emptyList()
) {
    fun asArray() = doubleArrayOf(heartRateBpm, riseFraction, widthFraction, areaRatio)

    /** Features used by [BpEstimator]; see [BpEstimator.FEATURE_MIN_VERSION] for when each became available. */
    fun modelArray() = doubleArrayOf(heartRateBpm, upstrokeMs, width50Ms, areaRatio, apgBa, apgDa, reflectionDelayMs)

    /** Beat length, ms. */
    val beatMs: Double get() = 60_000.0 / heartRateBpm.coerceAtLeast(1.0)

    companion object {
        /** Current extractor. */
        const val VERSION = 3

        /** Oldest extractor whose features the estimator still accepts (the 6 algorithm-2 features are unchanged). */
        const val MIN_MODEL_VERSION = 2

        const val SHAPE_POINTS = 32
    }
}

/**
 * Extracts [PpgFeatureVector] from green PPG (PPG_ON_DEMAND, 100 Hz):
 * band-pass 0.5–8 Hz → polarity check (arterial pulses rise fast and fall slowly) → systolic
 * peaks and feet → beats of plausible length → ensemble average beat (aligned on the foot) →
 * morphology on that average, which is far less noisy than per-beat values.
 */
object PpgFeatures {
    fun extract(raw: FloatArray, fs: Int): PpgFeatureVector? {
        if (raw.size < fs * 8) return null
        val filtered = raw.filtFilt(Biquad.highPass(0.5, fs.toDouble()), Biquad.lowPass(8.0, fs.toDouble()))
        if ((filtered.max() - filtered.min()) < 1e-6f) return null
        val inverted = isInverted(filtered)
        val x = if (inverted) FloatArray(filtered.size) { -filtered[it] } else filtered

        val half = (0.25 * fs).toInt()
        val maxima = (half until x.size - half).filter { i -> (i - half..i + half).all { x[it] <= x[i] } }
        if (maxima.size < 6) return null
        val threshold = maxima.map { x[it] }.sorted()[maxima.size / 4] * 0.5f
        val peaks = mutableListOf<Int>()
        for (m in maxima.filter { x[it] >= threshold }) {
            if (peaks.isNotEmpty() && m - peaks.last() < fs * 0.35) {
                if (x[m] > x[peaks.last()]) peaks[peaks.lastIndex] = m
            } else {
                peaks += m
            }
        }
        val lookBack = (0.35 * fs).toInt()
        val feet = peaks.filter { it - lookBack >= 0 }.map { p -> (p - lookBack..p).minBy { x[it] } }.distinct()
        if (feet.size < 6) return null

        val beats = feet.zipWithNext().filter { (a, b) -> b - a in (fs * 0.33).toInt()..(fs * 1.6).toInt() }
        if (beats.size < 5) return null
        val lengths = beats.map { (a, b) -> b - a }.sorted()
        val len = lengths[lengths.size / 2]
        // Only beats within 20 % of the typical length: an ectopic or a missed foot would smear the average.
        val kept = beats.filter { (a, b) -> abs((b - a) - len) <= len * 0.2 }
        if (kept.size < 5) return null

        // Each beat normalised to 0..1 (foot..peak) and stretched to the typical length, at 4× the
        // sample rate so timings aren't quantised to 10 ms steps.
        val up = UPSAMPLE
        val n = len * up
        val shapes = kept.map { (a, b) -> normalise(resample(x, a, b, n)) }
        val template = DoubleArray(n) { k -> shapes.map { it[k] }.sorted()[shapes.size / 2] }
        if (template.max() < 0.5) return null
        val correlations = shapes.map { correlation(it, template) }
        val quality = correlations.sorted()[correlations.size / 2].coerceIn(0.0, 1.0) *
            (correlations.count { it > 0.9 }.toDouble() / correlations.size)

        val peakIdx = template.indices.maxBy { template[it] }
        val (reflectionIdx, reflectionHeight) = reflection(template, peakIdx, fs * up)
        val keptIntervals = kept.map { (a, b) -> (b - a) * 1000.0 / fs }
        val rmssd = if (keptIntervals.size < 3) 0.0 else sqrt(keptIntervals.zipWithNext { a, b -> (b - a) * (b - a) }.average())
        val areaBefore = (0..peakIdx).sumOf { template[it] }
        val areaAfter = (peakIdx until n).sumOf { template[it] }
        val (ba, da) = apgRatios(template, fs * up)
        val msPerSample = 1000.0 / (fs * up)
        return PpgFeatureVector(
            heartRateBpm = 60.0 * fs / len,
            riseFraction = peakIdx.toDouble() / n,
            widthFraction = template.count { it >= 0.5 }.toDouble() / n,
            areaRatio = areaAfter / areaBefore.coerceAtLeast(1e-6),
            beats = kept.size,
            quality = quality,
            upstrokeMs = peakIdx * msPerSample,
            width50Ms = template.count { it >= 0.5 } * msPerSample,
            width25Ms = template.count { it >= 0.25 } * msPerSample,
            apgBa = ba,
            apgDa = da,
            inverted = inverted,
            version = PpgFeatureVector.VERSION,
            reflectionDelayMs = reflectionIdx?.let { (it - peakIdx) * msPerSample } ?: 0.0,
            reflectionIndex = reflectionHeight,
            rmssdMs = rmssd,
            skewness = skewness(x),
            shape = List(PpgFeatureVector.SHAPE_POINTS) { k -> template[k * (n - 1) / (PpgFeatureVector.SHAPE_POINTS - 1)].toFloat() }
        )
    }

    /**
     * The reflected (diastolic) wave: the first local maximum of the beat after the systolic peak,
     * or, when the two merge (stiff arteries, older users), the inflection point where the downslope
     * flattens most (maximum of the first derivative). Searched 80–500 ms after the peak and before
     * 85 % of the beat. @return its index and its height relative to the systolic peak.
     */
    private fun reflection(template: DoubleArray, peak: Int, fs: Int): Pair<Int?, Double> {
        val smooth = gaussian(template, sigma = 0.015 * fs)
        val from = peak + (0.08 * fs).toInt()
        val to = minOf(peak + (0.5 * fs).toInt(), (template.size * 0.85).toInt())
        if (to - from < 3) return null to 0.0
        val localMax = (from + 1 until to - 1).firstOrNull {
            smooth[it] > smooth[it - 1] &&
                smooth[it] >= smooth[it + 1] &&
                smooth[it] > 0.05
        }
        val idx = localMax ?: (from + 1 until to - 1).maxByOrNull { smooth[it + 1] - smooth[it - 1] } ?: return null to 0.0
        return idx to template[idx].coerceIn(0.0, 1.0)
    }

    private fun skewness(x: FloatArray): Double {
        val m = x.average()
        var m2 = 0.0
        var m3 = 0.0
        for (v in x) {
            val d = v - m
            m2 += d * d
            m3 += d * d * d
        }
        m2 /= x.size
        m3 /= x.size
        return if (m2 <= 1e-12) 0.0 else m3 / (m2 * sqrt(m2))
    }

    /**
     * Arterial pulses rise quickly and decay slowly, so the steepest slope is positive. If the
     * steepest slopes are negative instead, the signal is upside down (raw light intensity falls
     * as blood volume rises).
     */
    private const val UPSAMPLE = 4

    fun isInverted(x: FloatArray): Boolean {
        val d = FloatArray(x.size - 1) { x[it + 1] - x[it] }.sorted()
        val k = (d.size * 0.02).toInt().coerceAtLeast(1)
        val steepRise = d.takeLast(k).average()
        val steepFall = -d.take(k).average()
        return steepFall > steepRise * 1.15
    }

    private fun resample(x: FloatArray, from: Int, to: Int, n: Int): DoubleArray = DoubleArray(n) { k ->
        val pos = from + k.toDouble() * (to - from) / n
        val i = pos.toInt().coerceIn(from, to - 1)
        val f = pos - i
        x[i] * (1 - f) + x[(i + 1).coerceAtMost(x.size - 1)] * f
    }

    /**
     * Removes the straight line from this foot to the next (respiration and drift tilt a beat),
     * then scales so the foot is 0 and the systolic peak 1.
     */
    private fun normalise(beat: DoubleArray): DoubleArray {
        val n = beat.size
        val start = beat.first()
        val end = beat.last()
        val detrended = DoubleArray(n) { beat[it] - (start + (end - start) * it / (n - 1).coerceAtLeast(1)) }
        val peak = detrended.max()
        if (peak <= 1e-9) return DoubleArray(n)
        return DoubleArray(n) { detrended[it] / peak }
    }

    /**
     * a = first maximum of the second derivative (early systole), b = the minimum after it,
     * d = the minimum in late systole (before the dicrotic region). Ratios are scale free.
     */
    private fun apgRatios(template: DoubleArray, fs: Int): Pair<Double, Double> {
        // Differentiating twice amplifies noise: smooth first (~10 ms Gaussian) and step 10 ms.
        val smooth = gaussian(template, sigma = 0.010 * fs)
        val h = maxOf(1, (0.010 * fs).toInt())
        val d1 = DoubleArray(smooth.size) { i -> if (i in h until smooth.size - h) (smooth[i + h] - smooth[i - h]) / (2 * h) else 0.0 }
        val d2 = DoubleArray(smooth.size) { i -> if (i in h until smooth.size - h) (d1[i + h] - d1[i - h]) / (2 * h) else 0.0 }
        val peak = template.indices.maxBy { template[it] }
        val a = (0..peak).maxByOrNull { d2[it] } ?: return 0.0 to 0.0
        if (d2[a] <= 0) return 0.0 to 0.0
        val b = (a..(a + (0.15 * fs).toInt()).coerceAtMost(template.size - 1)).minBy { d2[it] }
        val lateFrom = (peak + (0.05 * fs).toInt()).coerceAtMost(template.size - 1)
        val lateTo = (peak + (0.25 * fs).toInt()).coerceAtMost(template.size - 1)
        val d = (lateFrom..lateTo).minByOrNull { d2[it] } ?: return d2[b] / d2[a] to 0.0
        return d2[b] / d2[a] to d2[d] / d2[a]
    }

    private fun gaussian(x: DoubleArray, sigma: Double): DoubleArray {
        val radius = (3 * sigma).toInt().coerceAtLeast(1)
        val kernel = DoubleArray(2 * radius + 1) { k -> kotlin.math.exp(-0.5 * ((k - radius) / sigma).let { it * it }) }
        val norm = kernel.sum()
        return DoubleArray(x.size) { i ->
            var acc = 0.0
            for (k in kernel.indices) acc += kernel[k] * x[(i + k - radius).coerceIn(0, x.size - 1)]
            acc / norm
        }
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

/** Live pulse rate from a few seconds of filtered, upright PPG (for the measuring screen). */
object PulseRate {
    fun bpm(x: FloatArray, fs: Int): Int? {
        if (x.size < fs * 3) return null
        val sorted = x.sorted()
        val level = sorted[(sorted.size * 0.6).toInt()]
        val half = (0.2 * fs).toInt()
        val peaks = mutableListOf<Int>()
        for (i in half until x.size - half) {
            if (x[i] < level) continue
            if ((i - half..i + half).any { x[it] > x[i] }) continue
            if (peaks.isEmpty() || i - peaks.last() >= (0.33 * fs).toInt()) peaks += i
        }
        if (peaks.size < 3) return null
        val intervals = peaks.zipWithNext { a, b -> b - a }.sorted()
        return (60.0 * fs / intervals[intervals.size / 2]).toInt().takeIf { it in 35..200 }
    }
}
