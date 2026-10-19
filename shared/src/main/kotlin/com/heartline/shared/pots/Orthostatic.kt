// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.pots

import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import kotlinx.serialization.Serializable

private const val MINUTE = 60_000L
private const val DAY = 24 * 60 * MINUTE

/** One large heart-rate rise after getting up from rest: [restBpm] before, [peakBpm] within the window after. */
@Serializable
data class OrthostaticEvent(val startMs: Long, val restBpm: Int, val peakBpm: Int) {
    val riseBpm: Int get() = peakBpm - restBpm
}

/** The wearer's repeated large rises after getting up, for the phone to show. */
@Serializable
data class PotsFinding(val events: Int, val days: Int, val medianRiseBpm: Int, val withSymptoms: Boolean)

/**
 * Finds heart-rate rises after getting up from rest. There is no posture sensor: "getting up" is
 * a stretch of calm [HrContext.REST] minutes followed by [HrContext.ACTIVE] minutes. Windows that
 * contain exercise are skipped, so stairs and workouts don't count.
 */
class OrthostaticEventDetector(
    private val restMinutes: Int = 5,
    private val baselineMinutes: Int = 3,
    private val windowMinutes: Int = 10,
    private val minRiseBpm: Int = 30,
    private val minPeakBpm: Int = 100
) {
    fun detect(minutes: List<HrMinute>): List<OrthostaticEvent> {
        val sorted = minutes.sortedBy { it.minuteStartMs }
        val byStart = sorted.associateBy { it.minuteStartMs }
        val events = mutableListOf<OrthostaticEvent>()
        var skipUntil = Long.MIN_VALUE
        for (m in sorted) {
            if (m.minuteStartMs < skipUntil || m.activity != HrContext.ACTIVE) continue
            val rest = (1..restMinutes).map { byStart[m.minuteStartMs - it * MINUTE] }
            if (rest.any { it == null || it.activity != HrContext.REST }) continue
            val baseline = median(rest.take(baselineMinutes).map { it!!.avgBpm })
            val window = (0 until windowMinutes).mapNotNull { byStart[m.minuteStartMs + it * MINUTE] }
            if (window.any { it.activity == HrContext.EXERCISE || it.activity == HrContext.SLEEP }) continue
            // Sustained: the lowest of three neighbouring minutes, so one spike doesn't count.
            val peak = window.windowed(3).maxOfOrNull { w -> w.minOf { it.avgBpm } } ?: continue
            if (peak - baseline >= minRiseBpm && peak >= minPeakBpm) {
                events += OrthostaticEvent(m.minuteStartMs, baseline, peak)
                skipUntil = m.minuteStartMs + windowMinutes * MINUTE
            }
        }
        return events
    }

    private fun median(values: List<Int>): Int = values.sorted()[values.size / 2]
}

/**
 * A notice needs [minEvents] events on [minDays] different days within [windowDays]. Symptoms
 * logged by the wearer (dizziness, palpitations) only make the notice more specific; they never
 * lower the bar. One notice per [cooldownDays].
 */
class PotsPatternRules(
    private val minEvents: Int = 3,
    private val minDays: Int = 3,
    private val windowDays: Int = 14,
    private val cooldownDays: Int = 14
) {
    fun evaluate(
        events: List<OrthostaticEvent>,
        nowMs: Long,
        lastNoticeMs: Long?,
        symptomTimesMs: List<Long> = emptyList()
    ): PotsFinding? {
        if (lastNoticeMs != null && nowMs - lastNoticeMs < cooldownDays * DAY) return null
        val recent = events.filter { nowMs - it.startMs in 0..windowDays * DAY }
        val days = recent.map { it.startMs / DAY }.toSet().size
        if (recent.size < minEvents || days < minDays) return null
        val rises = recent.map { it.riseBpm }.sorted()
        val symptoms = recent.any { e -> symptomTimesMs.any { it in e.startMs..e.startMs + 30 * MINUTE } }
        return PotsFinding(recent.size, days, rises[rises.size / 2], symptoms)
    }
}
