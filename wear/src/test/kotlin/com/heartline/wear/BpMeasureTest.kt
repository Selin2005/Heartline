package com.heartline.wear

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.Protocol
import com.heartline.wear.bp.BpMeasureViewModel
import com.heartline.wear.bp.BpState
import com.heartline.wear.bp.WatchBpStore
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.FakePpgSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BpMeasureTest {
    private lateinit var db: WatchDatabase
    private lateinit var records: WatchRecordStore
    private lateinit var bp: WatchBpStore
    private var scheduled = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("bp", 0).edit().clear().commit()
        db = WatchDatabase.inMemory(context)
        records = WatchRecordStore(db.records(), File(context.cacheDir, "bp-test").apply { deleteRecursively() }, db.messages())
        bp = WatchBpStore(context)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun vm() = BpMeasureViewModel(FakePpgSource(chunkDelayMs = 0), bp, records, { scheduled++ }, uiIntervalMs = 0)

    @Test
    fun withoutCalibrationAsksToCalibrate() {
        val vm = vm()
        vm.start()
        assertEquals(BpState.NeedsCalibration, vm.state.value)
    }

    @Test
    fun calibrationRoundSendsFeaturesToPhone() = runBlocking {
        bp.setPendingCapture(CaptureRequest("cap-1", 2))
        val vm = vm()
        vm.start()
        val done = withTimeout(10_000) { vm.state.first { it is BpState.CalibrationRecorded } } as BpState.CalibrationRecorded
        assertEquals(2, done.round)
        val message = records.pendingMessages().single()
        assertEquals(Protocol.BP_CALIBRATION_CAPTURE, message.path)
        assertTrue(message.payload.decodeToString().contains("cap-1"))
        assertNull(bp.pendingCapture.value)
        assertEquals(1, scheduled)
    }

    @Test
    fun calibratedMeasurementIsStoredAndSynced() = runBlocking {
        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.5), 100)!!
        bp.setCalibration(BpCalibration("c", System.currentTimeMillis(), List(3) { CalibrationPoint(features, 122, 80, 68) }))
        val vm = vm()
        vm.start()
        val end = withTimeout(10_000) { vm.state.first { it !is BpState.Measuring && it !is BpState.Idle } }
        val done = end as? BpState.Done ?: error("ended with $end")
        assertTrue("$done", done.systolic in 110..135 && done.diastolic in 70..90)
        val summary = records.pending().single().meta.summary as RecordSummary.BloodPressure
        assertEquals(done.systolic, summary.systolic)
        assertEquals(1, scheduled)
    }
}
