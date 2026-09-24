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
}
