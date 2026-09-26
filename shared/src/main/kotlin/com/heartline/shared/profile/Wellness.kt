// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.profile

import kotlin.math.ln
import kotlin.math.roundToInt

enum class StressLevel { LOW, MEDIUM, HIGH }

/**
 * Stress score 0–100 from resting HRV (lower RMSSD → higher stress), nudged by skin
 * conductance on watches with EDA (Watch8+). A wellness indicator, like Samsung Health's stress.
 */
object StressIndex {
    private val lnLow = ln(15.0)
    private val lnHigh = ln(80.0)

    fun score(rmssdMs: Double, skinConductanceMicroSiemens: Float? = null): Int {
        val hrvPart = 100 * (1 - (ln(rmssdMs.coerceIn(5.0, 200.0)) - lnLow) / (lnHigh - lnLow))
        val edaPart = skinConductanceMicroSiemens?.let { ((it - 2f) / 8f).coerceIn(-1f, 1f) * 15 } ?: 0f
        return (hrvPart + edaPart).roundToInt().coerceIn(0, 100)
    }

    fun level(score: Int) = when {
        score < 34 -> StressLevel.LOW
        score < 67 -> StressLevel.MEDIUM
        else -> StressLevel.HIGH
    }
}

/** Skin temperature is shown as the change from the user's own recent baseline. */
object TemperatureBaseline {
    /** @return deviation from the median of up to [window] previous readings, or null without history. */
    fun deviation(latest: Float, previous: List<Float>, window: Int = 7): Float? {
        val recent = previous.take(window)
        if (recent.size < 3) return null
        val sorted = recent.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        return ((latest - median) * 10).roundToInt() / 10f
    }
}
