// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpChannel
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.BpPipeline
import com.heartline.shared.bp.BpSessionHeader
import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.bp.BpSessionRecorder
import com.heartline.shared.bp.BpSessionReplay
import com.heartline.shared.bp.BpSessionStreams
import com.heartline.shared.bp.BpTuning
import com.heartline.shared.bp.BpWindowSelector
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.HemodynamicState
import com.heartline.shared.bp.HydrostaticCalibration
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.bp.TransitTimes
import com.heartline.shared.bp.WristBcg
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sample.SyntheticSession
import com.heartline.shared.sample.SyntheticSession.Spec
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.SyncTransport
import com.heartline.shared.sync.WatchSyncEngine
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Algorithm 6: every sensor, one estimate per channel, one fused number. Synthetic sessions
 * carry a known pressure, and every channel is generated from the same beats.
 */
class BpAlgorithm6Test {
    /** The user from the report: 104/70 at a resting pulse of about 70. */
    private val base = Spec(systolic = 104.0, diastolic = 70.0, refSystolic = 104.0, stiffness = 0.3, irStiffness = 0.3)

    private fun rounds(precise: Boolean) = (1..3).map { i ->
        val s = SyntheticSession.generate(base.copy(heartRateStart = 68.0 + i, precise = precise, seed = i))
        CalibrationPoint.of(BpPipeline.capture(s.input)!!, 104 + i - 2, 70, 70)
    }

    /**
     * Quick rounds always (the calibration every quick measurement uses); for precise mode also
     * precise rounds, since its ECG-channel PPG must only be compared with rounds of the same source.
     */
    private fun calibration(precise: Boolean = false): BpCalibration =
        BpCalibration("c", 0, rounds(precise = false) + if (precise) rounds(precise = true) else emptyList())

    private fun run(spec: Spec, cal: BpCalibration = calibration(spec.precise)) =
        BpPipeline.run(cal, SyntheticSession.generate(spec).input, 1_000)

    private fun ok(out: BpOutcome) = (out as? BpOutcome.Ok)?.estimate ?: error("no number: $out")

    /** The bathroom episode: pressure 92/60 while the pulse races and the wrist vessels constrict. */
    private val episode = base.copy(
        systolic = 92.0,
        diastolic = 60.0,
        heartRateStart = 125.0,
        heartRateEnd = 110.0,
        stiffness = 0.55,
        irStiffness = 0.35,
        perfusionIndex = 0.45,
        irPerfusionIndex = 0.5,
        pepMs = 70.0,
        seed = 50
    )

    @Test
    fun wristBallistocardiogramRecoversTheTransitTimeChange() {
        fun ptt(sys: Double) = run(base.copy(systolic = sys, seed = 7)).bcgPttMs ?: error("no BCG at $sys")
        val low = ptt(104.0)
        val high = ptt(124.0)
        // 20 mmHg at 0.8 mmHg/ms: 25 ms shorter.
        assertEquals(-25.0, high - low, 5.0)
    }

    @Test
    fun preEjectionPeriodIsMeasuredFromEcgAndBallistocardiogram() {
        for (pep in listOf(100.0, 65.0)) {
            val s = SyntheticSession.generate(base.copy(precise = true, pepMs = pep, seed = 8))
            val p = s.input.precise!!
            val t = TransitTimes.compute(p.ecg, p.ppg, p.fs, p.startNs, s.input.imu.accel, HemodynamicState.STEADY)!!
            assertTrue("pep $pep → $t", t.pepMeasured)
            assertEquals(pep, t.pepMs, 8.0)
        }
    }

    @Test
    fun aStressShortenedPreEjectionFoolsPatButNotTheTrueTransitTime() {
        // Same pressure as calibration, but a stressed heart: PEP 100 → 55 ms.
        val r = run(base.copy(precise = true, pepMs = 55.0, seed = 9))
        val channels = ok(r.outcome).channels.associateBy { it.channel }
        val pat = channels.getValue(BpChannel.PAT)
        val ptt = channels.getValue(BpChannel.ECG_PTT)
        assertTrue("PAT reads high: $pat", pat.systolic > 104 + 15)
        assertEquals(104.0, ptt.systolic, 7.0)
        // And the fusion leans on the true transit time.
        assertTrue("${ok(r.outcome)}", abs(ok(r.outcome).systolic - 104) < abs(pat.systolic - 104))
    }

