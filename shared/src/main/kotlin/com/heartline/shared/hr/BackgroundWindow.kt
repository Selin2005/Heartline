// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.hr

import com.heartline.shared.irn.IbiWindowQuality
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Chooses what a background window can be read for, from every reading it received.
 *
 * The Samsung tracker sends its readings in batches, minutes late while the screen is off, and a
 * new listener first gets readings from before it started. So readings are judged by their own
 * timestamps, not by when they arrived, and the best stretch is used wherever it lies
 * (docs/algorithms/HEART_MONITORING.md, "Background windows").
 */
object BackgroundWindow {
    /** A rhythm check reads 60 seconds of beats. */
    const val RHYTHM_SPAN_MS = 60_000L
    private const val RHYTHM_MAX_MS = 75_000L

    /** HRV for stress: successive pairs of trusted beats within two minutes. */
    const val HRV_MIN_PAIRS = 40
    private const val HRV_MAX_MS = 120_000L

    /** A beat more than 20 % away from the one before is an extra beat or an artefact, not HRV. */
    private const val HRV_MAX_STEP = 0.2

    /** How long a background window listens, by the readings' own time. */
    const val LISTEN_MS = 90_000L

    /** The hard limit for a window, by the watch's elapsed time (which counts deep sleep). */
    const val MAX_MS = 150_000L

    /** This many readings in a row off the wrist, with no good one, end a window. */
    const val OFF_WRIST_READINGS = 8

    data class Rhythm(val samples: List<HrSample>?, val reason: String)

    /**
     * Why a window should stop listening now, or null to go on. Decided from the readings and
     * clocks that keep counting while the watch sleeps, never from coroutine timers alone: those
     * stand still in deep sleep, and a real night had windows that kept the sensor on for up to
     * 29 minutes instead of 90 seconds (docs/algorithms/HEART_MONITORING.md, "Background windows").
     *
     * @param startMs when the window started listening (wall clock); earlier readings don't count.
     * @param elapsedMs how long the window has run by the elapsed-realtime clock.
     */
    fun endReason(samples: List<HrSample>, startMs: Long, elapsedMs: Long, charging: Boolean, offWrist: Boolean = false): String? {
        if (charging) return "charging"
        if (offWrist) return "off the wrist"
        if (elapsedMs >= MAX_MS) return "time limit"
        val own = samples.filter { it.tsMs >= startMs }.sortedBy { it.tsMs }
        if (own.size >= OFF_WRIST_READINGS && own.none { it.onBody && it.reliable } && own.takeLast(OFF_WRIST_READINGS).none { it.onBody }) {
            return "off the wrist (tracker)"
        }
        if (own.isNotEmpty() && own.last().tsMs - startMs >= LISTEN_MS) return "covered"
        return null
    }

    data class HrvReading(val rmssdMs: Double, val bpm: Int, val pairs: Int, val startMs: Long)

    /** A reading whose intervals add up to more than this carries a held batch of beats. */
    private const val PACKED_MS = 3_000

    private fun ordered(samples: List<HrSample>) = unpack(samples.sortedBy { it.tsMs }.distinctBy { it.tsMs })

    /**
     * Puts every beat at its own time. A held batch can arrive as one reading with minutes of
     * intervals (665 beats, 10 minutes, in a real nap); they follow the reading's time. Each beat
     * becomes a reading of its own, worn and still as the real reading at or before it was.
     * Judging that batch as one "window" read 10 minutes against limits made for one, and a
     * smooth sleeping rhythm looked irregular.
     */
    internal fun unpack(sorted: List<HrSample>): List<HrSample> {
        if (sorted.none { it.rawIbiMs.sum() > PACKED_MS }) return sorted
        val real = sorted.filter { it.rawIbiMs.sum() <= PACKED_MS }
        fun around(ts: Long, packed: HrSample) = real.lastOrNull { it.tsMs <= ts }?.takeIf { ts - it.tsMs <= 5_000 } ?: packed
        val beats = sorted.filter { it.rawIbiMs.sum() > PACKED_MS }.flatMap { packed ->
            var t = packed.tsMs
            packed.rawIbiMs.mapIndexed { k, ibi ->
                t += ibi
                val ok = packed.rawIbiOk.getOrElse(k) { false }
                val state = around(t, packed)
                HrSample(
                    tsMs = t,
                    bpm = packed.bpm,
                    ibiMs = if (ok) listOf(ibi) else emptyList(),
                    onBody = packed.onBody && state.onBody,
                    moving = packed.moving || state.moving,
                    reliable = packed.reliable,
                    rejectedIbis = if (ok) 0 else 1,
                    rawIbiMs = listOf(ibi),
                    rawIbiOk = listOf(ok)
                )
            }
        }
        // Real readings without beats keep the still/worn record; beats take their place in time.
        return (real + beats).sortedBy { it.tsMs }.distinctBy { it.tsMs }
    }

