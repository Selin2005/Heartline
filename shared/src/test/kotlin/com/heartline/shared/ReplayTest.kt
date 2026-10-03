// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.HrSamples
import com.heartline.shared.hr.RrFeatures
import com.heartline.shared.irn.IbiWindowQuality
import com.heartline.shared.irn.IrnState
import com.heartline.shared.irn.IrnThresholds
import com.heartline.shared.irn.IrregularRhythmDetector
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
        // Shows the fixture reproduces the false notice: unfiltered, the noise passes the rhythm test.
        val ibis = oldAlerts.flatMap { allIntervals(it) }
        val features = RrFeatures.of(ibis.map(Int::toDouble))!!
        assertTrue("$features", IrnThresholds.HIGH.irregular(features))
    }
}
