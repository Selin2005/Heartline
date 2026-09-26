package com.heartline.wear

import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.DayPart
import com.heartline.shared.profile.UserProfile
import com.heartline.wear.ui.LauncherViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class LauncherOrderTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 9, 26, 8, 30).toInstant(zone).toEpochMilli()
    private val hour = 3_600_000L

    private fun spo2(at: Long) = RecordMeta("s$at", RecordKind.SPO2, at, 30_000, 0, 0, RecordSummary.Spo2(97, null, false))

    private fun bp(at: Long) = RecordMeta("b$at", RecordKind.BLOOD_PRESSURE, at, 30_000, 0, 0, RecordSummary.BloodPressure(118, 76, 64))

    private val supported = listOf(Metric.ECG, Metric.BLOOD_PRESSURE, Metric.HEART_RATE, Metric.SPO2)

    @Test
    fun mostUsedFirstAndPinsOnTop() {
        val recent = listOf(spo2(now - hour), spo2(now - 30 * hour), spo2(now - 50 * hour), bp(now - 2 * hour))
        assertEquals(listOf(Metric.SPO2, Metric.BLOOD_PRESSURE, Metric.ECG, Metric.HEART_RATE), LauncherViewModel.order(supported, recent, emptyList(), now))
        assertEquals(Metric.HEART_RATE, LauncherViewModel.order(supported, recent, listOf(Metric.HEART_RATE), now).first())
    }

    @Test
    fun headerGreetsAndCountsTodaysCheckIns() {
        val sara = UserProfile(firstName = "Sara", birthDate = "1990-09-26")
        val header = LauncherViewModel.header(listOf(bp(now - hour), spo2(now - 20 * hour)), sara, now, zone)
        assertEquals(DayPart.MORNING, header.part)
        assertEquals("Sara", header.name)
        assertTrue(header.birthday)
        // Blood pressure done today; yesterday's SpO2 doesn't count; ECG is next.
        assertEquals(1, header.done)
        assertEquals(Metric.ECG, header.next)
    }
}
