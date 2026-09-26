// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.WaveStore
import com.heartline.phone.ecg.EcgFounder
import com.heartline.phone.ecg.EcgSecondOpinion
import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.ecg.EcgFounderInput
import com.heartline.shared.ecg.EcgSession
import com.heartline.shared.ecg.RhythmModel
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sample.SyntheticEcg
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EcgSecondOpinionTest {
    private lateinit var db: HeartlineDatabase
    private lateinit var records: RecordRepository
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val model by lazy { RhythmModel.parse(context.assets.open(EcgSecondOpinion.MODEL_ASSET).use { it.readBytes().decodeToString() }) }

    @Before
    fun setUp() {
        db = HeartlineDatabase.inMemory(context)
        records = RecordRepository(db.records(), WaveStore(File(context.cacheDir, "ecg-second").apply { deleteRecursively() }))
    }

    @After
    fun tearDown() = db.close()

    /** A stand-in for ECGFounder: "normal sinus rhythm" labels high, everything else low. */
    private val normalFounder = EcgFounder { windows ->
        assertTrue(windows.all { it.size == EcgFounderInput.WINDOW })
        DoubleArray(150) { if (it in 1..3) 0.95 else 0.02 }
    }

    private suspend fun ecgRecord(): Pair<RecordMeta, FloatArray> {
        val wave = SyntheticEcg.generate(30.0, 70.0, seed = 3).let { s -> FloatArray(s.size) { s[it] * 0.4f } }
        val a = EcgAnalyzer.analyze(wave, 500, EcgSession())
        val meta = RecordMeta("e1", RecordKind.ECG, 0, 30_000, 500, wave.size, RecordSummary.Ecg(a.averageBpm, a.result, 0f, metrics = a.metrics))
        records.save(meta, wave)
        return meta to wave
    }

    @Test
    fun theSecondOpinionModelMatchesTheFeatures() {
        assertEquals(24 + 150, model.features.size)
        assertEquals(listOf("normal", "af", "other", "noisy"), model.classes)
    }

    @Test
    fun aSecondOpinionIsAddedToTheRecord() = runBlocking {
        val (meta, wave) = ecgRecord()
        EcgSecondOpinion({ normalFounder }, { model }).onRecordSaved(records, meta, wave)
        val m = (records.get("e1")!!.summary as RecordSummary.Ecg).metrics!!
        assertNotNull(m.secondOpinion)
        assertNotNull(m.secondOpinionAfProbability)
    }

    @Test
    fun withoutEcgFounderNothingChanges() = runBlocking {
        val (meta, wave) = ecgRecord()
        EcgSecondOpinion({ null }, { model }).onRecordSaved(records, meta, wave)
        assertNull((records.get("e1")!!.summary as RecordSummary.Ecg).metrics!!.secondOpinion)
    }
}
