// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.bp.BpSessionStreams
import com.heartline.shared.bp.HemodynamicState
import com.heartline.shared.bp.ImuStreams
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
import com.heartline.wear.sensor.EcgChunk
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.ImuRecorder
import com.heartline.wear.sensor.PpgChunk
import com.heartline.wear.sensor.PpgSource
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

    private fun vm(source: PpgSource = FakePpgSource(chunkDelayMs = 0), imu: ImuRecorder = ImuRecorder.NONE) =
        BpMeasureViewModel(source, bp, records, { scheduled++ }, uiIntervalMs = 0, imu = imu)

    private fun calibrate() {
        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.5), 100)!!
        bp.setCalibration(BpCalibration("c", System.currentTimeMillis(), List(3) { CalibrationPoint(features, 122, 80, 68) }))
    }

    private suspend fun BpMeasureViewModel.measure(): BpState {
        start()
        return withTimeout(BP_TIMEOUT_MS) { state.first { it !is BpState.Measuring && it !is BpState.Idle } }
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
        val done = withTimeout(BP_TIMEOUT_MS) { vm.state.first { it is BpState.CalibrationRecorded } } as BpState.CalibrationRecorded
        assertEquals(2, done.round)
        val message = records.pendingMessages().single()
        assertEquals(Protocol.BP_CALIBRATION_CAPTURE, message.path)
        assertTrue(message.payload.decodeToString().contains("cap-1"))
        assertNull(bp.pendingCapture.value)
        assertTrue(scheduled >= 1)
        // The round's raw session log waits for the phone too.
        assertEquals(1, records.pendingSessions().size)
    }

    @Test
    fun calibratedMeasurementIsStoredAndSynced() = runBlocking {
        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.5), 100)!!
        bp.setCalibration(BpCalibration("c", System.currentTimeMillis(), List(3) { CalibrationPoint(features, 122, 80, 68) }))
        val vm = vm()
        vm.start()
        val end = withTimeout(BP_TIMEOUT_MS) { vm.state.first { it !is BpState.Measuring && it !is BpState.Idle } }
        val done = end as? BpState.Done ?: error("ended with $end")
        assertTrue("$done", done.systolic in 110..135 && done.diastolic in 70..90)
        val pending = records.pending().single()
        val summary = pending.meta.summary as RecordSummary.BloodPressure
        assertEquals(done.systolic, summary.systolic)
        assertEquals(6, summary.algorithm)
        assertEquals("PWA_GREEN", summary.channels)
        // The raw pulse wave is kept for the phone.
        assertEquals(100, pending.meta.sampleRateHz)
        assertEquals(2000, pending.meta.sampleCount)
        assertEquals(2000, pending.wave?.size)
        assertTrue(scheduled >= 1)
        // Every sample and value of the session is logged for the phone.
        val (id, bytes) = records.pendingSessions().single()
        val log = BpSessionLog.decode(bytes)
        assertEquals(summary.sessionId, id)
        assertEquals(pending.meta.id, log.header.recordId)
        assertTrue(log.stream(BpSessionStreams.PPG)!!.size >= 2000)
        assertTrue(log.header.values.containsKey("fusion.systolic"))
        assertEquals(6, log.header.algorithm)
        assertEquals(1, bp.history.size)
    }

    @Test
    fun aVeryDifferentPulseIsShownFlaggedAndConfirmedBySecondReading() = runBlocking {
        calibrate()
        val vm = vm(FakePpgSource(heartRateBpm = 110.0, stiffness = 0.95, chunkDelayMs = 0))
        val first = vm.measure() as? BpState.Done ?: error("refused: ${vm.state.value}")
        assertTrue("$first", first.beyondCalibration && first.needsConfirming && !first.confirmed)
        // Algorithms 5–6: a faster pulse no longer drives the number (bounded, never the old +20),
        // but the reading is still flagged.
        assertTrue("$first", kotlin.math.abs(first.systolic - 122) <= 10)
        vm.reset()
        val second = vm.measure() as BpState.Done
        assertTrue("$second", second.confirmed && !second.needsConfirming)
        val summaries = records.pending().map { it.meta.summary as RecordSummary.BloodPressure }
        assertTrue(summaries.all { it.beyondCalibration })
        assertEquals(1, summaries.count { it.confirmed })
        // Extrapolated readings don't teach the "normal spread".
        assertTrue(bp.history.isEmpty())
    }

    /** Raw watch-style PPG of one scenario, in small chunks. */
    private class ScenarioSource(private val scenario: SyntheticPpg.Scenario) : PpgSource {
        override fun stream(): Flow<PpgChunk> = flow {
            val signal = SyntheticPpg.scenario(scenario)
            for (offset in 0 until signal.size - 5 step 5) emit(PpgChunk(signal.copyOfRange(offset, offset + 5), contact = true))
        }
    }

    @Test
    fun racingWeakPulseStillGivesANumberWithoutReadingHigh() = runBlocking {
        val points = (1..3).map {
            val features = PpgFeatures.extract(SyntheticPpg.scenario(SyntheticPpg.Scenario(heartRateStart = 70.0, stiffness = 0.3, seed = it)), 100)!!
            CalibrationPoint(features, 104, 70, 70)
        }
        bp.setCalibration(BpCalibration("c", System.currentTimeMillis(), points))
        val episode = SyntheticPpg.Scenario(
            seconds = 25.0,
            heartRateStart = 125.0,
            heartRateEnd = 110.0,
            stiffness = 0.55,
            amplitudeStart = 0.8,
            amplitudeEnd = 1.2,
            perfusionIndex = 0.45,
        )
        val end = vm(ScenarioSource(episode)).measure() as? BpState.Done ?: error("no number")
        assertEquals(HemodynamicState.COMPENSATORY, end.bodyState)
        assertTrue("$end", end.systolic <= 115)
    }

    @Test
    fun anUnsteadyCalibrationRoundIsTakenAgain() = runBlocking {
        bp.setPendingCapture(CaptureRequest("cap-2", 1))
        // The pulse keeps climbing through the whole recording (as in a real round 2 that skewed a calibration).
        val climbing = SyntheticPpg.Scenario(seconds = 25.0, heartRateStart = 66.0, heartRateEnd = 96.0)
        val end = vm(ScenarioSource(climbing)).measure()
        assertEquals(BpState.CalibrationRetry(1), end)
        // Nothing was sent; the phone keeps waiting for this round.
        assertTrue(records.pendingMessages().isEmpty())
        assertEquals("cap-2", bp.pendingCapture.value?.captureId)
    }

    @Test
    fun movingArmMeansMeasureAgain() = runBlocking {
        calibrate()
        val moving = object : ImuRecorder {
            override fun start() = Unit

            override fun stop() = 2.0

            override fun streams() = ImuStreams.EMPTY
        }
        assertEquals(BpState.Moving, vm(imu = moving).measure())
        assertTrue(records.pending().isEmpty())
        // The failed session is still logged.
        assertEquals(1, records.pendingSessions().size)
    }

    @Test
    fun preciseModeFusesEcgTransitTimesWithTheMotionSensor() = runBlocking {
        val spec = com.heartline.shared.sample.SyntheticSession.Spec(
            systolic = 104.0,
            diastolic = 70.0,
            refSystolic = 104.0,
            stiffness = 0.3,
            precise = true,
            seconds = 36.0,
        )
        // Quick rounds (every calibration) plus precise rounds (precise mode compares its ECG-channel PPG only with those).
        val points = listOf(false, true).flatMap { precise ->
            (1..3).map {
                val s = com.heartline.shared.sample.SyntheticSession.generate(spec.copy(heartRateStart = 68.0 + it, seed = it, precise = precise))
                CalibrationPoint.of(com.heartline.shared.bp.BpPipeline.capture(s.input)!!, 103 + it, 70, 70)
            }
        }
        bp.setCalibration(BpCalibration("c", System.currentTimeMillis(), points))
        val session = com.heartline.shared.sample.SyntheticSession.generate(spec.copy(seed = 9))
        val precise = session.input.precise!!
        val ecg = object : EcgSource {
            override fun stream(): Flow<EcgChunk> = flow {
                val n = 50
                for (offset in 0 until precise.ecg.size - n step n) {
                    val lastMs = (precise.startNs + ((offset + n - 1) * 1e9 / precise.fs).toLong()) / 1_000_000L
                    emit(EcgChunk(precise.ecg.copyOfRange(offset, offset + n), leadOff = false, timestampMs = lastMs, ppg = precise.ppg.copyOfRange(offset, offset + n)))
                }
            }
        }
        val imu = object : ImuRecorder {
            override fun start() = Unit

            override fun stop() = 0.05

            override fun streams() = session.input.imu
        }
        val vm = BpMeasureViewModel(FakePpgSource(chunkDelayMs = 0), bp, records, { scheduled++ }, uiIntervalMs = 0, imu = imu, ecg = ecg)
        vm.start(com.heartline.wear.bp.BpMode.PRECISE)
        val done = withTimeout(BP_TIMEOUT_MS) { vm.state.first { it !is BpState.Measuring && it !is BpState.Idle && it !is BpState.Preparing } } as? BpState.Done
            ?: error("ended with ${vm.state.value}")
        assertTrue("${done.channels}", com.heartline.shared.bp.BpChannel.ECG_PTT in done.channels)
        assertTrue("$done", kotlin.math.abs(done.systolic - 104) <= 8)
        // The raw ECG is in the session log.
        val log = BpSessionLog.decode(records.pendingSessions().single().second)
        assertEquals("precise", log.header.mode)
        assertTrue(log.stream(BpSessionStreams.ECG)!!.size >= 34 * 500)
    }
}

/** A measurement runs the full PPG pipeline; a loaded build machine (other test forks) needs headroom. */
private const val BP_TIMEOUT_MS = 30_000L
