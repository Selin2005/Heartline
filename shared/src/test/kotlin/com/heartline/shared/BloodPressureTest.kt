package com.heartline.shared

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.CaptureResult
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PendingMessage
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.WatchSyncEngine
import kotlin.math.abs
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BloodPressureTest {
    private val fs = SyntheticPpg.SAMPLE_RATE_HZ

    private fun features(hr: Double, stiffness: Double, seed: Int = 1) =
        PpgFeatures.extract(SyntheticPpg.generate(20.0, hr, stiffness, seed = seed), fs)!!

    private fun calibration(at: Long = 0) = BpCalibration(
        "c1",
        at,
        listOf(
            CalibrationPoint(features(68.0, 0.45, 1), 121, 79, 68),
            CalibrationPoint(features(70.0, 0.5, 2), 124, 81, 70),
            CalibrationPoint(features(66.0, 0.5, 3), 119, 78, 66)
        )
    )

    @Test
    fun featuresTrackHeartRateAndStiffness() {
        val soft = features(60.0, 0.1)
        val stiff = features(60.0, 0.9)
        assertEquals(60.0, soft.heartRateBpm, 2.0)
        assertTrue(stiff.riseFraction < soft.riseFraction)
        assertTrue(stiff.areaRatio > soft.areaRatio)
        assertTrue(soft.quality > 0.5 && stiff.quality > 0.5)
    }

    @Test
    fun rejectsShortOrFlatSignals() {
        assertNull(PpgFeatures.extract(FloatArray(300), fs))
        assertNull(PpgFeatures.extract(FloatArray(2000), fs))
    }

    @Test
    fun sameStateReproducesCalibrationMean() {
        val cal = calibration()
        val out = BpEstimator.estimate(cal, features(68.0, 0.48, 7), 1_000) as BpOutcome.Ok
        assertTrue("${out.estimate}", abs(out.estimate.systolic - 121) <= 5)
        assertTrue("${out.estimate}", abs(out.estimate.diastolic - 79) <= 4)
    }

    @Test
    fun stifferPulseEstimatesHigherPressureWithinClamp() {
        val cal = calibration()
        val base = (BpEstimator.estimate(cal, features(68.0, 0.48, 7), 1_000) as BpOutcome.Ok).estimate
        val stiff = (BpEstimator.estimate(cal, features(80.0, 0.95, 8), 1_000) as BpOutcome.Ok).estimate
        assertTrue("base=$base stiff=$stiff", stiff.systolic > base.systolic)
        assertTrue(stiff.systolic <= 121 + 25)
    }

    @Test
    fun calibrationExpiresAfter28Days() {
        val cal = calibration(at = 0)
        assertEquals(28, cal.daysLeft(0))
        assertEquals(BpOutcome.NeedsCalibration, BpEstimator.estimate(cal, features(68.0, 0.5), BpCalibration.VALIDITY_MS + 1))
        assertEquals(BpOutcome.NeedsCalibration, BpEstimator.estimate(null, features(68.0, 0.5), 0))
        assertNotNull(cal)
    }

    @Test
    fun noisySignalIsRejected() {
        val noisy = PpgFeatures.extract(SyntheticPpg.generate(20.0, 70.0, 0.5, noise = 1.5, seed = 4), fs)
        assertEquals(BpOutcome.PoorSignal, BpEstimator.estimate(calibration(), noisy, 1_000))
    }

    @Test
    fun ahaCategories() {
        assertEquals(BpCategory.NORMAL, BpCategory.of(115, 75))
        assertEquals(BpCategory.ELEVATED, BpCategory.of(125, 78))
        assertEquals(BpCategory.HIGH_STAGE_1, BpCategory.of(118, 84))
        assertEquals(BpCategory.HIGH_STAGE_2, BpCategory.of(145, 85))
        assertEquals(BpCategory.CRISIS, BpCategory.of(185, 100))
    }
}

class CalibrationSyncTest {
    @Test
    fun captureRequestAndResultRoundTrip() = runBlocking {
        val (watchSide, phoneSide) = InMemoryTransport.pair()
        val requests = mutableListOf<CaptureRequest>()
        val results = mutableListOf<CaptureResult>()
        var calibration: BpCalibration? = null
        val queue = mutableListOf<PendingMessage>()
        val outbox = object : Outbox {
            override suspend fun pending() = emptyList<OutboxItem>()

            override suspend fun pendingMessages() = queue.toList()

            override suspend fun markDelivered(id: String) {
                queue.removeAll { it.id == id }
            }
        }
        val sink = object : RecordSink {
            override suspend fun contains(id: String) = false

            override suspend fun save(meta: RecordMeta, wave: FloatArray?) = Unit

            override suspend fun delete(id: String) = Unit
        }
        val watch = WatchSyncEngine(watchSide, outbox, onCalibration = { calibration = it }, onCaptureRequest = { requests += it })
        val phone = PhoneSyncEngine(phoneSide, sink, onCaptureResult = { results += it })
        val jobs = listOf(
            phoneSide.incoming.onEach { phone.handle(it) }.launchIn(this),
            watchSide.incoming.onEach { watch.handle(it) }.launchIn(this)
        )
        yield()

        phone.requestCapture(CaptureRequest("cap", 2))
        repeat(10) { yield() }
        assertEquals(2, requests.single().round)

        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0), 100)!!
        val result = CaptureResult("r1", "cap", 2, features)
        queue += PendingMessage("r1", Protocol.BP_CALIBRATION_CAPTURE, Protocol.json.encodeToString(result).encodeToByteArray())
        watch.flush()
        repeat(10) { yield() }
        assertEquals(result, results.single())
        assertTrue(queue.isEmpty())

        val cal = BpCalibration("c", 0, List(3) { CalibrationPoint(features, 120, 80, 70) })
        phone.sendCalibration(cal)
        repeat(10) { yield() }
        assertEquals(cal, calibration)
        phone.sendCalibration(null)
        repeat(10) { yield() }
        assertNull(calibration)
        jobs.forEach { it.cancel() }
    }
}
