// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import com.heartline.phone.data.RecordEntity
import com.heartline.phone.export.CsvFormat
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.model.Symptom
import com.heartline.shared.sync.Protocol
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvExportTest {
    private fun entity(kind: RecordKind, summary: RecordSummary) =
        RecordEntity("id", kind, 0, 0, 0, 0, Protocol.json.encodeToString<RecordSummary>(summary), null, receivedAtMs = 0)

    @Test
    fun rowsPerKind() {
        assertEquals(
            "1970-01-01T00:00:00Z,BLOOD_PRESSURE,118/76,mmHg,pulse=64",
            CsvFormat.row(entity(RecordKind.BLOOD_PRESSURE, RecordSummary.BloodPressure(118, 76, 64))),
        )
        assertEquals(
            "1970-01-01T00:00:00Z,ECG,72,bpm,result=SINUS_RHYTHM;symptoms=FATIGUE|DIZZINESS",
            CsvFormat.row(entity(RecordKind.ECG, RecordSummary.Ecg(72, EcgResult.SINUS_RHYTHM, 0f, listOf(Symptom.FATIGUE, Symptom.DIZZINESS)))),
        )
        assertEquals(5, CsvFormat.HEADER.split(',').size)
    }

    @Test
    fun valuesWithCommasAreQuoted() {
        val row = CsvFormat.row(entity(RecordKind.BODY_COMPOSITION, RecordSummary.BodyComposition(21.4f, null, null, null)))
        assertEquals(5, Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)").split(row).size)
    }
}
