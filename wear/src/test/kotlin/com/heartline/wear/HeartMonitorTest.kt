package com.heartline.wear

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.irn.IrnState
import com.heartline.shared.sample.SyntheticHr
import com.heartline.wear.monitor.HeartMonitor
import com.heartline.wear.monitor.MonitorOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartMonitorTest {
    private class Recorder : MonitorOutput {
        var irn = IrnState()
        val batches = mutableListOf<HrBatch>()
        val alerts = mutableListOf<HealthAlert>()
        val notified = mutableListOf<HealthAlert>()

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

        override fun notify(alert: HealthAlert) {
            notified += alert
        }
    }

    private var n = 0

    @Test
    fun batchesMinutesEveryQuarterHour() = runBlocking {
        val out = Recorder()
        val monitor = HeartMonitor(out, { false }, { MonitorSettings() }, newId = { "id${n++}" })
        SyntheticHr.samples(0, 31 * 60, bpm = 62.0).forEach { monitor.onSample(it) }
        assertEquals(2, out.batches.size)
        assertEquals(15, out.batches.first().minutes.size)
        assertTrue(out.alerts.isEmpty())
    }

    @Test
    fun irregularRhythmRaisesNotification() = runBlocking {
        val out = Recorder()
        val monitor = HeartMonitor(out, { false }, { MonitorSettings() }, newId = { "id${n++}" })
        // 2 hours of irregular rhythm → windows every 15 min → 5 of 6 irregular.
        SyntheticHr.samples(0, 2 * 3600, bpm = 92.0, irregularity = 0.35, seed = 9).forEach { monitor.onSample(it) }
        assertEquals(listOf(AlertKind.IRREGULAR_RHYTHM), out.alerts.map { it.kind })
        assertEquals(out.alerts, out.notified)
    }

    @Test
    fun movementSuppressesRhythmChecks() = runBlocking {
        val out = Recorder()
        val monitor = HeartMonitor(out, { true }, { MonitorSettings() }, newId = { "id${n++}" })
        SyntheticHr.samples(0, 2 * 3600, bpm = 92.0, irregularity = 0.35, seed = 9).forEach { monitor.onSample(it) }
        assertTrue(out.alerts.isEmpty())
        assertTrue(out.irn.windows.isEmpty())
    }

    @Test
    fun disabledSettingSkipsRhythmChecks() = runBlocking {
        val out = Recorder()
        val monitor = HeartMonitor(out, { false }, { MonitorSettings(irregularRhythmEnabled = false) }, newId = { "id${n++}" })
        SyntheticHr.samples(0, 2 * 3600, bpm = 92.0, irregularity = 0.35, seed = 9).forEach { monitor.onSample(it) }
        assertTrue(out.alerts.none { it.kind == AlertKind.IRREGULAR_RHYTHM })
    }
}
