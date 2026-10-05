// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

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
    val turningPointRatio: Double,
    /**
     * Correlation of each interval with the next (lag 1). A sinus rhythm drifts smoothly with
     * breathing (clearly positive, about +0.3 to +0.6 in a real night of high HRV); the intervals of
     * atrial fibrillation are random (about 0). Equivalent to the Poincaré plot's
     * SD1/SD2 = sqrt((1 − r) / (1 + r)), round for AF.
     */
    val lag1: Double = 0.0
) {
    /** Irregularly irregular: high successive variation, spread-out and non-patterned intervals. */
    val isIrregular: Boolean get() = irregular(count, nRmssd, shannonEntropy, turningPointRatio)

    /** Clearly regular rhythm. */
    val isRegular: Boolean get() = count >= MIN_INTERVALS && nRmssd < 0.08 && cov < 0.10

    companion object {
        const val MIN_INTERVALS = 8

        /** The same rule from stored values (also used by blood pressure on its PPG beats). */
        fun irregular(count: Int, nRmssd: Double, entropy: Double, turningPoint: Double, minNRmssd: Double = 0.10) =
            count >= MIN_INTERVALS && nRmssd > minNRmssd && entropy > 0.55 && turningPoint in 0.45..0.95

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
            val variance = clean.sumOf { (it - mean) * (it - mean) } / clean.size
            val lag1 = if (variance <=
                0.0
            ) {
                1.0
            } else {
                clean.zipWithNext { a, b -> (a - mean) * (b - mean) }.sum() / (clean.size - 1) / variance
            }
            return RrFeatures(clean.size, mean, sdnn, rmssd, sdnn / mean, rmssd / mean, entropy, tpr, lag1)
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