    private fun HrSample.usable() = reliable && onBody && !moving && bpm > 0

    /**
     * The newest 60–75 s stretch that passes the rhythm checks' quality test
     * ([IbiWindowQuality]), or null and why none did.
     */
    fun rhythm(all: List<HrSample>): Rhythm {
        val samples = ordered(all)
        if (samples.isEmpty()) return Rhythm(null, "no readings")
        val starts = samples.indices.filter { samples[it].usable() }
        if (starts.isEmpty()) return Rhythm(null, "no reliable still reading (${statusMix(samples)})")
        var reason: String? = null
        // Every start: one reading can carry minutes of beats (a held batch), so neighbours differ.
        for (i in starts.reversed()) {
            val start = samples[i].tsMs
            val stretch = samples.subList(i, samples.size).takeWhile { it.tsMs - start < RHYTHM_MAX_MS }
            if (stretch.last().tsMs - start < RHYTHM_SPAN_MS - 1_000) continue
            when (val q = IbiWindowQuality.assess(stretch)) {
                is IbiWindowQuality.Result.Readable -> return Rhythm(stretch, "readable")
                is IbiWindowQuality.Result.Unreadable -> if (reason == null) reason = q.reason
            }
        }
        return Rhythm(null, reason ?: "under 60 s of reliable still signal (${statusMix(samples)})")
    }

    /**
     * RMSSD and heart rate for stress from successive trusted beats: the stretch of up to two
     * minutes with the most pairs, at least [HRV_MIN_PAIRS]. Wrist intervals are often flagged
     * one by one, so unlike the rhythm check this doesn't need nine in ten trusted, only enough
     * true neighbours (ultra-short RMSSD is valid from about 30–60 s of beats).
     */
    fun hrv(all: List<HrSample>): HrvReading? {
        val samples = ordered(all)
        var best: HrvReading? = null
        for (i in samples.indices.reversed()) {
            if (!samples[i].usable()) continue
            val start = samples[i].tsMs
            // A moving or off-wrist reading ends the stretch.
            val stretch = samples.subList(i, samples.size).takeWhile { it.tsMs - start < HRV_MAX_MS && it.onBody && !it.moving }
            val reading = pairs(stretch) ?: continue
            if (best == null || reading.pairs > best.pairs) best = reading
        }
        return best
    }

    private fun pairs(stretch: List<HrSample>): HrvReading? {
        val diffs = mutableListOf<Double>()
        val beats = mutableListOf<Int>()
        var previous: Int? = null
        var previousTs: Long? = null
        for (s in stretch) {
            // Readings missing in between: the next beat doesn't follow the last one.
            if (previousTs != null && s.tsMs - previousTs > s.rawIbiMs.sum() + 2_000) previous = null
            previousTs = s.tsMs
            s.rawIbiMs.forEachIndexed { k, ibi ->
                val ok = s.reliable && s.rawIbiOk.getOrElse(k) { false } && ibi in 300..2000
                if (!ok) {
                    previous = null
                    return@forEachIndexed
                }
                beats += ibi
                previous?.let { p -> if (abs(ibi - p) <= p * HRV_MAX_STEP) diffs += (ibi - p).toDouble() }
                previous = ibi
            }
        }
        if (diffs.size < HRV_MIN_PAIRS) return null
        val ibiBpm = 60_000.0 / beats.average()
        val reported = stretch.filter { it.reliable && it.bpm > 0 }.map { it.bpm }.sorted()
        if (reported.isNotEmpty()) {
            val median = reported[reported.size / 2].toDouble()
            if (abs(ibiBpm - median) > median * IbiWindowQuality.MAX_RATE_MISMATCH) return null
        }
        return HrvReading(sqrt(diffs.sumOf { it * it } / diffs.size), ibiBpm.toInt(), diffs.size, stretch.first().tsMs)
    }

    /** "75 readings: 40 good, 30 weak, 5 off" for the log. */
    fun statusMix(samples: List<HrSample>): String {
        val good = samples.count { it.reliable && it.onBody }
        val off = samples.count { !it.onBody }
        val moving = samples.count { it.reliable && it.onBody && it.moving }
        return "${samples.size} readings: ${good - moving} good, $moving moving, ${samples.size - good - off} weak, $off off-wrist"
    }

    /** Share of the trusted intervals among all the reliable readings' intervals, for the log. */
    fun trustedShare(samples: List<HrSample>): Int {
        val all = samples.filter { it.reliable }.sumOf { it.rawIbiMs.size }
        return if (all == 0) 0 else samples.filter { it.reliable }.sumOf { it.ibiMs.size } * 100 / all
    }
}
