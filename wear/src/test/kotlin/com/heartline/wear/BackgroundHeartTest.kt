// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.BackgroundWindow
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.MonitorState
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.irn.IrnState
import com.heartline.shared.sample.SyntheticHr
import com.heartline.wear.monitor.BackgroundHeart
import com.heartline.wear.monitor.MonitorOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundHeartTest {
    private class Recorder : MonitorOutput {
        var irn = IrnState()
        var state = MonitorState()

        override suspend fun loadMonitorState() = state

        override suspend fun saveMonitorState(state: MonitorState) {
            this.state = state
        }
        val batches = mutableListOf<HrBatch>()
        val alerts = mutableListOf<HealthAlert>()

        override suspend fun loadIrnState() = irn

        override suspend fun saveIrnState(state: IrnState) {
            irn = state
        }

        override suspend fun enqueueBatch(batch: HrBatch) {
            batches += batch
        }

        override suspend fun enqueueAlert(alert: HealthAlert) {
            alerts += alert
        }

        override fun notify(alert: HealthAlert) = Unit
    }

    @Test
    fun passiveBatchesAreSentRightAway() = runBlocking {
        val out = Recorder()
        val heart = BackgroundHeart(out) { MonitorSettings() }
        // Health Services delivers a few minutes at a time; each delivery is flushed.
        heart.onPassive(SyntheticHr.samples(0, 5 * 60 + 1, bpm = 64.0).map { it.copy(ibiMs = emptyList()) })
        assertEquals(1, out.batches.size)
        // The last, partly filled minute is closed too: the next delivery may come in a new process.
        assertEquals(6, out.batches.single().minutes.size)
    }

    @Test
    fun alertHistorySurvivesANewProcess() = runBlocking {
        val out = Recorder()
        // Sparse resting readings above the safety net (nothing learnt yet, so not 130: there is no
        // usual to compare with right after install): one alert.
        val high = listOf(0, 6, 12, 18).map { HrSample(it * 60_000L, 160, emptyList()) }
        BackgroundHeart(out) { MonitorSettings() }.onPassive(high)
        assertEquals(listOf(AlertKind.HIGH_HEART_RATE), out.alerts.map { it.kind })
        // A new process (new BackgroundHeart) gets the next reading: no repeat within the cooldown.
        BackgroundHeart(out) { MonitorSettings() }.onPassive(listOf(HrSample(24 * 60_000L, 161, emptyList())))
        assertEquals(1, out.alerts.size)
    }

    @Test
    fun exerciseAtAHighRateDoesNotAlert() = runBlocking {
        val out = Recorder()
        val heart = BackgroundHeart(out, activity = { HrContext.EXERCISE }, age = { 30 }) { MonitorSettings() }
        // Passive heart rate every second during a 40-minute workout at 165 bpm (max for 30 is 187).
        heart.onPassive(SyntheticHr.samples(0, 40 * 60, bpm = 165.0).map { it.copy(ibiMs = emptyList()) })
        assertTrue(out.alerts.isEmpty())
        assertTrue(out.batches.flatMap { it.minutes }.all { it.activity == HrContext.EXERCISE && !it.resting })
        // Above the age maximum for a few minutes: one alert, marked as exercise.
        heart.onPassive(SyntheticHr.samples(40 * 60_000L, 4 * 60, bpm = 195.0).map { it.copy(ibiMs = emptyList()) })
        assertEquals(listOf(HrContext.EXERCISE), out.alerts.map { it.context })
    }

    @Test
    fun rhythmWindowMinutesCarryHrvToThePhone() = runBlocking {
        val out = Recorder()
        val heart = BackgroundHeart(out) { MonitorSettings() }
        heart.irn.resetWindow()
        val samples = SyntheticHr.samples(0, 75, bpm = 70.0)
        heart.onIrnWindow(samples, BackgroundWindow.rhythm(samples).samples)
        assertEquals(false, heart.irn.lastWindowIrregular)
        val minutes = out.batches.flatMap { it.minutes }
        assertTrue(minutes.isNotEmpty())
        assertTrue(minutes.first().rmssdMs != null)
    }

    @Test
    fun shortWindowsEveryQuarterHourDetectIrregularRhythm() = runBlocking {
        val out = Recorder()
        val heart = BackgroundHeart(out) { MonitorSettings() }
        // Six background windows of 75 s, 15 minutes apart, each irregular.
        repeat(6) { i ->
            heart.irn.resetWindow()
            val start = i * 15 * 60_000L
            val samples = SyntheticHr.samples(start, 75, bpm = 92.0, irregularity = 0.35, seed = 9 + i)
            heart.onIrnWindow(samples, BackgroundWindow.rhythm(samples).samples)
        }
        assertEquals(listOf(AlertKind.IRREGULAR_RHYTHM), out.alerts.map { it.kind })
    }
}
