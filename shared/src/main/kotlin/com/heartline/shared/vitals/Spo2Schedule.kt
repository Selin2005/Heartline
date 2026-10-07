// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.vitals

/**
 * When the watch measures blood oxygen in the background, as one pure decision the worker only
 * carries out (docs/algorithms/VITALS_MONITORING.md, "Blood oxygen schedule").
 *
 * A real log (10/04–10/07) had 14 readings in 55 runs asleep but only one valid reading in three
 * days awake: the arm had to be still for 15 s before measuring, a wrist movement during it
 * rejected what the sensor had accepted, and after two put-offs no retry was ever scheduled again.
 */
object Spo2Schedule {
    sealed interface Decision {
        data object Measure : Decision

        /** Moving now: try again in [retryMinutes]. */
        data class PutOff(val retryMinutes: Int, val reason: String) : Decision

        data class Skip(val reason: String) : Decision
    }

    /** Awake, the arm is watched this long before measuring. */
    const val STILL_CHECK_MS = 5_000L

    /** Put off by movement: tried again this much later, at most [MAX_RETRIES] times per slot. */
    const val RETRY_MINUTES = 10
    const val MAX_RETRIES = 4

    /** Awake, at most this many steps a minute (over the last [STEP_MINUTES] minutes); asleep, more turning is fine. */
    const val AWAKE_STEPS = 5.0
    const val ASLEEP_STEPS = 20.0
    const val STEP_MINUTES = 2

    /** No background SpO2 on a low battery by day. */
    const val LOW_BATTERY = 15

    /** The passive heart rate is compared with the sensor's only while it is this fresh. */
    const val FRESH_HEART_RATE_MS = 5 * 60_000L

    /** The time slot a reading is due in: one per [everyMinutes]; put-offs are counted per slot. */
    fun slot(nowMs: Long, everyMinutes: Int): Long = nowMs / (everyMinutes * 60_000L)

    /** Put-offs so far in the current slot (a new slot starts again from none). */
    fun retriesNow(storedSlot: Long, storedRetries: Int, nowMs: Long, everyMinutes: Int) =
        if (storedSlot == slot(nowMs, everyMinutes)) storedRetries else 0

    fun decide(
        nowMs: Long,
        lastSpo2Ms: Long,
        everyMinutes: Int,
        asleep: Boolean,
        inSleep: Boolean,
        battery: Int,
        stepsPerMinute: Double,
        active: Boolean,
        armMoved: Boolean,
        retries: Int
    ): Decision {
        // A few minutes early is still due: the worker's own timing drifts.
        if (nowMs - lastSpo2Ms < (everyMinutes - 5) * 60_000L) return Decision.Skip("not due")
        if (asleep && !inSleep) return Decision.Skip("off in sleep")
        if (!asleep && battery < LOW_BATTERY) return Decision.Skip("battery $battery %")
        val moving = active || stepsPerMinute >= (if (asleep) ASLEEP_STEPS else AWAKE_STEPS)
        if (moving || armMoved) {
            val why = if (moving) "steps" else "arm moving"
            return if (retries < MAX_RETRIES) Decision.PutOff(RETRY_MINUTES, why) else Decision.Skip("$why, no more tries this slot")
        }
        return Decision.Measure
    }

    /** The passive heart rate to check the sensor's against, only when it is fresh. */
    fun recentBpm(latestBpm: Int?, latestAtMs: Long?, nowMs: Long): Int? =
        latestBpm?.takeIf { latestAtMs != null && nowMs - latestAtMs <= FRESH_HEART_RATE_MS }

    /**
     * Which readings of one try count. A low first reading counts only when its re-check agrees:
     * with no re-check result it is dropped, and with a normal one the re-check stands alone (the
     * log had 80 % kept awake at rest when the re-check gave nothing).
     */
    fun confirmed(first: Spo2Sample, rechecks: List<Spo2Sample>, isLow: (Int) -> Boolean): List<Spo2Sample> = when {
        !isLow(first.percent) -> listOf(first) + rechecks
        rechecks.isEmpty() -> emptyList()
        isLow(rechecks.first().percent) -> listOf(first) + rechecks
        else -> rechecks
    }
}
