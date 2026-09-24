package com.heartline.shared.bp

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * Pulse-wave morphology of one recording, measured on the ensemble-averaged beat (see
 * docs/BP_ALGORITHM.md). Fields added in algorithm 2 default to 0 so older data still decodes.
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
    val version: Int = 1
) {
    fun asArray() = doubleArrayOf(heartRateBpm, riseFraction, widthFraction, areaRatio)

    /** Features used by [BpEstimator] (algorithm 2). */
    fun modelArray() = doubleArrayOf(heartRateBpm, upstrokeMs, width50Ms, areaRatio, apgBa, apgDa)

    companion object {
        const val VERSION = 2
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
            version = PpgFeatureVector.VERSION
        )
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
