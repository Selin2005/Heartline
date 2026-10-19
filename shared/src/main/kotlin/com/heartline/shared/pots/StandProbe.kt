// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.pots

import com.heartline.shared.hr.ActivityTimeline
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.MonitorSettings
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** One heart-rate value of a stand probe: the median of a few seconds of the tracker's readings. */
@Serializable
data class StandReading(val tsMs: Long, val bpm: Int)

/**
 * A short measurement the watch takes right after the wearer gets up (docs/algorithms/POSTURAL_RISE.md,
 * section 2): the heart-rate tracker in a few slices over the minutes after [startMs], when the
 * motion sensor saw the wearer start moving after a still stretch. Passive heart rate at rest comes
 * minutes apart, so without it most getting-ups have no reading in the minutes that matter.
 *
 * [readings] are in time order; slices are told apart by the gaps between them. [stepsPerMinute]:
 * the steps over the probe, if the watch counted them (brisk walking raises the rate by itself).
 */
@Serializable
data class StandProbe(val startMs: Long, val readings: List<StandReading>, val stepsPerMinute: Int? = null) {
    /** The median of each slice, in time order (a gap of [SLICE_GAP_MS] or more starts a new one). */
    fun sliceMedians(): List<StandReading> {
        val slices = mutableListOf<MutableList<StandReading>>()
        readings.sortedBy { it.tsMs }.forEach { r ->
            val last = slices.lastOrNull()?.lastOrNull()
            if (last == null || r.tsMs - last.tsMs >= SLICE_GAP_MS) slices += mutableListOf(r) else slices.last() += r
        }
        return slices.map { s -> StandReading(s[s.size / 2].tsMs, s.map { it.bpm }.sorted()[s.size / 2]) }
    }

    companion object {
        const val SLICE_GAP_MS = 30_000L

        /** Readings are kept as one value per this many milliseconds (the median of the tracker's ~1 Hz). */
        const val BIN_MS = 5_000L

        /**
         * A probe from the tracker's readings: good, on-wrist ones only, as the median of each
         * [BIN_MS]. The tracker hands readings over in batches, so they are put by their own time.
         */
        fun of(startMs: Long, samples: List<HrSample>, stepsPerMinute: Int?): StandProbe {
            val readings = samples.filter { it.reliable && it.onBody && it.bpm > 0 }
                .groupBy { it.tsMs / BIN_MS }
                .toSortedMap()
                .map { (bin, group) -> StandReading(bin * BIN_MS + BIN_MS / 2, group.map { it.bpm }.sorted()[group.size / 2]) }
            return StandProbe(startMs, readings, stepsPerMinute)
        }
    }
}

/**
 * When the watch starts a stand probe (StandProbeWorker): the motion sensor fires as the wearer
 * starts moving, and this decides whether that is a getting-up worth measuring. The probe uses the
 * heart-rate tracker for about three minutes in all, so it is rationed:
 * - **Still before:** no movement fired the sensor for [QUIET_MS]. Moving about already is not
 *   getting up from rest.
 * - **Not asleep, not a workout, not in the usual sleep hours:** a getting-up at night is a
 *   different situation, and probes there would cost battery while the wearer sleeps.
 * - **At most [MAX_PER_DAY] a day, [MIN_APART_MS] apart:** the first getting-ups of the day are
 *   measured, and the probes stay a small share of the day's sensor time.
 */
object StandProbePolicy {
    /** When the slices start, after the motion: the first right away, then about 2.5 and 6 minutes in. */
    val SLICE_STARTS_MS = listOf(15_000L, 150_000L, 360_000L)

    /** Each slice listens until its readings cover this, or [SLICE_MAX_MS] at the most. */
    const val SLICE_MS = 40_000L
    const val SLICE_MAX_MS = 75_000L

    const val QUIET_MS = 10 * 60_000L
    const val MAX_PER_DAY = 6
    const val MIN_APART_MS = 45 * 60_000L

    /**
     * For watches without the motion sensor: steps that ended a still stretch, from the passive
     * step counts (which arrive in batches, minutes late). Returns when they started and when the
     * wearer last took steps before ([QUIET_MS] or more earlier, null if never), or null when the
     * newest steps don't start a getting-up or began more than [freshMs] before [nowMs], too late
     * for the first minutes.
     */
    fun gettingUpFromSteps(timeline: ActivityTimeline, nowMs: Long, freshMs: Long = 3 * 60_000L): Pair<Long, Long?>? {
        val walking = timeline.steps.filter { s ->
            val minutes = (s.endMs - s.startMs).coerceAtLeast(1) / 60_000.0
            s.steps / maxOf(minutes, 1.0 / 6) >= ActivityTimeline.ACTIVE_STEPS_PER_MINUTE
        }
        val start = walking.maxByOrNull { it.startMs }?.startMs ?: return null
        if (nowMs - start > freshMs) return null
        val lastMove = timeline.steps.filter { it.steps > 0 && it.startMs < start }.maxOfOrNull { it.endMs }
        if (lastMove != null && start - lastMove < QUIET_MS) return null
        return start to lastMove
    }

    /** Why a probe starts or not, for the log. */
    data class Decision(val start: Boolean, val reason: String)

    fun decide(
        nowMs: Long,
        lastMotionMs: Long?,
        probesMs: List<Long>,
        context: HrContext?,
        settings: MonitorSettings,
        zone: ZoneId = ZoneId.systemDefault()
    ): Decision {
        if (!settings.posturalRiseActive) return Decision(false, "off")
        if (context == HrContext.SLEEP) return Decision(false, "asleep")
        if (context == HrContext.EXERCISE) return Decision(false, "workout")
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        if (settings.isUsualSleep(local.hour * 60 + local.minute)) return Decision(false, "usual sleep hours")
        val quietFor = lastMotionMs?.let { nowMs - it }
        if (quietFor != null && quietFor < QUIET_MS) return Decision(false, "moved ${quietFor / 1_000} s before")
        val today = local.toLocalDate()
        val todays = probesMs.count { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today }
        if (todays >= MAX_PER_DAY) return Decision(false, "$MAX_PER_DAY today already")
        val sinceLast = probesMs.maxOrNull()?.let { nowMs - it }
        if (sinceLast != null && sinceLast < MIN_APART_MS) return Decision(false, "last probe ${sinceLast / 60_000} min ago")
        return Decision(true, "still for ${lastMotionMs?.let { "${(nowMs - it) / 60_000} min" } ?: "unknown"}")
    }
}
