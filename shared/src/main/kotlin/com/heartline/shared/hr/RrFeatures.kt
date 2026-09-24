package com.heartline.shared.hr

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Beat-to-beat irregularity features used for AFib-like detection on both the ECG (RR) and the
 * background PPG/IBI path (Dash et al., 2009: nRMSSD, Shannon entropy, turning point ratio).
 */
data class RrFeatures(
    val count: Int,
    val meanMs: Double,
    val sdnnMs: Double,
    val rmssdMs: Double,
    val cov: Double,
    val nRmssd: Double,
    val shannonEntropy: Double,
    val turningPointRatio: Double
) {
    /** Irregularly irregular: high successive variation, spread-out and non-patterned intervals. */
    val isIrregular: Boolean
        get() = count >= MIN_INTERVALS && nRmssd > 0.10 && shannonEntropy > 0.55 && turningPointRatio in 0.45..0.95

    /** Clearly regular rhythm. */
    val isRegular: Boolean get() = count >= MIN_INTERVALS && nRmssd < 0.08 && cov < 0.10

    companion object {
        const val MIN_INTERVALS = 8

        fun of(rrMs: List<Double>): RrFeatures? {
            val clean = rrMs.filter { it in 250.0..2500.0 }
            if (clean.size < 3) return null
            val mean = clean.average()
            val sdnn = sqrt(clean.sumOf { (it - mean) * (it - mean) } / (clean.size - 1))
            val diffs = clean.zipWithNext { a, b -> b - a }
            val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)

            // Entropy on the series without its two most extreme values at each end (outliers).
            val trimmed = clean.sorted().let { if (it.size > 10) it.subList(2, it.size - 2) else it }
            val entropy = shannon(trimmed)

            val turning = (1 until clean.size - 1).count { i ->
                (clean[i] > clean[i - 1] && clean[i] > clean[i + 1]) || (clean[i] < clean[i - 1] && clean[i] < clean[i + 1])
            }
            val tpr = if (clean.size > 2) turning.toDouble() / (clean.size - 2) else 0.0
            return RrFeatures(clean.size, mean, sdnn, rmssd, sdnn / mean, rmssd / mean, entropy, tpr)
        }

        /** Normalised Shannon entropy over 16 equal-width bins between min and max. */
        private fun shannon(values: List<Double>, bins: Int = 16): Double {
            val min = values.min()
            val max = values.max()
            if (abs(max - min) < 1e-9) return 0.0
            val counts = IntArray(bins)
            values.forEach { counts[(((it - min) / (max - min)) * (bins - 1)).toInt()]++ }
            val n = values.size.toDouble()
            val h = counts.filter { it > 0 }.sumOf {
                val p = it / n
                -p * ln(p)
            }
            return h / ln(bins.toDouble())
        }
    }
}
