// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.pots

import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import java.time.Instant
import java.time.ZoneId

/**
 * One large heart-rate rise after getting up: [restBpm] is the median in the still minutes before,
 * [peakBpm] the rate held in the [OrthostaticEventDetector.windowMs] after (docs/algorithms/POSTURAL_RISE.md).
 */
data class OrthostaticEvent(val startMs: Long, val restBpm: Int, val peakBpm: Int) {
    val riseBpm: Int get() = peakBpm - restBpm
}

/** Repeated large rises after getting up over the last days: what the notice and the heart screen show. */
data class PosturalPattern(
    val events: Int,
    val days: Int,
    val medianRestBpm: Int,
    val medianPeakBpm: Int,
    val medianRiseBpm: Int,
    /** Dizziness, palpitations, fatigue or fainting were tagged near one of the rises. */
    val withSymptoms: Boolean
)

/**
 * Finds heart-rate rises after getting up from rest. The watch has no posture sensor (its gravity
 * vector is the forearm's angle, not the body's), so "getting up" is the first moving-about
 * ([HrContext.ACTIVE]) minute after the wearer has been still for a while.
 *
 * Passive heart rate at rest arrives every few minutes, not every minute, so everything is judged
 * by time covered, not by back-to-back minutes:
 * - **Still before:** at least two still readings spanning [restMs] or more in the [lookbackMs]
 *   before, nothing else in that time (no moving about, workout or sleep: a rate still coming
 *   down from moving is not a resting rate), and the last of them at most [maxGapMs] before.
 * - **Held after:** at least two readings in the [windowMs] after; the second highest is the
 *   rise, so one spike doesn't count.
 * - **Not a workout:** no exercise or sleep in the window, and no minute with [briskStepsPerMinute]
 *   or more (brisk walking or stairs raise the rate by themselves). Minutes from older watches
 *   carry no steps and are judged by their context alone.
 *
 * A rise of [minRiseBpm] or more counts: the standing-test criterion for adults (30 bpm; 40 bpm
 * from 12 to 19 years, see [minRiseFor]). Only rises whose window has passed by [nowMs] are judged,
 * so a rise is never found from half its window.
 */
class OrthostaticEventDetector(
    private val minRiseBpm: Int = ADULT_RISE_BPM,
    private val restMs: Long = 5 * MINUTE,
    private val lookbackMs: Long = 15 * MINUTE,
    private val maxGapMs: Long = 10 * MINUTE,
    private val windowMs: Long = 10 * MINUTE,
    private val briskStepsPerMinute: Int = 100
) {
    fun detect(minutes: List<HrMinute>, nowMs: Long): List<OrthostaticEvent> {
        val sorted = minutes.sortedBy { it.minuteStartMs }.distinctBy { it.minuteStartMs }
        val events = mutableListOf<OrthostaticEvent>()
        var nextFrom = Long.MIN_VALUE
        sorted.forEachIndexed { i, m ->
            if (m.activity != HrContext.ACTIVE || m.minuteStartMs < nextFrom) return@forEachIndexed
            val t = m.minuteStartMs
            if (t + windowMs > nowMs) return@forEachIndexed
            var from = i
            while (from > 0 && sorted[from - 1].minuteStartMs >= t - lookbackMs) from--
            val before = sorted.subList(from, i)
            if (before.size < 2 || before.any { it.activity != HrContext.REST }) return@forEachIndexed
            if (before.last().minuteStartMs < t - maxGapMs || before.first().minuteStartMs > t - restMs) return@forEachIndexed
            val after = sorted.subList(i, sorted.size).takeWhile { it.minuteStartMs < t + windowMs }
            if (after.size < 2) return@forEachIndexed
            if (after.any { it.activity == HrContext.EXERCISE || it.activity == HrContext.SLEEP }) return@forEachIndexed
            if (after.any { (it.steps ?: 0) >= briskStepsPerMinute }) return@forEachIndexed
            val rest = median(before.map { it.avgBpm })
            val held = after.map { it.avgBpm }.sortedDescending()[1]
            if (held - rest >= minRiseBpm) {
                events += OrthostaticEvent(t, rest, held)
                nextFrom = t + windowMs
            }
        }
        return events
    }

    companion object {
        private const val MINUTE = 60_000L
        const val ADULT_RISE_BPM = 30
        const val TEEN_RISE_BPM = 40

        /** The rise that counts at [age]: 40 bpm from 12 to 19 years (their rates rise more on standing), else 30. */
        fun minRiseFor(age: Int?) = if (age != null && age in 12..19) TEEN_RISE_BPM else ADULT_RISE_BPM
    }
}

/**
 * Whether the rises make a pattern worth a notice: at least [minEvents] of them on [minDays]
 * different days within [windowDays]. One morning of several rises, or a single day, is not a
 * pattern. Tagged symptoms only describe the pattern; they never lower the bar.
 */
class PosturalPatternRules(
    private val minEvents: Int = 3,
    private val minDays: Int = 3,
    private val windowDays: Int = 14,
    private val symptomWindowMs: Long = 30 * 60_000L
) {
    fun evaluate(
        events: List<OrthostaticEvent>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        symptomTimesMs: List<Long> = emptyList()
    ): PosturalPattern? {
        val recent = events.filter { it.startMs <= nowMs && nowMs - it.startMs < windowDays * DAY }
        val days = recent.map { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }.toSet().size
        if (recent.size < minEvents || days < minDays) return null
        val symptoms = recent.any { e -> symptomTimesMs.any { it in e.startMs..e.startMs + symptomWindowMs } }
        return PosturalPattern(
            events = recent.size,
            days = days,
            medianRestBpm = median(recent.map { it.restBpm }),
            medianPeakBpm = median(recent.map { it.peakBpm }),
            medianRiseBpm = median(recent.map { it.riseBpm }),
            withSymptoms = symptoms
        )
    }

    companion object {
        const val DAY = 86_400_000L

        /** One notice per two weeks at most: the pattern is slow, and repeating it adds nothing. */
        const val COOLDOWN_MS = 14 * DAY
    }
}

private fun median(values: List<Int>): Int = values.sorted()[values.size / 2]
