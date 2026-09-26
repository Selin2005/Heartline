// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sample.SyntheticEcg
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.FakeSensorGateway
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.LauncherState
import com.heartline.wear.ui.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WatchStoreTest {
    private lateinit var db: WatchDatabase
    private lateinit var store: WatchRecordStore

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        // viewModelScope uses Dispatchers.Main, whose looper is blocked by runBlocking here.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = WatchDatabase.inMemory(context)
        store = WatchRecordStore(db.records(), File(context.cacheDir, "store-test").apply { deleteRecursively() })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun ecg(id: String, at: Long): Pair<RecordMeta, FloatArray> {
        val wave = SyntheticEcg.generate(30.0)
        return RecordMeta(id, RecordKind.ECG, at, 30_000, 500, wave.size, RecordSummary.Ecg(71, EcgResult.SINUS_RHYTHM, 0f)) to wave
    }

    @Test
    fun outboxKeepsUntilDeliveredThenKeepsHistoryWithoutWave() = runBlocking {
        val (meta, wave) = ecg("a", 1)
        store.add(meta, wave)
        val pending = store.pending().single()
        assertEquals(wave.size, pending.wave!!.size)

        store.markDelivered("a")
        assertTrue(store.pending().isEmpty())
        assertEquals(listOf("a"), store.recent.first().map { it.id })
        assertNull(db.records().get("a")!!.wavePath)
    }

    @Test
    fun launcherShowsOnlySupportedMetricsWithLastValues() = runBlocking {
        val (meta, wave) = ecg("b", 2)
        store.add(meta, wave)
        val gateway = FakeSensorGateway(setOf(TrackerKind.ECG_ON_DEMAND, TrackerKind.HEART_RATE_CONTINUOUS))
        val vm = LauncherViewModel(gateway, store)
        vm.connect()
        val ready = withTimeout(5_000) { vm.state.first { it is LauncherState.Ready } } as LauncherState.Ready
        assertEquals(listOf(Metric.ECG, Metric.HEART_RATE, Metric.STRESS), ready.entries.map { it.metric })
        assertEquals("71 bpm", ready.entries.first().lastValue)
    }

    @Test
    fun launcherReportsGatewayFailure() = runBlocking {
        val vm = LauncherViewModel(FakeSensorGateway(failure = SensorProblem.SDK_POLICY), store)
        vm.connect()
        val state = withTimeout(5_000) { vm.state.first { it is LauncherState.Problem } } as LauncherState.Problem
        assertEquals(SensorProblem.SDK_POLICY, state.problem)
    }
}
