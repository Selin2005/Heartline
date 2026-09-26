// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.hr

import kotlin.math.abs
import kotlin.math.sqrt

data class HrvMetrics(val rmssdMs: Double, val sdnnMs: Double, val pnn50: Double, val count: Int)

/** Time-domain HRV from inter-beat intervals, with artefact rejection. */
object Hrv {
    /**
     * Drops out-of-range IBIs and beats more than 20 % from the local median (5 beats each side).
     * The median, unlike "the previous accepted beat", can't be captured by one early artefact
     * that would then reject every real beat after it.
     */
    fun clean(ibiMs: List<Int>): List<Int> {
        val inRange = ibiMs.filter { it in 300..2000 }
        return inRange.filterIndexed { i, ibi ->
            val window = inRange.subList(maxOf(0, i - 5), minOf(inRange.size, i + 6)).sorted()
            val median = window[window.size / 2]
            abs(ibi - median) <= median * 0.2
        }
    }

    fun compute(ibiMs: List<Int>): HrvMetrics? {
        val nn = clean(ibiMs)
        if (nn.size < 10) return null
        val mean = nn.average()
        val sdnn = sqrt(nn.sumOf { (it - mean) * (it - mean) } / (nn.size - 1))
        val diffs = nn.zipWithNext { a, b -> (b - a).toDouble() }
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        val pnn50 = diffs.count { abs(it) > 50 } * 100.0 / diffs.size
        return HrvMetrics(rmssd, sdnn, pnn50, nn.size)
    }
}

/** Groups 1 Hz samples into per-minute summaries (on-body samples only). */
object MinuteAggregator {
    private const val MINUTE = 60_000L

    fun aggregate(samples: List<HrSample>): List<HrMinute> = samples
        .filter { it.onBody && it.bpm > 0 }
        .groupBy { it.tsMs / MINUTE * MINUTE }
        .toSortedMap()
        .map { (start, group) ->
            HrMinute(
                minuteStartMs = start,
                avgBpm = group.map { it.bpm }.average().toInt(),
                minBpm = group.minOf { it.bpm },
                maxBpm = group.maxOf { it.bpm },
                rmssdMs = Hrv.compute(group.flatMap { it.ibiMs })?.rmssdMs,
                resting = group.none { it.moving }
            )
        }
}
