// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.hr.HrContext
import com.heartline.shared.vitals.Spo2Sample
import com.heartline.shared.vitals.Spo2Schedule
import com.heartline.shared.vitals.Spo2Schedule.Decision
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Spo2ScheduleTest {
    private val min = 60_000L
    private val hour = 60 * min

    private fun decide(
        now: Long = 10 * hour,
        last: Long = 0,
        every: Int = 60,
        asleep: Boolean = false,
        inSleep: Boolean = true,
        battery: Int = 80,
        steps: Double = 0.0,
        active: Boolean = false,
        armMoved: Boolean = false,
        retries: Int = 0
    ) = Spo2Schedule.decide(now, last, every, asleep, inSleep, battery, steps, active, armMoved, retries)

    @Test
    fun dueOncePerIntervalWithAFewMinutesOfSlack() {
        assertEquals(Decision.Measure, decide(now = 10 * hour, last = 9 * hour + 4 * min))
        assertEquals(Decision.Skip("not due"), decide(now = 10 * hour, last = 9 * hour + 6 * min))
        // Every 30 minutes (the breathing mode): due after 25.
        assertEquals(Decision.Measure, decide(now = 10 * hour, last = 10 * hour - 26 * min, every = 30))
        assertEquals(Decision.Skip("not due"), decide(now = 10 * hour, last = 10 * hour - 20 * min, every = 30))
    }

    @Test
    fun sleepBatteryAndMovement() {
        assertEquals(Decision.Skip("off in sleep"), decide(asleep = true, inSleep = false))
        assertEquals(Decision.Measure, decide(asleep = true, battery = 5))
        assertEquals(Decision.Skip("battery 10 %"), decide(battery = 10))
        assertEquals(Decision.PutOff(10, "steps"), decide(steps = 6.0))
        // Asleep, turning over is not walking.
        assertEquals(Decision.Measure, decide(asleep = true, steps = 6.0))
        assertEquals(Decision.PutOff(10, "arm moving"), decide(armMoved = true))
        assertEquals(Decision.PutOff(10, "steps"), decide(active = true))
    }

    @Test
    fun putOffsAreCountedPerSlotAndStartAgainInTheNext() {
        // The old rule never reset its count: after two put-offs every later hour got no retry.
        assertEquals(Decision.Skip("arm moving, no more tries this slot"), decide(armMoved = true, retries = Spo2Schedule.MAX_RETRIES))
        val slot = Spo2Schedule.slot(10 * hour + 5 * min, 60)
        assertEquals(4, Spo2Schedule.retriesNow(slot, 4, 10 * hour + 50 * min, 60))
        assertEquals(0, Spo2Schedule.retriesNow(slot, 4, 11 * hour + 1 * min, 60))
    }

    @Test
    fun onlyAFreshPassiveHeartRateIsComparedWithTheSensors() {
        // 05:55 on 10/07: 100 % rejected against a passive rate of 58 from minutes before (sensor 91).
        assertEquals(58, Spo2Schedule.recentBpm(58, 10 * hour - 4 * min, 10 * hour))
        assertNull(Spo2Schedule.recentBpm(58, 10 * hour - 20 * min, 10 * hour))
        assertNull(Spo2Schedule.recentBpm(null, 10 * hour, 10 * hour))
    }

    @Test
    fun aLowReadingCountsOnlyWhenItsRecheckAgrees() {
        fun s(p: Int, t: Long) = Spo2Sample(t, p, HrContext.REST, confirmation = t > 0)
        val low = { p: Int -> p < 92 }
        // 80 % with no re-check result (the log, 10/06 09:04 and 10/07 11:34): nothing is kept.
        assertEquals(emptyList<Spo2Sample>(), Spo2Schedule.confirmed(s(80, 0), emptyList(), low))
        // A normal re-check stands alone; a low one confirms.
        assertEquals(listOf(s(95, 2)), Spo2Schedule.confirmed(s(80, 0), listOf(s(95, 2)), low))
        assertEquals(listOf(s(85, 0), s(86, 2)), Spo2Schedule.confirmed(s(85, 0), listOf(s(86, 2)), low))
        assertEquals(listOf(s(96, 0)), Spo2Schedule.confirmed(s(96, 0), emptyList(), low))
    }

    @Test
    fun waitingForAStillArmMeasuresOnceItHasBeenStillLongEnough() {
        assertEquals(Spo2Schedule.Wait.KEEP_WAITING, Spo2Schedule.stillWait(stillForMs = 3_000, waitedMs = 30_000))
        assertEquals(Spo2Schedule.Wait.MEASURE, Spo2Schedule.stillWait(Spo2Schedule.STILL_NEEDED_MS, 30_000))
        // Still enough wins even at the end of the wait.
        assertEquals(Spo2Schedule.Wait.MEASURE, Spo2Schedule.stillWait(Spo2Schedule.STILL_NEEDED_MS, Spo2Schedule.STILL_WAIT_MS))
        assertEquals(Spo2Schedule.Wait.GIVE_UP, Spo2Schedule.stillWait(2_000, Spo2Schedule.STILL_WAIT_MS))
    }

    @Test
    fun aMeasurementStoppedByMovementStartsOnceMoreWithinTheWait() {
        assertTrue(Spo2Schedule.tryAgain(starts = 1, waitedMs = 40_000))
        // Two starts at most, and only while the two minutes last.
        assertFalse(Spo2Schedule.tryAgain(starts = 2, waitedMs = 40_000))
        assertFalse(Spo2Schedule.tryAgain(starts = 1, waitedMs = Spo2Schedule.STILL_WAIT_MS))
    }

    /** One simulated day awake (08:00–22:00): readings, and tries put off for movement. */
    private data class Day(val readings: Int, val putOffs: Int)

    private fun day(armMovedAtTry: () -> Boolean): Day {
        var readings = 0
        var putOffs = 0
        var last = 0L
        var slot = -1L
        var retries = 0
        val queue = generateSequence(8 * hour) { it + 30 * min }.takeWhile { it < 22 * hour }.toMutableList()
        while (queue.isNotEmpty()) {
            val now = queue.removeAt(0)
            retries = Spo2Schedule.retriesNow(slot, retries, now, 60)
            val d = decide(now = now, last = last, retries = retries)
            if (d != Decision.Measure) continue
            when (val after = decide(now = now, last = last, armMoved = armMovedAtTry(), retries = retries)) {
                Decision.Measure -> {
                    last = now
                    readings++
                }
                is Decision.PutOff -> {
                    putOffs++
                    slot = Spo2Schedule.slot(now, 60)
                    retries++
                    queue += now + after.retryMinutes * min
                    queue.sort()
                }
                is Decision.Skip -> Unit
            }
        }
        return Day(readings, putOffs)
    }

    @Test
    fun aBusyDayGetsItsReadingsWithFarFewerPointlessTries() {
        // A busy arm: in each 5 s it moves with chance 0.6 (typing, a phone in hand).
        val random = Random(7)
        fun still5s() = random.nextDouble() >= 0.6

        // Waiting: up to 2 minutes for 15 s (three 5 s stretches) of still arm.
        val waiting = day {
            var run = 0
            val found = (1..(Spo2Schedule.STILL_WAIT_MS / 5_000).toInt()).any {
                run = if (still5s()) run + 1 else 0
                run * 5_000L >= Spo2Schedule.STILL_NEEDED_MS
            }
            !found
        }
        // The rule before: one 5 s look, then put off 10 minutes.
        val looking = day { !still5s() }

        assertTrue("$waiting vs $looking", waiting.readings >= looking.readings)
        // 15 s of stillness is harder to find than 8 s, yet still half the put-offs or fewer
        // (the second start after a movement isn't modelled here).
        assertTrue("$waiting vs $looking", waiting.putOffs * 2 <= looking.putOffs)
    }
}
