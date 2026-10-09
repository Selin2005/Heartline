// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.measure

import com.heartline.shared.model.Metric

/**
 * The watch route of a measurement started from the phone: `remote/<METRIC>?session=<id>`, with
 * `&round=<n>` for a blood-pressure calibration round. Both apps build and read it here.
 */
data class RemoteMeasureLink(val metric: Metric, val sessionId: String, val round: Int? = null) {
    val route: String get() = "$PREFIX${metric.name}?session=$sessionId" + (round?.let { "&round=$it" } ?: "")

    companion object {
        const val PREFIX = "remote/"

        /** The link in [route] (as from a deep link, `src` already removed), or null. */
        fun parse(route: String): RemoteMeasureLink? {
            if (!route.startsWith(PREFIX)) return null
            val path = route.removePrefix(PREFIX).substringBefore('?')
            val metric = Metric.entries.firstOrNull { it.name == path } ?: return null
            if (!metric.measuresOnPhone) return null
            val params = route.substringAfter('?', "").split('&').mapNotNull {
                val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
                k to v
            }.toMap()
            val session = params["session"]?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
            return RemoteMeasureLink(metric, session, params["round"]?.toIntOrNull()?.takeIf { it in 1..4 })
        }
    }
}

/**
 * Collects a heart-rate check: readings come in about once a second; the result is their median
 * and range once [DURATION_MS] of good readings are in. Pure, so the watch and tests share it.
 */
class HeartRateCheck {
    private val readings = mutableListOf<Int>()
    private var goodMs = 0L

    /** Seconds of good readings so far, as 0…1. */
    val progress: Float get() = (goodMs.toFloat() / DURATION_MS).coerceIn(0f, 1f)
    val done: Boolean get() = goodMs >= DURATION_MS
    val latest: Int? get() = readings.lastOrNull()

    /**
     * A reading [bpm] covering [elapsedMs] since the previous one; [good] false when the sensor
     * flagged it (it then counts for nothing, so the progress waits).
     */
    fun add(bpm: Int, good: Boolean, elapsedMs: Long) {
        if (!good || bpm !in 30..230) return
        readings += bpm
        goodMs += elapsedMs.coerceIn(0, 2_000)
    }

    /** The result, or null when there were too few good readings. */
    fun result(): Result? {
        if (readings.size < MIN_READINGS) return null
        val sorted = readings.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2] + 1) / 2
        return Result(median, sorted.first(), sorted.last(), sorted.size)
    }

    data class Result(val bpm: Int, val min: Int, val max: Int, val samples: Int)

    companion object {
        const val DURATION_MS = 30_000L
        const val MIN_READINGS = 10

        /** Gives up after this long without enough good readings. */
        const val TIMEOUT_MS = 75_000L
    }
}
