// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.hr.BackgroundWindow
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.HrSamples
import com.heartline.shared.hr.IrnSensitivity
import com.heartline.shared.hr.RrFeatures
import com.heartline.shared.irn.IbiWindowQuality
import com.heartline.shared.irn.IrnState
import com.heartline.shared.irn.IrnThresholds
import com.heartline.shared.irn.IrregularRhythmDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real tracker readings from an exported Galaxy Watch8 Classic log (tools/replay/extract.py), so
 * the notices users actually got are tests. The old watch app (0.0.2.115-beta.1) sent irregular
 * rhythm notices from these windows; most readings are status -10 (the tracker's own "unreliable")
 * with impossible intervals (2,300 ms, 328 ms). The current checks must not read them as a rhythm.
 */
class ReplayTest {
    private fun load(name: String): List<HrSample> {
        val text = javaClass.getResourceAsStream("/replay/$name")!!.bufferedReader().readText()
        return text.lines().drop(1).filter { it.isNotBlank() }.map { line ->
            val (offset, status, ibis, ibiStatus) = line.split(",")
            val intervals = ibis.split(";").filter { it.isNotBlank() }.map { it.trim().toInt() }
            val statuses = ibiStatus.split(";").filter { it.isNotBlank() }.map { it.trim().toInt() }
            val bpm = if (status.toInt() == HrSamples.STATUS_SUCCESS &&
                intervals.isNotEmpty()
            ) {
                (60_000 / intervals.average()).toInt()
            } else {
                0
            }
            HrSamples.fromTracker(offset.toLong(), bpm, status.toInt(), intervals, statuses)
        }
    }

    /** Every interval taken at face value, as the old app did. */
    private fun allIntervals(name: String) = javaClass.getResourceAsStream("/replay/$name")!!.bufferedReader().readText()
        .lines().drop(1).filter { it.isNotBlank() }.flatMap { it.split(",")[2].split(";").filter(String::isNotBlank).map(String::toInt) }

    private val oldAlerts = listOf("old-watch-irn-alert-1321.csv", "old-watch-window-2216.csv")

    @Test
    fun windowsBehindOldFalseNoticesAreNotReadAsARhythm() {
        oldAlerts.forEach { name ->
            val samples = load(name)
            val quality = IbiWindowQuality.assess(samples)
            assertTrue("$name: $quality", quality is IbiWindowQuality.Result.Unreadable)
            val (state, alert) = IrregularRhythmDetector().onWindow(IrnState(), samples, { "x" })
            assertNull("$name gave a notice", alert)
            assertTrue("$name was judged: ${state.windows}", state.windows.isEmpty())
        }
    }

    @Test
    fun takenAtFaceValueTheSameIntervalsLookIrregular() {
        // Shows the fixture reproduces the false notice: unfiltered, the noise passed the old app's
        // rule (high sensitivity, turning points 0.45–0.95, no neighbour test)…
        val ibis = oldAlerts.flatMap { allIntervals(it) }
        val features = RrFeatures.of(ibis.map(Int::toDouble))!!
        val oldRule = IrnThresholds(0.10, 0.55, 0.45..0.95, minBeats = 40, dropIsolatedEctopics = false, maxLag1 = 1.0)
        assertTrue("$features", oldRule.irregular(features))
        // …and the current one doesn't pass it even taken at face value.
        assertTrue("$features", !IrnThresholds.HIGH.irregular(features))
    }

    /** A raw background window (tools/replay/sessions.py): offsetMs,status,bpm,ibis,ibiStatus. */
    private fun window(name: String): List<HrSample> {
        val text = javaClass.getResourceAsStream("/replay/window-$name.csv")!!.bufferedReader().readText()
        return text.lines().drop(1).filter { it.isNotBlank() }.map { line ->
            val (offset, status, bpm, ibis, ibiStatus) = line.split(",")
            fun ints(s: String) = s.split(";").filter { it.isNotBlank() }.map { it.trim().toInt() }
            HrSamples.fromTracker(offset.toLong(), bpm.toInt(), status.toInt(), ints(ibis), ints(ibiStatus))
        }
    }

    @Test
    fun aSteadyNightWindowIsReadForRhythmAndStress() {
        // 1:58 at night: the old window logic logged "0 samples, warming up" and threw it away.
        val samples = window("night-steady-0158")
        val rhythm = BackgroundWindow.rhythm(samples)
        assertNotNull(rhythm.reason, rhythm.samples)
        val (state, alert) = IrregularRhythmDetector().onWindow(IrnState(), rhythm.samples!!, { "x" }, IrnSensitivity.HIGH)
        assertNull(alert)
        assertEquals(false, state.windows.single().irregular)
        assertEquals(62.0, state.windows.single().meanBpm.toDouble(), 3.0)
        val hrv = BackgroundWindow.hrv(samples)!!
        assertTrue("${hrv.pairs}", hrv.pairs >= 100)
        assertEquals(62.0, hrv.bpm.toDouble(), 3.0)
    }

    @Test
    fun offWristAndWeakSignalWindowsAreNotRead() {
        listOf("off-wrist-1100", "weak-signal-1437").forEach { name ->
            val samples = window(name)
            val rhythm = BackgroundWindow.rhythm(samples)
            assertNull("$name: ${rhythm.reason}", rhythm.samples)
            assertTrue(rhythm.reason, rhythm.reason.startsWith("no reliable still reading"))
            assertNull(name, BackgroundWindow.hrv(samples))
        }
    }

    @Test
    fun noRealWindowIsJudgedIrregular() {
        // The wearer has a regular rhythm: whatever is read must be regular, at both sensitivities.
        listOf("night-steady-0158", "day-untrusted-beats-1305", "evening-mixed-1929", "night-mixed-0129").forEach { name ->
            val picked = BackgroundWindow.rhythm(window(name)).samples ?: return@forEach
            IrnSensitivity.entries.forEach { sensitivity ->
                val (state, alert) = IrregularRhythmDetector().onWindow(IrnState(), picked, { "x" }, sensitivity)
                assertNull("$name $sensitivity", alert)
                assertTrue("$name $sensitivity", state.windows.none { it.irregular })
            }
        }
    }

    @Test
    fun beatsTheTrackerDidNotTrustAreNotUsed() {
        // 13:05: every reading "good", but every beat interval flagged: nothing to read.
        val samples = window("day-untrusted-beats-1305")
        assertNull(BackgroundWindow.rhythm(samples).samples)
        assertNull(BackgroundWindow.hrv(samples))
    }

    @Test
    fun aSmoothSleepingRhythmIsNotIrregular() {
        // A nap with high HRV (RMSSD about 80 ms): three windows the first background rule judged
        // irregular. One held batch carried 10 minutes of beats in a single reading.
        listOf("nap-packed-1955", "nap-2038", "nap-2055").forEach { name ->
            val picked = BackgroundWindow.rhythm(window(name)).samples
            assertNotNull(name, picked)
            // About 60 s of beats is judged, not the whole batch.
            val beats = picked!!.sumOf { it.ibiMs.size }
            assertTrue("$name: $beats beats", beats in 40..110)
            IrnSensitivity.entries.forEach { sensitivity ->
                val (state, alert) = IrregularRhythmDetector().onWindow(IrnState(), picked, { "x" }, sensitivity)
                assertNull("$name $sensitivity", alert)
                assertEquals("$name $sensitivity", false, state.windows.single().irregular)
            }
        }
    }

    @Test
    fun randomIntervalsAreStillIrregular() {
        // What atrial fibrillation looks like to the rule: neighbouring intervals unrelated.
        val random = java.util.Random(3)
        val rr = List(80) { 500.0 + random.nextDouble() * 600 }
        val features = RrFeatures.of(rr)!!
        assertTrue("${features.lag1}", kotlin.math.abs(features.lag1) < 0.25)
        IrnSensitivity.entries.forEach { assertTrue("$it $features", IrnThresholds.of(it).irregular(features)) }
    }

    @Test
    fun windowsJudgedByTheEarlierRuleAreDropped() {
        val old = IrnState(windows = List(5) { com.heartline.shared.irn.WindowResult(it * 900_000L, irregular = true, meanBpm = 70) })
        val (state, alert) = IrregularRhythmDetector().onWindow(old, BackgroundWindow.rhythm(window("nap-2038")).samples!!, {
            "x"
        }, IrnSensitivity.HIGH)
        assertNull(alert)
        assertEquals(IrnThresholds.RULE, state.rule)
        assertEquals(1, state.windows.size)
    }

    /** The first reading (as they arrive, oldest first) at which the window would stop, and why. */
    private fun firstEnd(samples: List<HrSample>, startMs: Long = 0, elapsed: (HrSample) -> Long = { 0 }): Pair<Int, String>? {
        val seen = mutableListOf<HrSample>()
        samples.forEachIndexed { i, s ->
            seen += s
            BackgroundWindow.endReason(seen, startMs, elapsed(s), charging = false)?.let { return i to it }
        }
        return null
    }

    @Test
    fun aWindowOnTheChargerStopsWithinSeconds() {
        // 14:34, the watch on its charger: the tracker said off-body (-3) from the second reading
        // on, yet the window listened for the full 90 s (three times in the first hour).
        val (at, reason) = firstEnd(window("charging-1434"))!!
        assertEquals("off the wrist (tracker)", reason)
        assertTrue("stopped at reading $at", at <= BackgroundWindow.OFF_WRIST_READINGS)
    }

    @Test
    fun aNightWindowEndsOnceItsReadingsCoverTheListeningTime() {
        // 08:05 asleep: coroutine timers stood still and this window kept the sensor on for 29
        // minutes. Judged by the readings' own time it has what it needs after 90 s of readings.
        val samples = window("night-long-0805")
        val (at, reason) = firstEnd(samples)!!
        assertEquals("covered", reason)
        assertTrue("${samples[at].tsMs}", samples[at].tsMs in BackgroundWindow.LISTEN_MS..BackgroundWindow.LISTEN_MS + 2_000)
        // What it ends with is still read for the rhythm.
        assertNotNull(BackgroundWindow.rhythm(samples.take(at + 1)).samples)
    }

    @Test
    fun aWindowAlsoEndsOnTheChargerOffTheWristOrAtItsTimeLimit() {
        val few = window("night-steady-0158").take(10)
        assertNull(BackgroundWindow.endReason(few, few.first().tsMs, elapsedMs = 10_000, charging = false))
        assertEquals("charging", BackgroundWindow.endReason(few, 0, 10_000, charging = true))
        assertEquals("off the wrist", BackgroundWindow.endReason(few, 0, 10_000, charging = false, offWrist = true))
        assertEquals("time limit", BackgroundWindow.endReason(few, 0, BackgroundWindow.MAX_MS, charging = false))
        // Readings from before the window started (a new listener gets some) don't count.
        assertNull(BackgroundWindow.endReason(few, few.last().tsMs + 1, 10_000, charging = false))
    }
}