    @Test
    fun armRaiseMeasuresTheSlopeNowAndMeanPressureWhenItIsLow() {
        // Arm raised slowly from heart level to overhead (13 → 19 s), held, lowered slowly (21 → 27 s).
        val maneuver = { t: Double ->
            when {
                t < 13 -> 0.0
                t < 19 -> 85.0 * (t - 13) / 6
                t < 21 -> 85.0
                t < 27 -> 85.0 * (27 - t) / 6
                else -> 0.0
            }
        }
        // Normal pressure: the slope is measured, the amplitude peak is out of reach (only a lower bound).
        val normal = run(base.copy(precise = true, pitchDeg = maneuver, seconds = 34.0, seed = 10))
        val h = normal.hydro ?: error("no maneuver result")
        assertEquals(1.0, h.sign, 0.0)
        val slope = h.patSlope ?: error("no slope")
        assertEquals(-0.8, slope.mmHgPerMs, 0.8 * 0.2)
        assertNull(h.map)
        assertNotNull(h.mapLowerBound)
        // Very low pressure (mean ≈ 35): the peak is reached and gives the mean pressure without a cuff.
        val low = run(base.copy(precise = true, systolic = 55.0, diastolic = 25.0, pitchDeg = maneuver, seconds = 34.0, seed = 11))
        val map = low.hydro?.map ?: error("no mean pressure: ${low.hydro}")
        assertEquals(35.0, map, 8.0)
    }

    @Test
    fun maneuverFindsTheSignWhenTheWatchIsWornTheOtherWay() {
        // Worn so that a raised hand tilts the accelerometer negative.
        val flipped = { t: Double -> if (t in 13.0..22.0) -80.0 else 0.0 }
        val s = SyntheticSession.generate(base.copy(precise = true, pitchDeg = flipped, seconds = 32.0, seed = 12))
        val p = s.input.precise!!
        val raised = s.spec.startNs + 13_000_000_000L..s.spec.startNs + 22_000_000_000L
        val times = LongArray(p.ppg.size) { p.startNs + (it * 1e9 / p.fs).toLong() }
        val h = HydrostaticCalibration.analyse(p.ppg, times, p.fs, s.input.imu.accel!!, 170.0, raised)!!
        assertEquals(-1.0, h.sign, 0.0)
    }

    @Test
    fun bathroomEpisodeWithAllQuickSensorsIsCloseToTheTruePressure() {
        val r = run(episode)
        val e = ok(r.outcome)
        assertEquals(HemodynamicState.COMPENSATORY, e.state.state)
        assertTrue("$e", abs(e.systolic - 92) <= 8)
        assertTrue("channels ${e.channels}", e.channels.any { it.channel == BpChannel.BCG_PTT })
        // Green PPG alone reads higher than the fusion.
        val green = e.channels.first { it.channel == BpChannel.PWA_GREEN }
        assertTrue("$green vs $e", green.systolic > e.systolic)
    }

    @Test
    fun bathroomEpisodeInPreciseModeIsCloseToTheTruePressure() {
        val e = ok(run(episode.copy(precise = true)).outcome)
        assertTrue("$e", abs(e.systolic - 92) <= 8)
        assertTrue("$e", abs(e.diastolic - 60) <= 8)
    }

    @Test
    fun withoutTheMotionChannelThereIsStillANumberWithAWiderPlusMinus() {
        val full = ok(run(episode).outcome)
        val noImu = ok(run(episode.copy(withImu = false)).outcome)
        assertTrue("$noImu", noImu.uncertaintySys > full.uncertaintySys)
        assertTrue("$noImu", noImu.systolic <= 115)
    }

    @Test
    fun theSteadiestWindowIsChosen() {
        // 20 s of a pulse settling from 110 to 75, then 40 s steady.
        val settling = SyntheticPpg.scenario(SyntheticPpg.Scenario(seconds = 20.0, heartRateStart = 110.0, heartRateEnd = 75.0, seed = 13))
        val steady = SyntheticPpg.scenario(SyntheticPpg.Scenario(seconds = 40.0, heartRateStart = 75.0, seed = 14))
        val raw = settling + steady
        val w = BpWindowSelector.best(raw, 100)!!
        assertTrue("$w", w.steady)
        assertTrue("${w.range}", w.range.first >= 18 * 100)
        assertTrue(BpWindowSelector.shouldStop(raw, 100))
        assertTrue(!BpWindowSelector.shouldStop(settling, 100))
    }

    @Test
    fun theArmsHeightIsFlaggedAndCanStillBeCorrectedInTheTransitChannel() {
        val hanging = base.copy(pitchDeg = { -40.0 }, withIr = false, seed = 15)
        // Algorithm 6.4: the angle no longer moves the number; a posture this far from the
        // calibration's is flagged instead (real cuff checks: the correction made errors larger).
        val e = ok(run(hanging).outcome)
        assertTrue(e.postureDiffers && e.beyondCalibration)
        // The physics is still there when asked for: the transit channel, corrected back.
        val corrected =
            ok(BpPipeline.run(calibration(), SyntheticSession.generate(hanging).input, 1_000, tuning = BpTuning.ALGORITHM_6_3).outcome)
        val bcg = corrected.channels.first { it.channel == BpChannel.BCG_PTT }
        assertEquals(104.0, bcg.systolic, 7.0)
    }

