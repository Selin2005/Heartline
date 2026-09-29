// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.WaveStore
import com.heartline.phone.ui.model.CalibrationUi
import com.heartline.phone.ui.model.CalibrationViewModel
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpProfile
import com.heartline.shared.bp.BpSessionRecorder
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.CaptureResult
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CalibrationTest {
    private lateinit var db: HeartlineDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = HeartlineDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun threeRoundsProduceCalibrationSentToWatch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val (phoneSide, watchSide) = InMemoryTransport.pair()
        val records = RecordRepository(db.records(), WaveStore(File(context.cacheDir, "cal")))
        lateinit var engine: PhoneSyncEngine
        val bp = BpRepository(db.bp(), records) { engine }
        engine = PhoneSyncEngine(phoneSide, records, onCaptureResult = { bp.onCaptureResult(it) })

        val requests = mutableListOf<CaptureRequest>()
        val sentCalibrations = mutableListOf<String>()
        val watchJob = watchSide.incoming.onEach { env ->
            when (env.path) {
                Protocol.BP_CALIBRATION_CAPTURE -> requests += Protocol.json.decodeFromString<CaptureRequest>(env.data.decodeToString())
                Protocol.BP_CALIBRATION -> sentCalibrations += env.data.decodeToString()
            }
        }.launchIn(this)
        yield() // let the fake watch subscribe before the first request is sent

        val vm = CalibrationViewModel(bp, now = { 1_000L })
        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0), 100)!!
        vm.startRound()
        for (round in 1..3) {
            withTimeout(5_000) { while (requests.size < round) delay(5) }
            val request = requests[round - 1]
            assertEquals(round, request.round)
            // The watch answers with the features for this round.
            engine.handle(
                com.heartline.shared.sync.Envelope(
                    Protocol.BP_CALIBRATION_CAPTURE,
                    Protocol.json.encodeToString(CaptureResult.serializer(), CaptureResult("r$round", request.captureId, round, features)).encodeToByteArray(),
                ),
            )
            withTimeout(5_000) { vm.state.first { it.phase == CalibrationUi.Phase.ENTER_CUFF } }
            if (round == 1) {
                vm.submitCuff(80, 120, 60) // diastolic above systolic: rejected
                assertTrue(vm.state.value.inputError)
            }
            vm.submitCuff(120 + round, 80, 65)
        }
        // After the 3 seated rounds the standing round is offered; this user skips it.
        withTimeout(5_000) { vm.state.first { it.phase == CalibrationUi.Phase.OFFER_STANDING } }
        vm.finish()
        withTimeout(5_000) { vm.state.first { it.phase == CalibrationUi.Phase.DONE } }
        val saved = withTimeout(5_000) { bp.calibration.first { it != null } }!!
        assertEquals(3, saved.points.size)
        assertEquals(122.0, saved.points.map { it.cuffSystolic }.average(), 1e-9)
        assertTrue(saved.isValid(1_000L + BpCalibration.VALIDITY_MS - 1))
        withTimeout(5_000) { while (sentCalibrations.isEmpty()) delay(5) }
        watchJob.cancel()
    }

    @Test
    fun standingRoundAndHealthProfileAreSavedWithTheCalibration() = runBlocking {
        val (phoneSide, watchSide) = InMemoryTransport.pair()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val records = RecordRepository(db.records(), WaveStore(File(context.cacheDir, "cal-standing")))
        lateinit var engine: PhoneSyncEngine
        val bp = BpRepository(db.bp(), records) { engine }
        engine = PhoneSyncEngine(phoneSide, records, onCaptureResult = { bp.onCaptureResult(it) })
        val requests = mutableListOf<CaptureRequest>()
        val watchJob = watchSide.incoming.onEach { env ->
            if (env.path == Protocol.BP_CALIBRATION_CAPTURE) requests += Protocol.json.decodeFromString<CaptureRequest>(env.data.decodeToString())
        }.launchIn(this)
        yield()

        val vm = CalibrationViewModel(bp, now = { 1_000L })
        vm.setProfile(BpProfile(betaBlocker = true))
        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0), 100)!!
        val seated = listOf(0.0, 0.0, 9.8)
        suspend fun answer(round: Int, gravity: List<Double>) {
            withTimeout(5_000) { while (requests.none { it.round == round }) delay(5) }
            val request = requests.last { it.round == round }
            val result = CaptureResult("r$round", request.captureId, round, features, gravity = gravity)
            engine.handle(com.heartline.shared.sync.Envelope(Protocol.BP_CALIBRATION_CAPTURE, Protocol.json.encodeToString(CaptureResult.serializer(), result).encodeToByteArray()))
            withTimeout(5_000) { vm.state.first { it.phase == CalibrationUi.Phase.ENTER_CUFF && it.round == round } }
        }
        vm.startRound()
        for (round in 1..3) {
            answer(round, seated)
            vm.submitCuff(104, 70, 70)
        }
        withTimeout(5_000) { vm.state.first { it.phase == CalibrationUi.Phase.OFFER_STANDING } }
        vm.addStandingRound()
        answer(BpCalibration.STANDING_ROUND, listOf(9.8, 0.0, 0.0))
        assertTrue(vm.state.value.standingRound)
        vm.submitCuff(100, 72, 88)
        withTimeout(5_000) { vm.state.first { it.phase == CalibrationUi.Phase.DONE } }
        val saved = withTimeout(5_000) { bp.calibration.first { it != null } }!!
        assertEquals(4, saved.points.size)
        assertEquals(listOf(false, false, false, true), saved.points.map { it.standing })
        assertEquals(seated, saved.points.first().gravity)
        assertEquals(BpProfile(betaBlocker = true), saved.profile)
        assertTrue(saved.isValid(2_000))
        // The profile can be changed later without recalibrating.
        bp.saveProfile(BpProfile(atrialFibrillation = true))
        assertEquals(BpProfile(atrialFibrillation = true), withTimeout(5_000) { bp.calibration.first { it?.profile?.atrialFibrillation == true } }!!.profile)
        watchJob.cancel()
    }

    @Test
    fun everyRoundAndCuffCheckKeepsItsCuffReadingWithItsRawSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val (phoneSide, watchSide) = InMemoryTransport.pair()
        val records = RecordRepository(db.records(), WaveStore(File(context.cacheDir, "cal-sessions")))
        val dir = File(context.cacheDir, "bp-sessions-test").apply { deleteRecursively() }
        lateinit var engine: PhoneSyncEngine
        val bp = BpRepository(db.bp(), records, sessionsDir = dir) { engine }
        engine = PhoneSyncEngine(phoneSide, records, onCaptureResult = { bp.onCaptureResult(it) })
        val requests = mutableListOf<CaptureRequest>()
        val watchJob = watchSide.incoming.onEach { env ->
            if (env.path == Protocol.BP_CALIBRATION_CAPTURE) requests += Protocol.json.decodeFromString<CaptureRequest>(env.data.decodeToString())
        }.launchIn(this)
        yield()
        fun session(id: String) = BpSessionRecorder(id, "calibration", 0L) { 0L }.build { this }.encode()

        val vm = CalibrationViewModel(bp, now = { 1_000L })
        val features = PpgFeatures.extract(SyntheticPpg.generate(20.0), 100)!!
        vm.startRound()
        for (round in 1..3) {
            withTimeout(5_000) { while (requests.size < round) delay(5) }
            val request = requests[round - 1]
            // Round 1's raw session arrives before its cuff reading, the others after it.
            if (round == 1) bp.saveSession("s1", session("s1"))
            val result = CaptureResult("r$round", request.captureId, round, features, sessionId = "s$round")
            engine.handle(com.heartline.shared.sync.Envelope(Protocol.BP_CALIBRATION_CAPTURE, Protocol.json.encodeToString(CaptureResult.serializer(), result).encodeToByteArray()))
            withTimeout(5_000) { vm.state.first { it.phase == CalibrationUi.Phase.ENTER_CUFF && it.round == round } }
            vm.submitCuff(130 + round, 80, 70)
        }
        bp.saveSession("s2", session("s2"))
        bp.saveSession("s3", session("s3"))
        withTimeout(5_000) { vm.state.first { it.phase == CalibrationUi.Phase.OFFER_STANDING } }
        vm.finish()
        val saved = withTimeout(5_000) { bp.calibration.first { it != null } }!!
        assertEquals(listOf("s1", "s2", "s3"), saved.points.map { it.sessionId })
        for (round in 1..3) {
            val header = withTimeout(5_000) {
                var h = bp.session("s$round")?.header
                while (h?.cuffSystolic == null) {
                    delay(5)
                    h = bp.session("s$round")?.header
                }
                h
            }
            assertEquals(130 + round, header.cuffSystolic)
            assertEquals(80, header.cuffDiastolic)
        }
        // The diagnostic export has every calibration with its cuff readings and session ids.
        val files = bp.diagnosticsFiles()
        val calibrations = files.getValue("bp/calibrations.json").decodeToString()
        assertTrue(calibrations.contains("\"cuffSystolic\":131") && calibrations.contains("\"sessionId\":\"s3\""))
        assertTrue(files.getValue("bp/cuff-checks.csv").decodeToString().startsWith("atMs,readingId,sessionId"))
        watchJob.cancel()
    }
}
