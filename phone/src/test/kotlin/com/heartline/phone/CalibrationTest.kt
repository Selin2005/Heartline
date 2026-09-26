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
        withTimeout(5_000) { vm.state.first { it.phase == CalibrationUi.Phase.DONE } }
        val saved = withTimeout(5_000) { bp.calibration.first { it != null } }!!
        assertEquals(3, saved.points.size)
        assertEquals(122.0, saved.points.map { it.cuffSystolic }.average(), 1e-9)
        assertTrue(saved.isValid(1_000L + BpCalibration.VALIDITY_MS - 1))
        withTimeout(5_000) { while (sentCalibrations.isEmpty()) delay(5) }
        watchJob.cancel()
    }
}