    @Test
    fun rawSessionLogRoundTripsAndReplaysToTheSameReading() {
        val s = SyntheticSession.generate(episode)
        val rec = BpSessionRecorder("s1", "measure", 0L) { 0L }
        val ppg = rec.stream(BpSessionStreams.PPG, *BpSessionStreams.PPG_COLUMNS)
        val times = s.input.greenTimesNs!!
        for (i in s.input.green.indices) ppg.append(times[i], s.input.green[i], s.input.ir!![i], Float.NaN, 0f, 0f, -1f)
        rec.attach(s.input.imu.accel!!)
        rec.event("recording", "start")
        val cal = calibration()
        val direct = BpPipeline.run(cal, s.input, 1_000, log = rec)
        val log = rec.build { copy(mode = BpSessionHeader.MODE_QUICK, algorithm = 6) }
        val bytes = log.encode()
        // A sample for tools/bp-ml/read_session.py (build output only).
        java.io.File("build/tmp/bp-session-sample.hlbp").apply { parentFile.mkdirs() }.writeBytes(bytes)
        val decoded = BpSessionLog.decode(bytes)
        assertEquals(log.header, decoded.header)
        assertEquals(log.streams, decoded.streams)
        assertTrue(decoded.header.values.containsKey("fusion.systolic"))
        assertTrue(decoded.header.values.containsKey("green.upstrokeMs"))
        val replayed = BpSessionReplay.replay(decoded, cal, 1_000)
        assertEquals(ok(direct.outcome).systolic, ok(replayed.outcome).systolic)
    }

    @Test
    fun calibrationRoundsCarryEveryChannel() {
        val cal = calibration(precise = true)
        val p = cal.points.last()
        assertEquals(CalibrationPoint.PRECISE_FS, p.featureFs)
        assertNotNull(p.bcgPttMs ?: p.pttMs)
        assertNotNull(p.patMs)
        assertNotNull(p.pepMs)
        val quick = calibration().points.first()
        assertNotNull(quick.irFeatures)
        assertNotNull(quick.bcgPttMs)
        assertNotNull(quick.gravity)
        assertTrue(PpgFeatures.extract(quick.ppg!!.toFloatArray(), 100) != null)
        // The quick channels still work without a WristBcg average (no motion sensor).
        assertNull(WristBcg.transitMs(null))
    }

    @Test
    fun sessionLogsTravelToThePhoneAndAreAckedAway() = runBlocking {
        val bytes = BpSessionRecorder("sess-1", "measure", 0L) { 0L }.build().encode()
        val pending = mutableMapOf("sess-1" to bytes)
        val outbox = object : Outbox {
            override suspend fun pending(): List<OutboxItem> = emptyList()

            override suspend fun pendingSessions() = pending.toList()

            override suspend fun markDelivered(id: String) {
                pending.remove(id)
            }
        }
        val received = mutableMapOf<String, ByteArray>()
        lateinit var watch: WatchSyncEngine
        lateinit var phone: PhoneSyncEngine
        // Direct wiring: each side hands the other's envelopes straight to its engine.
        val toPhone = object : SyncTransport {
            override val incoming: Flow<Envelope> = emptyFlow()

            override suspend fun send(path: String, data: ByteArray): Boolean {
                phone.handle(Envelope(path, data))
                return true
            }
        }
        val toWatch = object : SyncTransport {
            override val incoming: Flow<Envelope> = emptyFlow()

            override suspend fun send(path: String, data: ByteArray): Boolean {
                watch.handle(Envelope(path, data))
                return true
            }
        }
        val sink = object : RecordSink {
            override suspend fun contains(id: String) = false

            override suspend fun save(meta: RecordMeta, wave: FloatArray?) = Unit

            override suspend fun delete(id: String) = Unit
        }
        watch = WatchSyncEngine(toPhone, outbox)
        phone = PhoneSyncEngine(toWatch, sink, onSessionLog = { id, data -> received[id] = data })
        assertEquals(1, watch.flush())
        assertTrue(received.getValue("sess-1").contentEquals(bytes))
        assertTrue("acked and removed", pending.isEmpty())
        assertEquals("/hl/v1/bp/session/sess-1", Protocol.sessionPath("sess-1"))
    }
}
