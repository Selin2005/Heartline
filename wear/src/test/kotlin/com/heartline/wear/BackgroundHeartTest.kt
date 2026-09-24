package com.heartline.wear

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
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
        assertEquals(5, out.batches.single().minutes.size)
    }

    @Test
    fun shortWindowsEveryQuarterHourDetectIrregularRhythm() = runBlocking {
        val out = Recorder()
        val heart = BackgroundHeart(out) { MonitorSettings() }
        // Six background windows of 75 s, 15 minutes apart, each irregular.
        repeat(6) { i ->
            heart.irn.resetWindow()
            val start = i * 15 * 60_000L
            SyntheticHr.samples(start, 75, bpm = 92.0, irregularity = 0.35, seed = 9 + i).forEach { heart.irn.onSample(it) }
        }
        assertEquals(listOf(AlertKind.IRREGULAR_RHYTHM), out.alerts.map { it.kind })
        // The rhythm monitor never sends minutes (the passive stream does), so nothing is counted twice.
        assertTrue(out.batches.isEmpty())
    }
}
