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

    /** A probe at [up] (mid-minute) with slices around +0.5, +3 and +6.5 minutes. */
    private fun probe(up: Long, vararg slices: Int, steps: Int? = 50) = StandProbe(
        up,
        slices.flatMapIndexed { i, bpm ->
            val at = up + listOf(30_000L, 180_000L, 390_000L)[i]
            (0 until 8).map { k -> StandReading(at + k * 5_000L, bpm + (k % 3) - 1) }
        },
        steps
    )

    /** Still passive readings every 3 minutes before [up] only: the probe alone sees the rise. */
    private fun stillBefore(up: Long, rest: Int = 70) = listOf(15, 12, 9, 6, 3).map { m(up - it, rest, HrContext.REST) }

    @Test fun aProbeSeesWhatSparseReadingsMiss() {
        val up = 100 * minute + 20_000
        val minutes = stillBefore(100) + m(104, 88, HrContext.ACTIVE)
        // One passive reading after: the passive check can't judge it.
        assertTrue(detect(minutes).isEmpty())
        val e = detector.detect(minutes, Long.MAX_VALUE, listOf(probe(up, 96, 104, 106))).single()
        assertEquals(RiseSource.PROBE, e.source)
        assertEquals(70, e.restBpm)
        assertEquals(104, e.peakBpm)
        // Minute 85 started 15 min 20 s before: outside the 15 minutes.
        assertEquals(4, e.before.size)
        assertEquals(24, e.after.size)
    }

    @Test fun aProbeNeedsTwoSlicesAndAHeldRise() {
        val up = 100 * minute + 20_000
        val minutes = stillBefore(100)
        assertTrue(detector.detect(minutes, Long.MAX_VALUE, listOf(probe(up, 110))).isEmpty())
        assertTrue(detector.detect(minutes, Long.MAX_VALUE, listOf(probe(up, 120, 85, 84))).isEmpty())
        assertEquals(1, detector.detect(minutes, Long.MAX_VALUE, listOf(probe(up, 102, 101))).size)
    }

    @Test fun aProbeNeedsRestBeforeAndNoBriskWalking() {
        val up = 100 * minute + 20_000
        assertTrue(detector.detect(listOf(m(97, 70, HrContext.REST)), Long.MAX_VALUE, listOf(probe(up, 104, 106))).isEmpty())
        assertTrue(detector.detect(stillBefore(100), Long.MAX_VALUE, listOf(probe(up, 104, 106, steps = 112))).isEmpty())
        val workout = stillBefore(100) + m(103, 130, HrContext.EXERCISE)
        assertTrue(detector.detect(workout, Long.MAX_VALUE, listOf(probe(up, 104, 106))).isEmpty())
    }

    @Test fun aProbedGettingUpIsCountedOnce() {
        // Both the probe and the passive readings saw the same getting-up: one rise, the probe's.
        val events = detector.detect(getUp(100, 70, listOf(104, 106, 105)), Long.MAX_VALUE, listOf(probe(100 * minute + 5_000, 103, 105)))
        assertEquals(listOf(RiseSource.PROBE), events.map { it.source })
    }

    @Test fun slicesAreToldApartByTheirGaps() {
        val slices = probe(0, 90, 100, 110).sliceMedians()
        assertEquals(listOf(90, 100, 110), slices.map { it.bpm })
    }

    @Test fun theReportExistsOnlyAfterAPattern() {
        val now = 100 * day
        val pattern = PosturalPattern(3, 3, 70, 104, 34, false)
        assertEquals(PosturalStatus.NONE, PosturalStatus.of(null, null, now))
        assertEquals(PosturalStatus.PATTERN, PosturalStatus.of(pattern, null, now))
        assertEquals(PosturalStatus.EASING, PosturalStatus.of(null, now - 20 * day, now))
        assertEquals(PosturalStatus.NONE, PosturalStatus.of(null, now - 61 * day, now))
    }

    private val on = com.heartline.shared.hr.MonitorSettings(sleepStartMinute = 23 * 60, sleepEndMinute = 7 * 60)
    private val nine = 100 * day + 9 * 3_600_000L

    private fun decide(
        now: Long = nine,
        lastMotion: Long? = nine - 20 * minute,
        probes: List<Long> = emptyList(),
        ctx: HrContext? = null,
        settings: com.heartline.shared.hr.MonitorSettings = on
    ) = StandProbePolicy.decide(now, lastMotion, probes, ctx, settings, utc)

    @Test fun aProbeStartsAfterAStillStretch() {
        assertTrue(decide().start)
        assertTrue(decide(lastMotion = null).start)
        assertFalse(decide(lastMotion = nine - 4 * minute).start)
    }

    @Test fun noProbeAsleepInAWorkoutOrAtNight() {
        assertFalse(decide(ctx = HrContext.SLEEP).start)
        assertFalse(decide(ctx = HrContext.EXERCISE).start)
        assertFalse(decide(now = 100 * day + 3 * 3_600_000L, lastMotion = null).start)
    }

    @Test fun probesAreRationed() {
        assertFalse(decide(probes = listOf(nine - 30 * minute)).start)
        assertTrue(decide(probes = listOf(nine - 50 * minute)).start)
        // Six already today, however long ago.
        assertFalse(decide(probes = (1..6).map { 100 * day + 7 * 3_600_000L + it * minute }).start)
        // Yesterday's don't count.
        assertTrue(decide(probes = (1..6).map { 99 * day + 12 * 3_600_000L + it * minute }).start)
    }

    @Test fun noProbeWithoutHeartMonitoringOrWithAPacemaker() {
        assertFalse(decide(settings = on.withMonitoring(false)).start)
        val paced = on.copy(health = com.heartline.shared.hr.HealthContext(heartDevice = com.heartline.shared.hr.Answer.YES))
        assertFalse(decide(settings = paced).start)
    }

    @Test fun aProbeKeepsGoodReadingsAsFiveSecondMedians() {
        val samples = (0 until 20).map { com.heartline.shared.hr.HrSample(1_000_000L + it * 1_000L, 90 + it % 5, emptyList()) } +
            com.heartline.shared.hr.HrSample(1_000_500L, 160, emptyList(), reliable = false) +
            com.heartline.shared.hr.HrSample(1_001_500L, 150, emptyList(), onBody = false)
        val probe = StandProbe.of(999_000L, samples, 30)
        assertEquals(4, probe.readings.size)
        assertTrue(probe.readings.all { it.bpm in 90..94 })
        assertEquals(30, probe.stepsPerMinute)
    }

    @Test fun withoutTheMotionSensorStepsAfterStillnessAreAGettingUp() {
        val now = 100 * day
        fun timeline(vararg spans: com.heartline.shared.hr.StepSpan) = com.heartline.shared.hr.ActivityTimeline(steps = spans.toList())
        val walk = com.heartline.shared.hr.StepSpan(now - 60_000L, now, 60)
        val earlier = com.heartline.shared.hr.StepSpan(now - 40 * minute, now - 39 * minute, 50)
        assertEquals(now - 60_000L to now - 39 * minute, StandProbePolicy.gettingUpFromSteps(timeline(earlier, walk), now))
        // Steps three minutes before: not still.
        val recent = com.heartline.shared.hr.StepSpan(now - 4 * minute, now - 3 * minute, 40)
        assertNull(StandProbePolicy.gettingUpFromSteps(timeline(recent, walk), now))
        // Too late to measure the first minutes.
        assertNull(StandProbePolicy.gettingUpFromSteps(timeline(walk), now + 5 * minute))
        // A few steps are not walking.
        assertNull(StandProbePolicy.gettingUpFromSteps(timeline(com.heartline.shared.hr.StepSpan(now - 60_000L, now, 6)), now))
    }

    @Test fun probesTravelToThePhone() {
        val batch = com.heartline.shared.hr.HrBatch("b", emptyList(), stands = listOf(probe(1_000L, 90, 100)))
        val json = com.heartline.shared.sync.Protocol.json.encodeToString(com.heartline.shared.hr.HrBatch.serializer(), batch)
        assertEquals(batch, com.heartline.shared.sync.Protocol.json.decodeFromString(com.heartline.shared.hr.HrBatch.serializer(), json))
        // An older phone's batch has none.
        assertTrue(
            com.heartline.shared.sync.Protocol.json.decodeFromString(
                com.heartline.shared.hr.HrBatch.serializer(),
                """{"id":"x","minutes":[]}"""
            ).stands.isEmpty()
        )
    }
}
