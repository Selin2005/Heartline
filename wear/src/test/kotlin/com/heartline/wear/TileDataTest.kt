package com.heartline.wear

import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.wear.tile.TileData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileDataTest {
    @Test
    fun picksLatestEcgAndBloodPressure() {
        val recent = listOf(
            RecordMeta("b", RecordKind.BLOOD_PRESSURE, 3, 0, 0, 0, RecordSummary.BloodPressure(121, 79, 70)),
            RecordMeta("e", RecordKind.ECG, 2, 0, 500, 0, RecordSummary.Ecg(70, EcgResult.SINUS_RHYTHM, 0f)),
            RecordMeta("e0", RecordKind.ECG, 1, 0, 500, 0, RecordSummary.Ecg(90, EcgResult.AFIB_SIGNS, 0f)),
        )
        val data = TileData.from(recent, 64, 21) { it.result?.name }
        assertEquals("SINUS_RHYTHM", data.lastEcg)
        assertEquals("121/79", data.lastBp)
        assertEquals(64, data.heartRate)
        assertEquals(21, data.bpDaysLeft)
        assertNull(TileData.from(emptyList(), null, null) { null }.lastEcg)
    }

    @Test
    fun wellnessValuesAndTodaysRange() {
        val recent = listOf(
            RecordMeta("s", RecordKind.STRESS, 9, 0, 0, 0, RecordSummary.Stress(72, 18.0)),
            RecordMeta("o", RecordKind.SPO2, 8, 0, 0, 0, RecordSummary.Spo2(96, 70, false)),
            RecordMeta("t3", RecordKind.SKIN_TEMPERATURE, 7, 0, 0, 0, RecordSummary.SkinTemperature(33.4f, null)),
            RecordMeta("t2", RecordKind.SKIN_TEMPERATURE, 6, 0, 0, 0, RecordSummary.SkinTemperature(33.0f, null)),
            RecordMeta("t1", RecordKind.SKIN_TEMPERATURE, 5, 0, 0, 0, RecordSummary.SkinTemperature(33.1f, null)),
            RecordMeta("t0", RecordKind.SKIN_TEMPERATURE, 4, 0, 0, 0, RecordSummary.SkinTemperature(33.2f, null)),
        )
        val data = TileData.from(recent, 64, null, com.heartline.wear.monitor.HeartToday(70, 52, 118)) { null }
        assertEquals(70, data.heartRate)
        assertEquals(52, data.heartMin)
        assertEquals(118, data.heartMax)
        assertEquals(96, data.spo2)
        assertEquals(72, data.stressScore)
        assertEquals(com.heartline.shared.profile.StressLevel.HIGH, data.stressLevel)
        assertEquals(18, data.hrvMs)
        // 33.4 against the median of the previous readings (33.1).
        assertEquals("+0.3°", data.temperature)
    }
}
