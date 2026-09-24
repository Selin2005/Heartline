package com.heartline.wear

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordSummary
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.ecg.EcgMeasureState
import com.heartline.wear.ecg.EcgMeasureViewModel
import com.heartline.wear.sensor.EcgChunk
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.FakeEcgSource
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EcgMeasureTest {
    private lateinit var db: WatchDatabase
    private lateinit var store: WatchRecordStore
    private var scheduled = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = WatchDatabase.inMemory(context)
        store = WatchRecordStore(db.records(), File(context.cacheDir, "ecg-test").apply { deleteRecursively() })
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun vm(source: EcgSource) = EcgMeasureViewModel(source, store, { scheduled++ }, uiIntervalMs = 0)

    @Test
    fun fullRecordingIsAnalysedStoredAndScheduled() = runBlocking {
        val vm = vm(FakeEcgSource(heartRateBpm = 130.0, chunkDelayMs = 0, leadOffChunks = 20))
        vm.start()
        val done = withTimeout(20_000) { vm.state.first { it is EcgMeasureState.Done } } as EcgMeasureState.Done

        assertEquals(EcgResult.HIGH_HEART_RATE, done.result)
        assertTrue("bpm=${done.averageBpm}", done.averageBpm in 126..134)
        val item = store.pending().single()
        assertEquals(15_000, item.wave!!.size)
        val summary = item.meta.summary as RecordSummary.Ecg
        assertTrue(summary.leadOffRatio > 0f)
        assertEquals(1, scheduled)
    }

    @Test
    fun sdkErrorBecomesFailedState() = runBlocking {
        val failing = object : EcgSource {
            override fun stream(): Flow<EcgChunk> = flow { throw SensorException(SensorProblem.SDK_POLICY) }
        }
        val vm = vm(failing)
        vm.start()
        val failed = withTimeout(5_000) { vm.state.first { it is EcgMeasureState.Failed } } as EcgMeasureState.Failed
        assertEquals(SensorProblem.SDK_POLICY, failed.problem)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun abandonedRecordingIsNotStored() = runBlocking {
        val allLeadOff = object : EcgSource {
            override fun stream(): Flow<EcgChunk> = flow { repeat(600) { emit(EcgChunk(FloatArray(10), leadOff = true)) } }
        }
        val vm = vm(allLeadOff)
        vm.start()
        val done = withTimeout(5_000) { vm.state.first { it is EcgMeasureState.Done } } as EcgMeasureState.Done
        assertEquals(EcgResult.POOR_RECORDING, done.result)
        assertTrue(store.pending().isEmpty())
        assertEquals(0, scheduled)
    }
}
