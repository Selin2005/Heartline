// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.hr

import kotlin.math.abs
import kotlin.math.roundToInt
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

/** Groups 1 Hz samples into per-minute summaries (reliable on-body samples only). */
object MinuteAggregator {
    private const val MINUTE = 60_000L

    /**
     * [activity]: what the wearer was doing in the minute starting at the given time, if known
     * (sleep or exercise from the watch). Otherwise, and when that says rest, a minute with
     * movement counts as active.
     */
    fun aggregate(samples: List<HrSample>, activity: (minuteStartMs: Long) -> HrContext? = { null }): List<HrMinute> = samples
        .filter { it.onBody && it.reliable && it.bpm > 0 }
        .groupBy { it.tsMs / MINUTE * MINUTE }
        .toSortedMap()
        .map { (start, group) ->
            val moving = group.any { it.moving }
            val context = when (val known = activity(start)) {
                null, HrContext.REST -> if (moving) HrContext.ACTIVE else HrContext.REST
                else -> known
            }
            HrMinute(
                minuteStartMs = start,
                avgBpm = group.map { it.bpm }.average().roundToInt(),
                minBpm = group.minOf { it.bpm },
                maxBpm = group.maxOf { it.bpm },
                rmssdMs = Hrv.compute(group.flatMap { it.ibiMs })?.rmssdMs,
                resting = context == HrContext.REST,
                activity = context
            )
        }

    /** Combines two summaries of the same minute (e.g. one closed early, then more samples). */
    fun merge(a: HrMinute, b: HrMinute): HrMinute = a.copy(
        avgBpm = (a.avgBpm + b.avgBpm) / 2,
        minBpm = minOf(a.minBpm, b.minBpm),
        maxBpm = maxOf(a.maxBpm, b.maxBpm),
        rmssdMs = a.rmssdMs ?: b.rmssdMs,
        resting = a.resting && b.resting,
        activity = if (a.activity == HrContext.REST) b.activity else a.activity
    )
}
