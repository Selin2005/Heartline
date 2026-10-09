// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.bubbles

import com.heartline.shared.model.Metric

/** What the measurement is doing; each phase has its own motion. */
enum class BubblePhase {
    /** Live, nothing to fill (the heart-rate screen): the globe turns and beats, fully lit. */
    IDLE,

    /** Waiting for the watch or the sensor: the points gather from all around into the globe. */
    FORMING,

    /** Measuring: the globe lights up with the progress and moves in the metric's own way. */
    MEASURING,

    /** Measuring but paused by a hint (hold still, wrist contact…): the points tremble, tinted amber. */
    HINT,

    /** Done: the globe swells once and settles, fully lit. */
    SUCCESS,

    /** Failed or cancelled: the globe dims and turns grey. */
    FAILED,
}

/** The look of one metric. */
enum class BubbleStyle {
    HEART,
    SPO2,
    TEMPERATURE,
    STRESS,
    BLOOD_PRESSURE,
    ECG,
    BODY,
    ;

    companion object {
        fun of(metric: Metric): BubbleStyle = when (metric) {
            Metric.HEART_RATE -> HEART
            Metric.SPO2 -> SPO2
            Metric.SKIN_TEMPERATURE -> TEMPERATURE
            Metric.STRESS -> STRESS
            Metric.BLOOD_PRESSURE -> BLOOD_PRESSURE
            Metric.ECG -> ECG
            Metric.BODY_COMPOSITION -> BODY
        }
    }
}

/** A heartbeat: a quick squeeze up, a slower release. [beat] is 0…1 through one beat; 1 at rest. */
fun beatScale(beat: Float, amount: Float): Float = when {
    beat < 0.12f -> 1f + amount * easeOut(beat / 0.12f)
    beat < 0.3f -> 1f + amount - amount * 1.35f * easeIn((beat - 0.12f) / 0.18f)
    beat < 0.5f -> 1f - amount * 0.35f + amount * 0.35f * easeOut((beat - 0.3f) / 0.2f)
    else -> 1f
}

private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)

private fun easeIn(x: Float) = x * x * x
