package com.heartline.shared

import com.heartline.shared.profile.Ago
import com.heartline.shared.profile.DayPart
import com.heartline.shared.profile.UserProfile
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalTest {
    @Test
    fun greetingFollowsTheTimeOfDay() {
        assertEquals(DayPart.MORNING, DayPart.of(7))
        assertEquals(DayPart.AFTERNOON, DayPart.of(13))
        assertEquals(DayPart.EVENING, DayPart.of(19))
        assertEquals(DayPart.NIGHT, DayPart.of(2))
    }

    @Test
    fun birthdays() {
        val sara = UserProfile(firstName = "Sara", birthDate = "1990-09-26")
        assertTrue(sara.isBirthday(LocalDate.of(2026, 9, 26)))
        assertFalse(sara.isBirthday(LocalDate.of(2026, 9, 27)))
        val leap = UserProfile(birthDate = "2000-02-29")
        assertTrue(leap.isBirthday(LocalDate.of(2027, 2, 28)))
        assertFalse(UserProfile().isBirthday())
    }

    @Test
    fun agoLabels() {
        val hour = 3_600_000L
        val day: (Long) -> Long = { it / (24 * hour) }
        val now = 10 * 24 * hour + 15 * hour
        assertEquals(Ago.JustNow, Ago.of(now - 20_000, now, day))
        assertEquals(Ago.Minutes(12), Ago.of(now - 12 * 60_000, now, day))
        assertEquals(Ago.Hours(3), Ago.of(now - 3 * hour, now, day))
        assertEquals(Ago.Yesterday, Ago.of(now - 20 * hour, now, day))
        assertEquals(Ago.Days(3), Ago.of(now - 3 * 24 * hour, now, day))
    }
}
