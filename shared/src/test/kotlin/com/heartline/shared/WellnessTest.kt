// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.Gender
import com.heartline.shared.profile.Sex
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.profile.StressLevel
import com.heartline.shared.profile.TemperatureBaseline
import com.heartline.shared.profile.UserProfile
import com.heartline.shared.sync.Protocol
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WellnessTest {
    @Test
    fun stressFallsWithHigherHrv() {
        val tense = StressIndex.score(12.0)
        val calm = StressIndex.score(90.0)
        assertEquals(100, tense)
        assertEquals(0, calm)
        assertEquals(StressLevel.MEDIUM, StressIndex.level(StressIndex.score(35.0)))
        assertTrue(StressIndex.score(35.0, skinConductanceMicroSiemens = 9f) > StressIndex.score(35.0))
    }

    @Test
    fun temperatureDeviationUsesMedianBaseline() {
        assertEquals(0.5f, TemperatureBaseline.deviation(33.8f, listOf(33.0f, 33.2f, 33.4f, 35.0f))!!, 1e-6f)
        assertEquals(-0.3f, TemperatureBaseline.deviation(32.9f, listOf(33.1f, 33.2f, 33.3f))!!, 1e-6f)
        assertNull(TemperatureBaseline.deviation(33.6f, listOf(33.1f)))
    }

    @Test
    fun profileValidationAndAge() {
        val p = UserProfile("Ada", "Lovelace", birthDate = "1990-03-01", gender = Gender.WOMAN, heightCm = 168f, weightKg = 61f)
        assertTrue(p.isComplete)
        assertEquals(36, p.age(java.time.LocalDate.of(2026, 3, 1)))
        assertEquals(35, p.age(java.time.LocalDate.of(2026, 2, 28)))
        assertEquals(Sex.FEMALE, p.calcSex)
        assertFalse(p.copy(heightCm = 40f).isComplete)
    }

    @Test
    fun stressSummarySerializes() {
        val meta = RecordMeta("s", RecordKind.STRESS, 0, 60_000, 0, 0, RecordSummary.Stress(42, 31.0, 3.5f))
        assertEquals(meta, Protocol.json.decodeFromString<RecordMeta>(Protocol.json.encodeToString(meta)))
    }
}
