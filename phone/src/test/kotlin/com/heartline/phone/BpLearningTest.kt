// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.BpValidationEntity
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.WaveStore
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sample.SyntheticPpg
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.PhoneSyncEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BpLearningTest {
    private lateinit var db: HeartlineDatabase
    private lateinit var records: RecordRepository
    private lateinit var bp: BpRepository
    private val alerts = mutableListOf<RecordSummary.BloodPressure>()
    private val now = 10 * BpCalibration.DAY_MS

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = HeartlineDatabase.inMemory(context)
        records = RecordRepository(db.records(), WaveStore(File(context.cacheDir, "bp-learning").apply { deleteRecursively() }))
        val engine = PhoneSyncEngine(InMemoryTransport.pair().first, records)
        bp = BpRepository(db.bp(), records, onSafety = { alerts += it }, now = { now }) { engine }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun calibrate() {
        val points = (1..3).map { CalibrationPoint(PpgFeatures.extract(SyntheticPpg.generate(20.0, 68.0, 0.45, seed = it), 100)!!, 120, 78, 68) }
        bp.saveCalibration(BpCalibration("cal", now - BpCalibration.DAY_MS, points))
    }

    private suspend fun reading(id: String, stiffness: Double, summary: RecordSummary.BloodPressure, seed: Int): RecordMeta {
        val wave = SyntheticPpg.generate(20.0, 70.0, stiffness, seed = seed)
        val meta = RecordMeta(id, RecordKind.BLOOD_PRESSURE, now - 60_000, 20_000, 100, wave.size, summary)
        records.save(meta, wave)
        bp.onRecordSaved(meta, wave)
        return meta
    }

    @Test
    fun aCuffCheckBecomesACalibrationPointAndIsExportable() = runBlocking {
        calibrate()
        reading("r1", 0.8, RecordSummary.BloodPressure(128, 82, 70, 9, algorithm = 3, beyondCalibration = true), seed = 11)
        bp.addValidation(BpValidationEntity("v1", "r1", now, 128, 82, 146, 90))
        val cal = bp.calibration.first()!!
        assertEquals(1, cal.extraPoints.size)
        assertEquals(146, cal.extraPoints.single().cuffSystolic)
        assertEquals(120..146, cal.systolicSpan)
        val data = bp.dataset()!!
        assertEquals(1, data.entries.size)
        assertEquals(2000, data.entries.single().ppg.size)
        assertEquals(146, data.entries.single().cuffSystolic)
    }

    @Test
    fun aConfirmedVeryHighReadingRaisesANotice() = runBlocking {
        calibrate()
        reading("r1", 0.9, RecordSummary.BloodPressure(184, 104, 90, 14, algorithm = 3, beyondCalibration = true), seed = 12)
        assertTrue(alerts.isEmpty())
        reading("r2", 0.9, RecordSummary.BloodPressure(186, 106, 90, 14, algorithm = 3, beyondCalibration = true, confirmed = true), seed = 13)
        assertEquals(186, alerts.single().systolic)
    }

    @Test
    fun readingsAreRefinedOnlyOnceThePersonalModelEarnsIt() = runBlocking {
        calibrate()
        // Too few checks: the watch's reading stands.
        reading("first", 0.5, RecordSummary.BloodPressure(121, 79, 70, 6, algorithm = 3), seed = 20)
        assertEquals(3, (records.get("first")!!.summary as RecordSummary.BloodPressure).algorithm)

        // This user reads systematically low on stiff pulses; the checks teach the model that.
        for (i in 0 until 10) {
            val stiffness = 0.2 + 0.07 * i
            reading("c$i", stiffness, RecordSummary.BloodPressure(122, 79, 70, 6, algorithm = 3), seed = 30 + i)
            bp.addValidation(BpValidationEntity("v$i", "c$i", now, 122, 79, (122 + 40 * (stiffness - 0.5)).toInt(), 79))
        }
        reading("new", 0.85, RecordSummary.BloodPressure(122, 79, 70, 6, algorithm = 3), seed = 50)
        val refined = records.get("new")!!.summary as RecordSummary.BloodPressure
        assertEquals(4, refined.algorithm)
        assertEquals(122, refined.watchSystolic)
        assertTrue("$refined", refined.systolic > 128)
        assertNull((records.get("first")!!.summary as RecordSummary.BloodPressure).watchSystolic)
    }
}
