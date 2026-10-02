// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.hr.AlertSensitivity
import com.heartline.shared.hr.DayStats
import com.heartline.shared.hr.HeartBaseline
import com.heartline.shared.hr.HeartHistory
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.MaxHr
import com.heartline.shared.profile.Sex
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartBaselineTest {
    private val today = 20_000L

    /** [days] days of resting and sleeping minutes around [rest] / [sleep] bpm (spread ± [spread]). */
    private fun history(rest: Int, sleep: Int, days: Int = 28, perDay: Int = 150, spread: Int = 4, seed: Int = 1): HeartHistory {
        val random = Random(seed)
        fun hist(center: Int, n: Int) = (0 until n).map { center + random.nextInt(-spread, spread + 1) }.groupingBy { it }.eachCount()
        return HeartHistory(
            (0 until days).map { i ->
                DayStats(today - i, rest = hist(rest, perDay), sleep = hist(sleep, perDay / 3))
            }.sortedBy { it.day }
        )
    }

    @Test
    fun ratiosComeFromTheClinicalNumbers() {
        val std = HeartBaseline.ratios(AlertSensitivity.STANDARD)
        assertEquals(100 / 65.0, std.high, 1e-9) // tachycardia at rest
        assertEquals(45 / 65.0, std.low, 1e-9)
        assertEquals(40 / (65 * 0.873), std.sleepLow, 1e-9)
        assertEquals(120 / 65.0, HeartBaseline.ratios(AlertSensitivity.LOW).high, 1e-9) // Apple/Fitbit default
        assertEquals(50 / 65.0, HeartBaseline.ratios(AlertSensitivity.HIGH).low, 1e-9) // bradycardia (ACC/AHA/HRS)
        // Higher sensitivity is always closer to the normal on both sides.
        val (lo, mid, hi) = AlertSensitivity.entries.map { HeartBaseline.ratios(it) }
        assertTrue(lo.high > mid.high && mid.high > hi.high)
        assertTrue(lo.low < mid.low && mid.low < hi.low)
        assertTrue(lo.sleepLow < mid.sleepLow && mid.sleepLow < hi.sleepLow)
    }

    @Test
    fun withoutDataTheFixedDefaultsApply() {
        val l = HeartBaseline.limits(HeartHistory(), today, AlertSensitivity.STANDARD, 40, null)
        assertEquals(120, l.high)
        // Low starts at the safety floors: no false alerts on a fit person's first night.
        assertEquals(35, l.low)
        assertEquals(30, l.sleepLow)
        assertEquals(65, l.restNormal)
        assertTrue(l.learning)
        assertEquals(68, HeartBaseline.limits(HeartHistory(), today, AlertSensitivity.STANDARD, 40, Sex.FEMALE).restNormal)
    }

    @Test
    fun oneDayIsEnoughToBePersonal() {
        val l = HeartBaseline.limits(history(65, 56, days = 1), today, AlertSensitivity.STANDARD, 40, null)
        assertFalse(l.learning)
        assertTrue("high ${l.high}", l.high in 100..105)
        assertTrue("low ${l.low}", l.low in 44..47)
        // A few hours in: half way between the defaults and the personal limit.
        val early = HeartBaseline.limits(history(65, 56, days = 1, perDay = 24), today, AlertSensitivity.STANDARD, 40, null)
        assertTrue(early.learning)
        assertTrue("early ${early.high}", early.high in 106..114)
    }

    @Test
    fun limitsFollowTheWearersNormal() {
        val typical = HeartBaseline.limits(history(65, 56), today, AlertSensitivity.STANDARD, 40, null)
        assertTrue("high ${typical.high}", typical.high in 99..101)
        assertTrue("low ${typical.low}", typical.low in 44..46)
        // A high normal: notified later, but never above the safety bound of 130.
        val high = HeartBaseline.limits(history(85, 74), today, AlertSensitivity.STANDARD, 40, null)
        assertEquals(130, high.high)
        assertEquals(50, high.low)
        // An athlete: notified sooner when high (bound 90), and only far below their low normal.
        val athlete = HeartBaseline.limits(history(48, 42, spread = 3), today, AlertSensitivity.STANDARD, 30, null)
        assertEquals(90, athlete.high)
        assertEquals(35, athlete.low)
        assertEquals(30, athlete.sleepLow)
    }

    @Test
    fun limitsStayOutsideTheWearersOwnUsualRange() {
        // Normal 65, but with frequent readings up to ~105: the limit stays above them (99th percentile + 5).
        val random = Random(3)
        val wide = HeartHistory(
            (0 until 28).map { i ->
                DayStats(
                    today - i,
                    rest = (0 until 150).map {
                        if (random.nextInt(20) ==
                            0
                        ) {
                            random.nextInt(95, 106)
                        } else {
                            random.nextInt(60, 71)
                        }
                    }.groupingBy { it }.eachCount()
                )
            }
        )
        val l = HeartBaseline.limits(wide, today, AlertSensitivity.STANDARD, 40, null)
        assertTrue("high ${l.high}", l.high >= 105)
    }

    @Test
    fun sensitivityMovesTheLimits() {
        val h = history(65, 56)
        val low = HeartBaseline.limits(h, today, AlertSensitivity.LOW, 40, null)
        val std = HeartBaseline.limits(h, today, AlertSensitivity.STANDARD, 40, null)
        val high = HeartBaseline.limits(h, today, AlertSensitivity.HIGH, 40, null)
        assertTrue(low.high > std.high && std.high > high.high)
        assertTrue(low.low < std.low && std.low < high.low)
    }

    @Test
    fun exerciseMaximumLearnsFromHardWorkoutsWithinBounds() {
        val predicted = MaxHr.predicted(40) // 180
        assertEquals(predicted, HeartBaseline.exerciseMax(HeartHistory(), today, 40))
        fun workouts(peak: Int) =
            HeartHistory((0 until 10).map { i -> DayStats(today - i * 3, exercise = (140..peak).associateWith { 3 }) })
        assertEquals(predicted, HeartBaseline.exerciseMax(workouts(170), today, 40))
        assertEquals(188, HeartBaseline.exerciseMax(workouts(188), today, 40))
        assertEquals(predicted + 15, HeartBaseline.exerciseMax(workouts(215), today, 40))
        // Today's own peak (an overexertion now) never raises today's limit, nor do one or two days.
        assertEquals(
            predicted,
            HeartBaseline.exerciseMax(
                HeartHistory(
                    listOf(
                        DayStats(
                            today,
                            exercise = (140..200).associateWith {
                                3
                            }
                        )
                    )
                ),
                today,
                40
            )
        )
        assertEquals(predicted, HeartBaseline.exerciseMax(HeartHistory(workouts(200).days.takeLast(2)), today, 40))
    }

    @Test
    fun raisedNightsGiveATrendNotice() {
        val random = Random(5)
        fun night(center: Int) = (0 until 40).map { center + random.nextInt(-2, 3) }.groupingBy { it }.eachCount()
        val base = (5 until 33).map { DayStats(today - it, sleep = night(55)) }
        fun with(vararg raised: Int) = HeartHistory(
            base + (0 until 5).map { i -> DayStats(today - i, sleep = night(if (i in raised) 64 else 55)) }
        )
        assertNull(HeartBaseline.restingTrend(with(0, 1), today))
        // 3 of 5 nights up, the latest included.
        val trend = HeartBaseline.restingTrend(with(0, 1, 3), today)
        assertNotNull(trend)
        assertEquals(3, trend!!.nightsAbove)
        assertEquals(55, trend.usual)
        // Not while the latest night is back to normal.
        assertNull(HeartBaseline.restingTrend(with(1, 2, 3), today))
        // The raised nights are not learnt from.
        val marked = with(0, 1, 3).markUnusual(trend.days)
        assertTrue(marked.days.filter { it.day in trend.days }.all { it.unusual })
    }

    @Test
    fun aRaisedWeekDoesNotMoveTheNormal() {
        val h = history(65, 56).let { h -> h.markUnusual(h.days.takeLast(4).map { it.day }) }
        val sick = h.copy(days = h.days.map { if (it.unusual) it.copy(rest = mapOf(90 to 150)) else it })
        assertTrue(HeartBaseline.limits(sick, today, AlertSensitivity.STANDARD, 40, null).restNormal in 64..66)
    }

    @Test
    fun sleepMinutesBelongToTheNightThatEnds() {
        val utc = ZoneOffset.UTC
        val day = 20_000L
        val lateEvening = (day * 86_400_000L) + 23 * 3_600_000L
        val h = HeartHistory()
            .record(HrMinute(lateEvening, 50, 48, 52, activity = HrContext.SLEEP, resting = false), utc)
            .record(HrMinute(lateEvening, 70, 68, 72), utc)
        assertEquals(mapOf(70 to 1), h.days.single { it.day == day }.rest)
        assertEquals(mapOf(50 to 1), h.days.single { it.day == day + 1 }.sleep)
        // Moving about is not part of any normal.
        assertEquals(h, h.record(HrMinute(lateEvening, 110, 100, 120, resting = false, activity = HrContext.ACTIVE), utc))
    }

    @Test
    fun karvonenZonesStartFromTheRestingRate() {
        assertEquals(listOf(124, 136, 148, 160, 172), HeartBaseline.zones(184, 64))
    }
}
