package com.heartline.shared.ecg

import kotlin.math.abs
import kotlin.math.sqrt

/** What one second of a filtered recording looks like. */
enum class SecondKind { GOOD, MOTION, MUSCLE, FLAT }

/**
 * Per-second quality of a cleaned (0.5–40 Hz) single-lead ECG. Each second is compared with the
 * recording's own typical second, so it adapts to the user's signal amplitude:
 * - MOTION: excursions far larger than typical (movement, pressing/lifting the finger, clipping);
 * - FLAT: almost no signal (poor contact);
 * - MUSCLE: high-frequency energy that buries the QRS (tensed arm, pressing the key hard).
 */
object EcgQuality {
    /** Median per-second peak-to-peak below this (mV) means no usable ECG at all. */
    const val MIN_SIGNAL_MV = 0.06
    const val MAX_ABS_MV = 6.0
    const val MOTION_FACTOR = 3.0
    const val FLAT_FACTOR = 0.25

    fun seconds(clean: FloatArray, fs: Int): List<SecondKind> {
        val count = clean.size / fs
        if (count == 0) return emptyList()
        val ranges = DoubleArray(count) { w ->
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in w * fs until (w + 1) * fs) {
                lo = minOf(lo, clean[i])
                hi = maxOf(hi, clean[i])
            }
            (hi - lo).toDouble()
        }
        val typical = median(ranges.toList())
        if (typical < MIN_SIGNAL_MV) return List(count) { SecondKind.FLAT }
        return List(count) { w ->
            val peak = (w * fs until (w + 1) * fs).maxOf { abs(clean[it]) }
            when {
                ranges[w] > MOTION_FACTOR * typical || peak > MAX_ABS_MV -> SecondKind.MOTION
                ranges[w] < FLAT_FACTOR * typical -> SecondKind.FLAT
                else -> SecondKind.GOOD
            }
        }
    }

    /**
     * Marks seconds whose beats are buried in noise (per-beat residual from [BeatTemplate]) as
     * MUSCLE. Seconds without a beat of their own take the verdict of the nearest beat.
     */
    fun withBeatNoise(kinds: List<SecondKind>, peaks: IntArray, noisy: BooleanArray, fs: Int): List<SecondKind> {
        if (peaks.isEmpty()) return kinds
        return kinds.mapIndexed { w, kind ->
            if (kind != SecondKind.GOOD) return@mapIndexed kind
            val centre = w * fs + fs / 2
            val inSecond = peaks.indices.filter { peaks[it] / fs == w }
            val verdicts = inSecond.ifEmpty { listOf(peaks.indices.minBy { abs(peaks[it] - centre) }) }.map { noisy[it] }
            if (verdicts.count { it } * 2 > verdicts.size) SecondKind.MUSCLE else SecondKind.GOOD
        }
    }

    internal fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

/**
 * Beat-level checks against the recording's median beat (-200..+400 ms around R): how well each
 * beat matches (noise mistaken for QRS matches poorly) and how much noise rides on it.
 */
object BeatTemplate {
    const val MIN_CORRELATION = 0.75

    /** Residual RMS / template peak-to-peak above this means the beat is buried in noise. */
    const val MAX_NOISE_RATIO = 0.12

    data class Beats(val correlation: DoubleArray, val noiseRatio: DoubleArray, val templateRangeMv: Double, val inverted: Boolean)

    fun assess(clean: FloatArray, peaks: IntArray, fs: Int): Beats {
        val pre = (0.2 * fs).toInt()
        val post = (0.4 * fs).toInt()
        val inside = peaks.filter { it - pre >= 0 && it + post < clean.size }
        if (inside.size < 3) return Beats(DoubleArray(peaks.size), DoubleArray(peaks.size) { 1.0 }, 0.0, false)
        val len = pre + post
        val template = DoubleArray(len) { k -> EcgQuality.median(inside.map { clean[it - pre + k].toDouble() }) }
        val range = template.max() - template.min()
        val baseline = EcgQuality.median(template.toList())
        // Dominant deflection at R below the baseline: electrodes reversed (e.g. worn on the other wrist).
        val inverted = baseline - template[pre] > template[pre] - baseline
        val corr = DoubleArray(peaks.size)
        val noise = DoubleArray(peaks.size) { 1.0 }
        peaks.forEachIndexed { b, p ->
            if (p - pre < 0 || p + post >= clean.size || range <= 0) return@forEachIndexed
            corr[b] = correlation(template) { k -> clean[p - pre + k].toDouble() }
            // Remove the beat's own offset before comparing shapes.
            val offset = (0 until len).sumOf { clean[p - pre + it] - template[it] } / len
            noise[b] = sqrt(
                (0 until len).sumOf {
                    val d = clean[p - pre + it] - template[it] - offset
                    d * d
                } / len
            ) / range
        }
        return Beats(corr, noise, range, inverted)
    }

    private fun correlation(a: DoubleArray, b: (Int) -> Double): Double {
        val n = a.size
        val bm = (0 until n).sumOf(b) / n
        val am = a.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (k in 0 until n) {
            val x = a[k] - am
            val y = b(k) - bm
            num += x * y
            da += x * x
            db += y * y
        }
        return if (da <= 0 || db <= 0) 0.0 else num / sqrt(da * db)
    }
}
