// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.hr

import kotlinx.serialization.Serializable

/** The watch's own activity recognition: sleep, a workout, or neither. */
@Serializable
enum class WatchActivity { UNKNOWN, PASSIVE, EXERCISE, ASLEEP }

@Serializable
data class ActivityChange(val atMs: Long, val activity: WatchActivity)

@Serializable
data class StepSpan(val startMs: Long, val endMs: Long, val steps: Long)

/**
 * Recent activity states and step counts on the watch, so each heart-rate minute can be judged as
 * rest, sleep, moving about or exercise. Kept small: state changes for [STATE_KEEP_MS], steps for
 * [STEPS_KEEP_MS].
 */
@Serializable
data class ActivityTimeline(val changes: List<ActivityChange> = emptyList(), val steps: List<StepSpan> = emptyList()) {
    fun withChange(change: ActivityChange, nowMs: Long): ActivityTimeline {
        val all = (changes + change).sortedBy { it.atMs }.distinctBy { it.atMs }
        // Keep the state in effect at the cutoff too, so older minutes still know it.
        val cutoff = nowMs - STATE_KEEP_MS
        val keepFrom = all.indexOfLast { it.atMs <= cutoff }.coerceAtLeast(0)
        return copy(changes = all.drop(keepFrom))
    }

    fun withSteps(spans: List<StepSpan>, nowMs: Long): ActivityTimeline =
        copy(steps = (steps + spans).filter { it.endMs >= nowMs - STEPS_KEEP_MS }.distinct().sortedBy { it.startMs })

    /** What the wearer was doing in the minute starting at [minuteStartMs], or null when unknown or at rest. */
    fun contextAt(minuteStartMs: Long): HrContext? {
        val mid = minuteStartMs + MINUTE / 2
        when (changes.lastOrNull { it.atMs <= mid }?.activity) {
            WatchActivity.EXERCISE -> return HrContext.EXERCISE
            WatchActivity.ASLEEP -> return HrContext.SLEEP
            else -> Unit
        }
        return if (stepsPerMinute(minuteStartMs) >= ACTIVE_STEPS_PER_MINUTE) HrContext.ACTIVE else null
    }

    /** Steps in the minute starting at [minuteStartMs], spreading each span evenly over its length. */
    fun stepsPerMinute(minuteStartMs: Long): Double {
        val end = minuteStartMs + MINUTE
        return steps.sumOf { s ->
            val overlap = minOf(end, s.endMs) - maxOf(minuteStartMs, s.startMs)
            val length = (s.endMs - s.startMs).coerceAtLeast(1)
            if (overlap <= 0) 0.0 else s.steps * overlap.toDouble() / length
        }
    }

    companion object {
        const val MINUTE = 60_000L
        const val STATE_KEEP_MS = 48 * 3_600_000L
        const val STEPS_KEEP_MS = 3 * 3_600_000L

        /** Walking pace starts around 60 steps a minute; 20 already means the wearer was not still. */
        const val ACTIVE_STEPS_PER_MINUTE = 20.0
    }
}
