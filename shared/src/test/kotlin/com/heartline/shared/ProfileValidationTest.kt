package com.heartline.shared

import com.heartline.shared.profile.Gender
import com.heartline.shared.profile.NumberInput
import com.heartline.shared.profile.ProfileError
import com.heartline.shared.profile.ProfileField
import com.heartline.shared.profile.ProfileValidator
import com.heartline.shared.profile.ReportName
import com.heartline.shared.profile.Sex
import com.heartline.shared.profile.UserProfile
import com.heartline.shared.sync.Protocol
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidationTest {
    private val today = LocalDate.of(2026, 9, 24)
    private val valid = UserProfile("Sam", "Rahimi", "Sam", "1992-04-10", Gender.MAN, heightCm = 178f, weightKg = 74.5f)

    @Test
    fun validProfileHasNoErrors() {
        assertTrue(ProfileValidator.errors(valid, today).isEmpty())
        assertEquals(34, valid.age(today))
    }

    @Test
    fun emptyProfileReportsEveryFieldOnce() {
        val errors = ProfileValidator.errors(UserProfile(), today)
        assertEquals(
            listOf(
                ProfileError.FIRST_NAME_MISSING,
                ProfileError.LAST_NAME_MISSING,
                ProfileError.BIRTH_DATE_MISSING,
                ProfileError.GENDER_MISSING,
                ProfileError.HEIGHT_MISSING,
                ProfileError.WEIGHT_MISSING
            ),
            errors.values.toList()
        )
    }

    @Test
    fun eachProblemPointsAtItsOwnField() {
        fun only(p: UserProfile) = ProfileValidator.errors(p, today).entries.single()
        assertEquals(ProfileField.HEIGHT to ProfileError.HEIGHT_OUT_OF_RANGE, only(valid.copy(heightCm = 34f)).toPair())
        assertEquals(ProfileField.WEIGHT to ProfileError.WEIGHT_OUT_OF_RANGE, only(valid.copy(weightKg = 900f)).toPair())
        assertEquals(ProfileField.BIRTH_DATE to ProfileError.BIRTH_DATE_FUTURE, only(valid.copy(birthDate = "2027-01-01")).toPair())
        assertEquals(ProfileField.BIRTH_DATE to ProfileError.TOO_YOUNG, only(valid.copy(birthDate = "2020-01-01")).toPair())
        assertEquals(ProfileField.BIRTH_DATE to ProfileError.TOO_OLD, only(valid.copy(birthDate = "1890-01-01")).toPair())
    }

    @Test
    fun calcSexComesFromGenderOrExplicitChoice() {
        assertEquals(Sex.MALE, valid.calcSex)
        val nb = valid.copy(gender = Gender.PREFER_NOT_TO_SAY)
        assertNull(nb.calcSex)
        assertEquals(Sex.FEMALE, nb.copy(sex = Sex.FEMALE).calcSex)
        assertTrue(ProfileValidator.errors(nb, today).isEmpty())
    }

    @Test
    fun reportNameFollowsTheChoice() {
        val p = valid.copy(firstName = "Alex", preferredName = "Lex")
        assertEquals("Lex", p.reportName(ReportName.PREFERRED_NAME))
        assertEquals("Alex Rahimi", p.reportName(ReportName.FULL_NAME))
        assertNull(p.reportName(ReportName.NONE))
        // No nickname: the first name stands in.
        assertEquals("Alex", p.copy(preferredName = " ").reportName(ReportName.PREFERRED_NAME))
    }

    @Test
    fun legacyGendersReadAsPreferNotToSay() {
        val old = Protocol.json.decodeFromString<UserProfile>(
            """{"firstName":"Alex","gender":"NON_BINARY","genderDescription":"x","reportName":"FULL_NAME"}"""
        )
        assertEquals(Gender.PREFER_NOT_TO_SAY, old.gender)
        assertEquals(
            Gender.MAN,
            Protocol.json.decodeFromString<UserProfile>(Protocol.json.encodeToString(UserProfile.serializer(), valid)).gender
        )
    }

    @Test
    fun numberInputAcceptsPersianDigitsAndSeparators() {
        assertEquals(175f, NumberInput.parse("۱۷۵")!!, 0f)
        assertEquals(72.5f, NumberInput.parse("۷۲٫۵")!!, 1e-4f)
        assertEquals(72.5f, NumberInput.parse("72,5")!!, 1e-4f)
        assertEquals(180.34f, NumberInput.cmFromFeet(5f, 11f), 0.01f)
        assertNull(NumberInput.parse("1.2.3"))
        assertNull(NumberInput.parse(""))
    }

    @Test
    fun legacyProfileStillDecodesButIsIncomplete() {
        val old = Protocol.json.decodeFromString<UserProfile>("""{"birthYear":1990,"sex":"FEMALE","heightCm":168.0,"weightKg":61.0}""")
        assertEquals(Sex.FEMALE, old.sex)
        assertEquals(36, old.age(today))
        assertEquals(ProfileError.FIRST_NAME_MISSING, ProfileValidator.errors(old, today).values.first())
    }
}
