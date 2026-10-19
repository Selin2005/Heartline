// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.irn.IrnState
import com.heartline.wear.monitor.BackgroundMonitoring
import com.heartline.wear.monitor.BackgroundSensors
import com.heartline.wear.monitor.MonitorOutput
import com.heartline.wear.monitor.StandProbeWorker
import com.heartline.wear.monitor.WatchNotifier
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.remote.RemoteMeasureCoordinator
import com.heartline.wear.sensor.FakeHrSource
import com.heartline.wear.sensor.HrSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class StandProbeWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val batches = mutableListOf<HrBatch>()
    private lateinit var store: WatchSettingsStore

    /** The tracker at about 1 Hz by its own clock, handed over at once (as a batch would be). */
    private var clock = 0L
    private val fake = FakeHrSource(bpm = 104.0, periodMs = 1, now = { clock.also { clock += 1_000 } })

    /** Each slice starts listening minutes after the last one, as on the watch. */
    private val source = object : HrSource {
        override fun stream(): kotlinx.coroutines.flow.Flow<com.heartline.shared.hr.HrSample> {
            clock += 120_000
            return fake.stream()
        }
    }

    private val output = object : MonitorOutput {
        override suspend fun loadIrnState() = IrnState()

        override suspend fun saveIrnState(state: IrnState) = Unit

        override suspend fun enqueueBatch(batch: HrBatch) {
            batches += batch
        }

        override suspend fun enqueueAlert(alert: HealthAlert) = Unit

        override fun notify(alert: HealthAlert) = Unit
    }

    @Before
    fun setUp() {
        val sensors = com.heartline.shared.sensor.PermissionPolicy.permissionsFor(com.heartline.shared.model.Metric.HEART_RATE, android.os.Build.VERSION.SDK_INT)
        shadowOf(context as Application).grantPermissions(*(sensors + BackgroundMonitoring.backgroundPermission).toTypedArray())
        store = WatchSettingsStore(context)
        val coordinator = RemoteMeasureCoordinator({ true }, {}, CoroutineScope(Dispatchers.Unconfined))
        startKoin {
            modules(
                module {
                    single<HrSource> { source }
                    single { store }
                    single<MonitorOutput> { output }
                    single { WatchNotifier(context) }
                    single { coordinator }
                },
            )
        }
    }

    @After
    fun tearDown() = stopKoin()

    private fun worker(startMs: Long = System.currentTimeMillis()) = TestListenableWorkerBuilder<StandProbeWorker>(context)
        .setInputData(workDataOf(StandProbeWorker.START_MS to startMs, StandProbeWorker.SLICES_MS to longArrayOf(0, 200, 400)))
        .build()

    @Test
    fun aProbeMeasuresThreeSlicesAndSendsThem() = runBlocking {
        clock = System.currentTimeMillis()
        val start = System.currentTimeMillis()
        assertEquals(ListenableWorker.Result.success(), worker(start).doWork())
        val probe = batches.single().stands.single()
        assertEquals(start, probe.startMs)
        assertTrue(batches.single().minutes.isEmpty())
        // Three slices of about 40 s each, by the readings' own time.
        assertEquals(3, probe.sliceMedians().size)
        assertTrue(probe.sliceMedians().all { it.bpm in 95..115 })
        assertEquals(listOf(start), store.standProbes)
        assertTrue(!StandProbeWorker.running && !BackgroundSensors.lock.isLocked)
    }

    @Test
    fun noProbeWhenTheCheckIsOffOrTheSensorsAreBusy() = runBlocking {
        store.offer(MonitorSettings(updatedAtMs = 1).withMonitoring(false))
        worker().doWork()
        store.offer(MonitorSettings(updatedAtMs = 2))
        BackgroundSensors.lock.lock()
        try {
            worker().doWork()
        } finally {
            BackgroundSensors.lock.unlock()
        }
        assertTrue(batches.isEmpty())
        assertTrue(store.standProbes.isEmpty())
    }

    @Test
    fun aProbeThatStartsTooLateIsSkipped() = runBlocking {
        worker(startMs = System.currentTimeMillis() - 10 * 60_000L).doWork()
        assertTrue(batches.isEmpty())
    }
}
