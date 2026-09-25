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
import com.heartline.wear.sensor.MotionMeter
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

    private fun vm(source: FakePpgSource = FakePpgSource(chunkDelayMs = 0), motion: MotionMeter = MotionMeter.NONE) =
        BpMeasureViewModel(source, bp, records, { scheduled++ }, uiIntervalMs = 0, motion = motion)

    private fun calibrate() {
        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.5), 100)!!
        bp.setCalibration(BpCalibration("c", System.currentTimeMillis(), List(3) { CalibrationPoint(features, 122, 80, 68) }))
    }

    private suspend fun BpMeasureViewModel.measure(): BpState {
        start()
        return withTimeout(10_000) { state.first { it !is BpState.Measuring && it !is BpState.Idle } }
    }

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
        val pending = records.pending().single()
        val summary = pending.meta.summary as RecordSummary.BloodPressure
        assertEquals(done.systolic, summary.systolic)
        assertEquals(3, summary.algorithm)
        // The raw pulse wave is kept for the phone.
        assertEquals(100, pending.meta.sampleRateHz)
        assertEquals(2000, pending.meta.sampleCount)
        assertEquals(2000, pending.wave?.size)
        assertEquals(1, scheduled)
        assertEquals(1, bp.history.size)
    }

    @Test
    fun aVeryDifferentPulseIsShownFlaggedAndConfirmedBySecondReading() = runBlocking {
        calibrate()
        val vm = vm(FakePpgSource(heartRateBpm = 110.0, stiffness = 0.95, chunkDelayMs = 0))
        val first = vm.measure() as? BpState.Done ?: error("refused: ${vm.state.value}")
        assertTrue("$first", first.beyondCalibration && first.needsConfirming && !first.confirmed)
        assertTrue("$first", first.systolic > 130)
        vm.reset()
        val second = vm.measure() as BpState.Done
        assertTrue("$second", second.confirmed && !second.needsConfirming)
        val summaries = records.pending().map { it.meta.summary as RecordSummary.BloodPressure }
        assertTrue(summaries.all { it.beyondCalibration })
        assertEquals(1, summaries.count { it.confirmed })
        // Extrapolated readings don't teach the "normal spread".
        assertTrue(bp.history.isEmpty())
    }

    @Test
    fun movingArmMeansMeasureAgain() = runBlocking {
        calibrate()
        val moving = object : MotionMeter {
            override fun start() = Unit

            override fun stop() = 2.0
        }
        assertEquals(BpState.Moving, vm(motion = moving).measure())
        assertTrue(records.pending().isEmpty())
    }
}
