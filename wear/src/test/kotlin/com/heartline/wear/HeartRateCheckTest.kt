// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.hr.HrSample
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.remote.HeartRateCheckState
import com.heartline.wear.remote.HeartRateCheckViewModel
import com.heartline.wear.sensor.HrSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HeartRateCheckTest {
    private lateinit var db: WatchDatabase
    private lateinit var store: WatchRecordStore
    private var clock = 0L
    private var scheduled = 0

    /** One reading per simulated second; the clock moves with it. */
    private class Readings(private val bpm: (Int) -> Int, private val onBody: (Int) -> Boolean, private val tick: () -> Unit) : HrSource {
        override fun stream(): Flow<HrSample> = flow {
            var i = 0
            while (true) {
                tick()
                emit(HrSample(i * 1_000L, bpm(i), emptyList(), onBody = onBody(i)))
                i++
            }
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = WatchDatabase.inMemory(context)
        store = WatchRecordStore(db.records(), File(context.cacheDir, "hr-check-test").apply { deleteRecursively() }, db.messages())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test fun thirtyGoodSecondsMakeARecord() = runBlocking {
        val vm = HeartRateCheckViewModel(Readings({ 60 + it % 7 }, { it !in 5..9 }) { clock += 1_000 }, store, { scheduled++ }) { clock }
        vm.start()
        val done = withTimeout(10_000) { vm.state.first { it is HeartRateCheckState.Done } } as HeartRateCheckState.Done
        assertEquals(63, done.summary.bpm)
        assertEquals(60, done.summary.minBpm)
        assertEquals(66, done.summary.maxBpm)
        val meta = store.pending().single().meta
        assertEquals(RecordKind.HEART_RATE, meta.kind)
        assertEquals(done.recordId, meta.id)
        assertEquals(done.summary, meta.summary as RecordSummary.HeartRate)
        assertEquals(1, scheduled)
    }

    @Test fun readingsHandedOverTogetherCountByTheirOwnTime() = runBlocking {
        // Three readings a second apart arrive together every three seconds.
        val batched = object : HrSource {
            override fun stream(): Flow<HrSample> = flow {
                var i = 0
                while (true) {
                    clock += 3_000
                    repeat(3) {
                        emit(HrSample(i * 1_000L, 70, emptyList()))
                        i++
                    }
                }
            }
        }
        val vm = HeartRateCheckViewModel(batched, store, { scheduled++ }) { clock }
        vm.start()
        val done = withTimeout(10_000) { vm.state.first { it is HeartRateCheckState.Done } } as HeartRateCheckState.Done
        assertEquals(70, done.summary.bpm)
        // 30 s of readings, not stretched by the batching.
        assertEquals(30, done.summary.samples)
    }

    @Test fun offTheWristItGivesUp() = runBlocking {
        val vm = HeartRateCheckViewModel(Readings({ 70 }, { false }) { clock += 1_000 }, store, { scheduled++ }) { clock }
        vm.start()
        withTimeout(10_000) { vm.state.first { it is HeartRateCheckState.TooFewReadings } }
        assertEquals(0, store.pending().size)
    }

    /** On the wrist but no reading good enough (the sensor's status -10): asked to keep still. */
    @Test fun unreliableReadingsAskToKeepStill() = runBlocking {
        var vm: HeartRateCheckViewModel? = null
        var seen: HeartRateCheckState? = null
        val weak = object : HrSource {
            override fun stream(): Flow<HrSample> = flow {
                for (i in 0 until 8) {
                    clock += 1_000
                    emit(HrSample(i * 1_000L, 70, emptyList(), reliable = false))
                    // What the screen and the phone see after six seconds of it.
                    if (i == 6) seen = vm?.state?.value
                }
            }
        }
        vm = HeartRateCheckViewModel(weak, store, { scheduled++ }) { clock }
        vm.start()
        withTimeout(10_000) { vm.state.first { it is HeartRateCheckState.TooFewReadings } }
        val measuring = seen as HeartRateCheckState.Measuring
        assertEquals(true, measuring.weak)
        assertEquals(false, measuring.offWrist)
        assertEquals(0f, measuring.progress)
    }
}
