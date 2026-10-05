// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.dsp

import kotlin.math.abs

/**
 * Repairs a raw PPG channel before analysis. Found on a Galaxy Watch8 Classic: the green PPG
 * inside ECG_ON_DEMAND has a real value only every 5th sample (100 Hz in a 500 Hz stream, the
 * rest -1), and its level jumps by tens of thousands of counts when the sensor changes gain.
 * Both made every precise-mode feature meaningless until repaired.
 */
object PpgRepair {
    /** The SDK's "no value" placeholder in the ECG tracker's PPG channel. */
    const val PLACEHOLDER = -1f

    /**
     * Placeholders and non-finite values become NaN, and so do exact zeros before the first real
     * value (the tracker's first point can be all zeros on a real Galaxy Watch6).
     */
    fun markMissing(x: FloatArray): FloatArray {
        val first = x.indexOfFirst { it != 0f }.let { if (it < 0) x.size else it }
        return FloatArray(x.size) { i -> x[i].takeIf { i >= first && it.isFinite() && it != PLACEHOLDER } ?: Float.NaN }
    }

    /** Share of samples that carry a value. */
    fun validShare(x: FloatArray): Double = if (x.isEmpty()) 0.0 else x.count { it.isFinite() && it != PLACEHOLDER }.toDouble() / x.size

    /**
     * Missing samples are linearly interpolated between their neighbours; then level jumps (a step
     * much larger than the signal's normal sample-to-sample change) are removed by shifting
     * everything after them. Null when fewer than half the samples have a value.
     */
    fun repair(raw: FloatArray): FloatArray? {
        val x = markMissing(raw)
        val valid = x.indices.filter { !x[it].isNaN() }
        if (valid.isEmpty() || valid.size < x.size * MIN_VALID_SHARE) return null
        val out = x.copyOf()
        // Interpolate gaps (edges take the nearest value).
        for (i in 0 until valid.first()) out[i] = x[valid.first()]
        for (i in valid.last() + 1 until x.size) out[i] = x[valid.last()]
        for ((a, b) in valid.zipWithNext()) {
            if (b - a <= 1) continue
            for (i in a + 1 until b) out[i] = x[a] + (x[b] - x[a]) * (i - a) / (b - a)
        }
        return removeSteps(out)
    }

    /** Level jumps larger than [STEP_FACTOR] × the median absolute change are cancelled. */
    fun removeSteps(x: FloatArray): FloatArray {
        if (x.size < 10) return x
        val diffs = FloatArray(x.size - 1) { abs(x[it + 1] - x[it]) }
        val typical = diffs.sorted()[diffs.size / 2].coerceAtLeast(1e-6f)
        val out = x.copyOf()
        var shift = 0f
        for (i in 1 until x.size) {
            val d = x[i] - x[i - 1]
            if (abs(d) > typical * STEP_FACTOR) shift -= d
            out[i] = x[i] + shift
        }
        return out
    }

    /** One value in five (the ECG tracker's real PPG rate) is enough; fewer is not a signal. */
    const val MIN_VALID_SHARE = 0.15

    /** A pulse changes the level gradually; a jump this many times the typical change is a gain switch. */
    const val STEP_FACTOR = 60f
}
