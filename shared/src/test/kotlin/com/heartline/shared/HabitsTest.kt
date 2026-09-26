package com.heartline.shared

import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.Baseline
import com.heartline.shared.profile.DailyGoal
import com.heartline.shared.profile.PersonalBaseline
import com.heartline.shared.profile.Streak
import com.heartline.shared.profile.WeekSummary
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HabitsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 9, 26)

    private fun at(day: LocalDate, hour: Int = 9) = day.atTime(hour, 0).toInstant(zone).toEpochMilli()

    private fun bp(day: LocalDate, sys: Int = 118) =
        RecordMeta("b$day$sys", RecordKind.BLOOD_PRESSURE, at(day), 30_000, 0, 0, RecordSummary.BloodPressure(sys, 76, 64))

    private fun spo2(day: LocalDate) = RecordMeta("s$day", RecordKind.SPO2, at(day, 10), 30_000, 0, 0, RecordSummary.Spo2(97, null, false))

    @Test
    fun streakCountsFullDaysBackFromToday() {
        val goal = listOf(Metric.BLOOD_PRESSURE, Metric.SPO2)
        val records = (1..3).flatMap { listOf(bp(today.minusDays(it.toLong())), spo2(today.minusDays(it.toLong()))) } + bp(today)
        val byDay = DailyGoal.byDay(records, zone)
        // Today is still open: yesterday and the two days before.
        assertEquals(3, Streak.of(byDay, goal, today))
        assertEquals(4, Streak.of(DailyGoal.byDay(records + spo2(today), zone), goal, today))
        assertEquals(0, Streak.of(byDay, emptyList(), today))
    }

    @Test
    fun baselineIsTheMiddleHalf() {
        assertNull(PersonalBaseline.of(listOf(1f, 2f)))
        val b = PersonalBaseline.of(listOf(110f, 115f, 118f, 120f, 122f, 125f, 140f))!!
        assertEquals(120f, b.median, 0f)
        assertEquals(Baseline.Place.USUAL, b.place(120f))
        assertEquals(Baseline.Place.ABOVE, b.place(135f))
        assertEquals(-5, b.delta(115f))
    }

    @Test
    fun weekInNumbers() {
        val records = (0..6).map { bp(today.minusDays(it.toLong()), 120) } + (7..13).map { bp(today.minusDays(it.toLong()), 126) }
        val week = WeekSummary.of(records, listOf(Metric.BLOOD_PRESSURE), today, zone)
        assertEquals(7, week.measurements)
        assertEquals(120 to 76, week.bpAverage)
        assertEquals(-6, week.bpChange)
        assertEquals(7, week.goalDays)
        assertEquals(14, week.streak)
    }
}
