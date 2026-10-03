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

    @Test
    fun aWindowIsTimedFromTheFirstReliableReading() {
        // After a blood-oxygen measurement: 30 s of "initial" readings (status 0, rate 0), then good ones.
        val opened = 1_000_000L
        val gate = com.heartline.wear.monitor.WindowGate(75_000, 45_000, opened)
        val decisions = (0 until 120).map { i ->
            val warming = i < 30
            gate.decide(HrSample(opened + i * 1_000L, if (warming) 0 else 80, listOf(750), reliable = !warming), opened + i * 1_000L)
        }
        assertEquals(30, decisions.count { it == com.heartline.wear.monitor.WindowGate.Decision.SKIP })
        assertEquals(75, decisions.count { it == com.heartline.wear.monitor.WindowGate.Decision.TAKE })
        assertEquals(opened + 30_000, gate.startedAtMs)
        // Never reliable: it gives up after the warm-up.
        val never = com.heartline.wear.monitor.WindowGate(75_000, 45_000, opened)
        val stop = (0 until 60).first { i -> never.decide(HrSample(opened + i * 1_000L, 0, emptyList(), reliable = false), opened + i * 1_000L) == com.heartline.wear.monitor.WindowGate.Decision.STOP }
        assertEquals(46, stop)
        assertNull(never.startedAtMs)
    }
}
