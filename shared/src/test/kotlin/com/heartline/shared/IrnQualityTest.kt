// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.IrnSensitivity
import com.heartline.shared.irn.IbiWindowQuality
import com.heartline.shared.irn.IrnState
import com.heartline.shared.irn.IrregularRhythmDetector
import com.heartline.shared.sample.SyntheticHr
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** False irregular-rhythm alerts: off-wrist noise, weak signal and harmless extra beats. */
class IrnQualityTest {
    private var ids = 0
    private val id = { "id-${ids++}" }
    private val window = 15 * 60_000L

    /** A watch lying on a table: the tracker keeps searching and reports random "intervals". */
    private fun offWrist(start: Long, seed: Int, status: Boolean = false): List<HrSample> {
        val random = Random(seed)
        return List(60) { s ->
            HrSample(
                start + s * 1000L,
                random.nextInt(40, 160),
                List(random.nextInt(0, 3)) {
                    random.nextInt(300, 1600)
                },
                reliable = status
            )
        }
    }

    /** Sinus rhythm with [ectopicsPerMinute] premature beats, each followed by a compensatory pause. */
    private fun sinusWithEctopics(start: Long, bpm: Double, ectopicsPerMinute: Int, seed: Int): List<HrSample> {
        val random = Random(seed)
        val rr = 60_000.0 / bpm
        val beats = mutableListOf<Int>()
        var total = 0.0
        val ectopicAt = (5 until 70).shuffled(random).take(ectopicsPerMinute).toSet()
        var i = 0
        while (total < 60_000) {
            val value = when {
                i in ectopicAt -> rr * 0.65
                i - 1 in ectopicAt -> rr * 1.35
                else -> rr * (1 + 0.03 * (random.nextDouble() * 2 - 1))
            }
            beats += value.toInt()
            total += value
            i++
        }
        // One sample per second carrying the beats that ended in it.
        val samples = mutableListOf<HrSample>()
        var t = 0.0
        var pending = mutableListOf<Int>()
        var second = 0
        beats.forEach { b ->
            t += b
            while (second < (t / 1000).toInt() && second < 60) {
                samples += HrSample(start + second * 1000L, bpm.toInt(), pending)
                pending = mutableListOf()
                second++
            }
            pending += b
        }
        return samples
    }

    private fun run(windows: List<List<HrSample>>, sensitivity: IrnSensitivity = IrnSensitivity.STANDARD): List<HealthAlert> {
        val detector = IrregularRhythmDetector()
        var state = IrnState()
        return windows.mapNotNull { w ->
            val (next, alert) = detector.onWindow(state, w, id, sensitivity)
            state = next
            alert
        }
    }

    @Test
    fun offWristNoiseIsNeverRead() {
        assertTrue(IbiWindowQuality.assess(offWrist(0, 1)) is IbiWindowQuality.Result.Unreadable)
        // Even if the tracker claimed good readings, gaps and a rate mismatch give it away.
        assertTrue(IbiWindowQuality.assess(offWrist(0, 2, status = true)) is IbiWindowQuality.Result.Unreadable)
        assertTrue(run(List(24) { offWrist(it * window, it) }).isEmpty())
        assertTrue(run(List(24) { offWrist(it * window, it, status = true) }).isEmpty())
    }

    @Test
    fun aFewUnreliableReadingsMakeAWindowUnreadable() {
        val samples = SyntheticHr.samples(0, 60, bpm = 92.0, irregularity = 0.35, seed = 3)
            .mapIndexed { i, s -> if (i % 10 == 0) s.copy(reliable = false) else s }
        assertTrue(IbiWindowQuality.assess(samples) is IbiWindowQuality.Result.Unreadable)
        val flagged = SyntheticHr.samples(0, 60, bpm = 92.0, irregularity = 0.35, seed = 3).map { it.copy(rejectedIbis = 1) }
        assertTrue(IbiWindowQuality.assess(flagged) is IbiWindowQuality.Result.Unreadable)
    }

    @Test
    fun harmlessExtraBeatsDoNotAlert() {
        val windows = List(24) { sinusWithEctopics(it * window, 72.0, ectopicsPerMinute = 4, seed = it) }
        assertTrue(windows.all { IbiWindowQuality.assess(it) is IbiWindowQuality.Result.Readable })
        assertTrue(run(windows).isEmpty())
    }

    @Test
    fun irregularlyIrregularRhythmStillAlerts() {
        val windows = List(8) { SyntheticHr.samples(it * window, 60, bpm = 95.0, irregularity = 0.35, seed = it) }
        assertEquals(1, run(windows).size)
        assertEquals(1, run(windows, IrnSensitivity.HIGH).size)
    }

    @Test
    fun evidenceMustSpreadOverAnHour() {
        // Six irregular windows five minutes apart (25 minutes) are not enough on their own.
        val windows = List(6) { SyntheticHr.samples(it * 5 * 60_000L, 60, bpm = 95.0, irregularity = 0.35, seed = it) }
        assertTrue(run(windows).isEmpty())
    }

    @Test
    fun regularEcgAfterAnAlertLengthensTheQuietPeriod() {
        val detector = IrregularRhythmDetector()
        var state = IrnState()
        var t = 0L
        fun next(): HealthAlert? {
            val (s, alert) = detector.onWindow(
                state,
                SyntheticHr.samples(t, 60, bpm = 95.0, irregularity = 0.35, seed = (t / window).toInt()),
                id
            )
            state = s
            t += window
            return alert
        }
        var first: HealthAlert? = null
        while (first == null) first = next()
        state = state.withEcg(regular = true, atMs = first.atMs + 30 * 60_000L)
        // 30 hours later: past the usual 24 h, but within the 48 h after a regular ECG.
        val restart = t
        while (t < restart + 30 * 3_600_000L) assertNull(next())
        // An ECG long after the alert is not its follow-up.
        assertNull(IrnState(lastAlertMs = 0).withEcg(regular = true, atMs = 5 * 3_600_000L).regularEcgAtMs)
        assertNull(IrnState(lastAlertMs = 0).withEcg(regular = false, atMs = 60_000L).regularEcgAtMs)
    }
}
