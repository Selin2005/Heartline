package com.heartline.shared

import com.heartline.shared.body.BodyComposition
import com.heartline.shared.body.BodyLevel
import com.heartline.shared.body.BodyType
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.Sex
import com.heartline.shared.profile.UserProfile
import com.heartline.shared.sync.Protocol
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyCompositionTest {
    private val full = RecordSummary.BodyComposition(
        bodyFatPercent = 18f,
        skeletalMuscleKg = 34f,
        bodyWaterKg = 44f,
        bmrKcal = 1700,
        weightKg = 78f,
        heightCm = 178f,
        bodyFatMassKg = 14.04f,
        impedanceOhm = 485f,
        phaseAngleDeg = -6.9f
    )

    @Test
    fun derivesBmiSharesAndPhaseAngle() {
        val r = BodyComposition.report(full, Sex.MALE, 32)
        assertEquals(78f / (1.78f * 1.78f), r.bmi!!, 1e-4f)
        assertEquals(43.6f, r.skeletalMusclePercent!!, 0.1f)
        assertEquals(56.4f, r.bodyWaterPercent!!, 0.1f)
        assertEquals(63.96f, r.fatFreeMassKg!!, 0.01f)
        assertEquals(6.9f, r.phaseAngleDeg!!, 1e-6f)
        assertEquals(1f, r.shares!!.sum(), 1e-4f)
    }

    @Test
    fun levelsAndBodyTypeDependOnSexAndAge() {
        val man = BodyComposition.report(full, Sex.MALE, 32)
        assertEquals(BodyLevel.STANDARD, man.fatLevel)
        assertEquals(BodyType.BALANCED, man.bodyType)
        val woman = BodyComposition.report(full, Sex.FEMALE, 32)
        assertEquals(BodyLevel.LOW, woman.fatLevel)
        assertEquals(BodyType.ATHLETIC, woman.bodyType)
        val skinnyFat = BodyComposition.report(full.copy(bodyFatPercent = 27f, skeletalMuscleKg = 26f), Sex.MALE, 32)
        assertEquals(BodyType.HIDDEN_OVERWEIGHT, skinnyFat.bodyType)
        assertEquals(BodyLevel.VERY_HIGH, BodyComposition.ranges(Sex.MALE, 30).bmi.level(31f))
    }

    @Test
    fun oldRecordsStillLoadAndUseTheProfileWeight() {
        val old = """{"type":"com.heartline.shared.model.RecordSummary.BodyComposition",""" +
            """"bodyFatPercent":21.4,"skeletalMuscleKg":30.0,"bodyWaterKg":40.0,"bmrKcal":1500}"""
        val s = Protocol.json.decodeFromString<RecordSummary>(old) as RecordSummary.BodyComposition
        assertNull(s.weightKg)
        val r = BodyComposition.report(s, Sex.FEMALE, 40, fallbackWeightKg = 70f, fallbackHeightCm = 165f)
        assertEquals(14.98f, r.fatMassKg!!, 0.01f)
        assertTrue(r.bmi!! > 25f)
        val round = Protocol.json.decodeFromString<RecordSummary>(Protocol.json.encodeToString<RecordSummary>(full))
        assertEquals(full, round)
    }

    @Test
    fun weightIsAskedAgainAfterAMonth() {
        val now = 100L * 86_400_000L
        assertTrue(UserProfile(weightKg = 70f).weightIsStale(now))
        assertFalse(UserProfile(weightKg = 70f, weightUpdatedAtMs = now - 10 * 86_400_000L).weightIsStale(now))
        assertTrue(UserProfile(weightKg = 70f, weightUpdatedAtMs = now - 40 * 86_400_000L).weightIsStale(now))
    }
}
