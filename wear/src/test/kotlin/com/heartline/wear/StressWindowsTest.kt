// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import com.heartline.shared.hr.BackgroundWindow
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.MonitorSettings
import com.heartline.wear.monitor.StressWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class StressWindowsTest {
    private val zone = ZoneOffset.UTC
    private val day = 20_000L * 86_400_000L

    /** 70 s of steady beats around [ibi] ms, alternating ±[swing] (RMSSD ≈ 2 × swing). */
    private fun window(ibi: Int = 900, swing: Int = 20, moving: Boolean = false): List<HrSample> {
        var t = day + 14 * 3_600_000L
        return (0 until 78).map { i ->
            val beat = ibi + if (i % 2 == 0) swing else -swing
            t += beat
            HrSample(t, 60_000 / ibi, listOf(beat), moving = moving)
        }
    }

    @Test
    fun aStillReadableWindowGivesRmssdAndRate() {
        val (rmssd, bpm) = StressWindows.measure(window())!!
        assertEquals(40.0, rmssd, 1.0)
        assertEquals(66, bpm)
        assertNull(StressWindows.measure(window(moving = true)))
    }

    @Test
    fun exerciseCountsForAnHourAfterAndUsualSleepStandsInWithoutActivity() {
        val at = day + 14 * 3_600_000L
        val settings = MonitorSettings()
        assertEquals(HrContext.EXERCISE, StressWindows.contextAt(at, settings, { if (it == at - 40 * 60_000L) HrContext.EXERCISE else HrContext.REST }, zone))
        assertEquals(HrContext.REST, StressWindows.contextAt(at, settings, { if (it == at - 90 * 60_000L) HrContext.EXERCISE else HrContext.REST }, zone))
        // No activity recognition: the usual sleep hours (23–7) decide.
        assertEquals(HrContext.SLEEP, StressWindows.contextAt(day + 2 * 3_600_000L, settings, { null }, zone))
        assertEquals(HrContext.REST, StressWindows.contextAt(at, settings, { null }, zone))
        assertNotNull(StressWindows.measure(window(swing = 5)))
        assertTrue(StressWindows.measure(window(swing = 5))!!.first < 15.0)
    }

    @Test
    fun readingsThatArriveLateAndBeforeTheWindowStillCount() {
        // A batch from before the listener started: 40 s of warm-up, then a still minute and more.
        val start = day + 3 * 3_600_000L
        val warming = (0 until 40).map { HrSample(start + it * 1_000L, 0, emptyList(), reliable = false) }
        var t = start + 40_000L
        val good = (0 until 90).map { i ->
            val beat = 950 + if (i % 2 == 0) 15 else -15
            t += beat
            HrSample(t, 63, listOf(beat))
        }
        val rhythm = BackgroundWindow.rhythm((good + warming).shuffled(java.util.Random(1)))
        assertNotNull(rhythm.samples)
        assertTrue(rhythm.samples!!.all { it.reliable })
        assertNotNull(StressWindows.measure(warming + good))
        // Only warm-up readings: nothing to read, and the log says why.
        val none = BackgroundWindow.rhythm(warming)
        assertNull(none.samples)
        assertTrue(none.reason, none.reason.startsWith("no reliable still reading"))
    }
}
