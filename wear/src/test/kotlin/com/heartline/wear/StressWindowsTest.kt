// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

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
}
