package com.heartline.shared.bp

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/** Pulse-wave morphology averaged over the clean beats of one recording. */
@Serializable
data class PpgFeatureVector(
    val heartRateBpm: Double,
    /** Foot-to-peak time as a fraction of the beat (shorter with stiffer arteries). */
    val riseFraction: Double,
    /** Pulse width at half amplitude, fraction of the beat. */
    val widthFraction: Double,
    /** Area after the systolic peak / area before it (wave reflection). */
    val areaRatio: Double,
    /** Beats used; also a quality signal. */
    val beats: Int,
    /** 0..1: beat-to-beat consistency of the features. */
    val quality: Double
) {
    fun asArray() = doubleArrayOf(heartRateBpm, riseFraction, widthFraction, areaRatio)
}

/** Extracts [PpgFeatureVector] from green PPG (PPG_ON_DEMAND, 100 Hz). */
object PpgFeatures {
    fun extract(raw: FloatArray, fs: Int): PpgFeatureVector? {
        if (raw.size < fs * 8) return null
        val x = raw.filtFilt(Biquad.highPass(0.5, fs.toDouble()), Biquad.lowPass(8.0, fs.toDouble()))
        // Systolic peaks first (robust to the dicrotic dip), then each pulse foot is the
        // minimum in the 0.35 s before its peak.
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

        data class Beat(val rr: Double, val rise: Double, val width: Double, val area: Double)
        val beats = feet.zipWithNext().mapNotNull { (a, b) ->
            val len = b - a
            if (len < fs * 0.33 || len > fs * 1.6) return@mapNotNull null
            val seg = x.copyOfRange(a, b)
            val base = minOf(seg.first(), seg.last())
            val peakIdx = seg.indices.maxBy { seg[it] }
            val amp = seg[peakIdx] - base
            if (amp <= 0f) return@mapNotNull null
            val halfLevel = base + amp / 2
            val width = seg.count { it >= halfLevel }.toDouble() / len
            val areaBefore = (0..peakIdx).sumOf { (seg[it] - base).toDouble() }
            val areaAfter = (peakIdx until len).sumOf { (seg[it] - base).toDouble() }
            Beat(len.toDouble() / fs, peakIdx.toDouble() / len, width, areaAfter / areaBefore.coerceAtLeast(1e-6))
        }
        if (beats.size < 5) return null
        fun med(v: List<Double>) = v.sorted()[v.size / 2]
        val rise = beats.map { it.rise }
        val riseMean = rise.average()
        val riseCov = sqrt(rise.sumOf { (it - riseMean) * (it - riseMean) } / rise.size) / riseMean
        return PpgFeatureVector(
            heartRateBpm = 60.0 / med(beats.map { it.rr }),
            riseFraction = med(rise),
            widthFraction = med(beats.map { it.width }),
            areaRatio = med(beats.map { it.area }),
            beats = beats.size,
            quality = (1.0 - riseCov / 0.4).coerceIn(0.0, 1.0)
        )
    }
}
