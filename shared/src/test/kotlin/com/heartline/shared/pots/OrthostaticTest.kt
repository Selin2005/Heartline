// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.pots

import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.MinuteAggregator
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrthostaticTest {
    private val minute = 60_000L
    private val day = 86_400_000L
    private val utc = ZoneOffset.UTC
    private val detector = OrthostaticEventDetector()

    private fun m(min: Long, bpm: Int, ctx: HrContext, steps: Int? = null) =
        HrMinute(min * minute, bpm, bpm, bpm, activity = ctx, steps = steps)

    /**
     * Passive heart rate as it really arrives: still readings every 3 minutes for 15 minutes, then
     * up at [up] with readings at +0, +3 and +6 minutes in [ctx].
     */
    private fun getUp(up: Long, rest: Int, after: List<Int>, ctx: HrContext = HrContext.ACTIVE, steps: Int? = 40): List<HrMinute> {
        val still = listOf(15 to rest, 12 to rest, 9 to rest + 2, 6 to rest - 1, 3 to rest)
            .map { (ago, bpm) -> m(up - ago, bpm, HrContext.REST) }
        return still + after.mapIndexed { i, bpm -> m(up + 3L * i, bpm, ctx, steps) }
    }

    private fun detect(minutes: List<HrMinute>) = detector.detect(minutes, nowMs = Long.MAX_VALUE)

    @Test fun aHeldRiseOfThirtyIsFound() {
        val e = detect(getUp(100, 70, listOf(98, 104, 101))).single()
        assertEquals(100 * minute, e.startMs)
        assertEquals(70, e.restBpm)
        // The second highest: 101, not the peak of 104.
        assertEquals(101, e.peakBpm)
        assertEquals(31, e.riseBpm)
    }

    @Test fun twentyNineIsNotEnough() {
        assertTrue(detect(getUp(100, 70, listOf(95, 99, 99))).isEmpty())
    }

    @Test fun oneSpikeIsNotHeld() {
        assertTrue(detect(getUp(100, 70, listOf(120, 84, 82))).isEmpty())
    }

    @Test fun oneReadingAfterIsNotEnough() {
        assertTrue(detect(getUp(100, 70, listOf(110))).isEmpty())
    }

    @Test fun aWorkoutIsNotGettingUp() {
        val workout = getUp(100, 70, listOf(110, 130)) + m(106, 140, HrContext.EXERCISE)
        assertTrue(detect(workout).isEmpty())
    }

    @Test fun briskWalkingOrStairsDontCount() {
        assertTrue(detect(getUp(100, 70, listOf(105, 110, 112), steps = 115)).isEmpty())
        // Without step counts (an older watch) the context alone decides.
        assertEquals(1, detect(getUp(100, 70, listOf(105, 110, 112), steps = null)).size)
    }

    @Test fun needsStillMinutesBefore() {
        // Moving about ten minutes before: the "rest" rate may still be coming down.
        val moved = getUp(100, 70, listOf(104, 106)).map {
            if (it.minuteStartMs ==
                91 * minute
            ) {
                it.copy(activity = HrContext.ACTIVE)
            } else {
                it
            }
        }
        assertTrue(detect(moved).isEmpty())
        // Still for only three minutes.
        val short =
            listOf(m(97, 70, HrContext.REST), m(99, 70, HrContext.REST), m(100, 104, HrContext.ACTIVE), m(103, 106, HrContext.ACTIVE))
        assertTrue(detect(short).isEmpty())
        // Waking up is not getting up from rest.
        val asleep = getUp(100, 55, listOf(90, 92)).map { if (it.activity == HrContext.REST) it.copy(activity = HrContext.SLEEP) else it }
        assertTrue(detect(asleep).isEmpty())
    }

    @Test fun aLongGapBeforeIsUnknown() {
        val gap = listOf(m(70, 70, HrContext.REST), m(75, 70, HrContext.REST), m(100, 104, HrContext.ACTIVE), m(103, 106, HrContext.ACTIVE))
        assertTrue(detect(gap).isEmpty())
    }

    @Test fun onlyFinishedWindowsAreJudged() {
        val data = getUp(100, 70, listOf(104, 106))
        assertTrue(detector.detect(data, nowMs = 105 * minute).isEmpty())
        assertEquals(1, detector.detect(data, nowMs = 110 * minute).size)
    }

    @Test fun oneGettingUpIsOneRise() {
        // Moving minutes all through the window: still one rise, not one per minute.
        val data = getUp(100, 70, listOf(104, 106, 108, 110))
        assertEquals(1, detect(data).size)
    }

    @Test fun teenagersNeedForty() {
        assertEquals(40, OrthostaticEventDetector.minRiseFor(16))
        assertEquals(30, OrthostaticEventDetector.minRiseFor(25))
        assertEquals(30, OrthostaticEventDetector.minRiseFor(null))
        val teen = OrthostaticEventDetector(minRiseBpm = OrthostaticEventDetector.minRiseFor(16))
        assertTrue(teen.detect(getUp(100, 70, listOf(104, 106)), Long.MAX_VALUE).isEmpty())
        assertEquals(1, teen.detect(getUp(100, 70, listOf(112, 114)), Long.MAX_VALUE).size)
    }

    @Test fun stepsTravelWithTheMinute() {
        val samples = listOf(com.heartline.shared.hr.HrSample(5 * minute + 10, 80, emptyList()))
        val minutes = MinuteAggregator.aggregate(samples, steps = { 42 })
        assertEquals(42, minutes.single().steps)
        assertEquals(50, MinuteAggregator.merge(minutes.single(), minutes.single().copy(steps = 50)).steps)
        assertEquals(42, MinuteAggregator.merge(minutes.single(), minutes.single().copy(steps = null)).steps)
    }

    private val rules = PosturalPatternRules()

    private fun rises(now: Long, vararg daysAgo: Double) = daysAgo.map { OrthostaticEvent(now - (it * day).toLong(), 70, 104) }

    @Test fun threeDaysMakeAPattern() {
        val now = 100 * day + 12 * 3_600_000L
        val p = rules.evaluate(rises(now, 1.0, 3.0, 5.0), now, utc)
        assertNotNull(p)
        assertEquals(3, p!!.events)
        assertEquals(3, p.days)
        assertEquals(34, p.medianRiseBpm)
        assertFalse(p.withSymptoms)
    }

    @Test fun oneBusyDayIsNotAPattern() {
        val now = 100 * day + 12 * 3_600_000L
        assertNull(rules.evaluate(rises(now, 1.0, 1.1, 1.2, 1.3), now, utc))
        assertNull(rules.evaluate(rises(now, 1.0, 3.0), now, utc))
    }

    @Test fun oldRisesDropOut() {
        val now = 100 * day + 12 * 3_600_000L
        assertNull(rules.evaluate(rises(now, 1.0, 3.0, 15.0), now, utc))
    }

    @Test fun symptomsDescribeButDontCount() {
        val now = 100 * day + 12 * 3_600_000L
        val events = rises(now, 1.0, 3.0, 5.0)
        assertTrue(rules.evaluate(events, now, utc, listOf(events[1].startMs + 10 * minute))!!.withSymptoms)
        assertFalse(rules.evaluate(events, now, utc, listOf(events[1].startMs + 2 * 3_600_000L))!!.withSymptoms)
        assertNull(rules.evaluate(events.take(2), now, utc, listOf(events[0].startMs)))
    }
}
