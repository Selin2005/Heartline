// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.pots

import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrthostaticTest {
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun m(t: Long, bpm: Int, ctx: HrContext) = HrMinute(t * minute, bpm, bpm, bpm, activity = ctx)

    /** [restBpm] for 6 minutes at rest, then [afterBpm] for 10 minutes in [ctx], starting at [startMin]. */
    private fun day(startMin: Long, restBpm: Int, afterBpm: Int, ctx: HrContext = HrContext.ACTIVE) =
        (0 until 6).map { m(startMin + it, restBpm, HrContext.REST) } +
            (6 until 16).map { m(startMin + it, afterBpm, ctx) }

    private val detector = OrthostaticEventDetector()

    @Test fun risesOfThirtyAreFound() {
        val e = detector.detect(day(0, 70, 102)).single()
        assertEquals(32, e.riseBpm)
    }

    @Test fun riseOfTwentyNineIsNot() {
        assertTrue(detector.detect(day(0, 70, 99)).isEmpty())
    }

    @Test fun exerciseIsNotCounted() {
        assertTrue(detector.detect(day(0, 70, 130, HrContext.EXERCISE)).isEmpty())
    }

    @Test fun needsRestBefore() {
        val noRest = (0 until 16).map { m(it.toLong(), if (it < 6) 70 else 110, HrContext.ACTIVE) }
        assertTrue(detector.detect(noRest).isEmpty())
    }

    @Test fun oneSpikeIsNotSustained() {
        val data = day(0, 70, 75).toMutableList()
        data[8] = m(8, 120, HrContext.ACTIVE)
        assertTrue(detector.detect(data).isEmpty())
    }

    private val rules = PotsPatternRules()

    private fun events(vararg daysAgo: Int, now: Long) =
        daysAgo.map { OrthostaticEvent(now - it * day, 70, 105) }

    @Test fun threeDaysRaiseNotice() {
        val now = 100 * day
        assertNotNull(rules.evaluate(events(1, 3, 5, now = now), now, null))
    }

    @Test fun oneDayIsNotAPattern() {
        val now = 100 * day
        assertNull(rules.evaluate(events(1, 1, 1, now = now), now, null))
    }

    @Test fun cooldownApplies() {
        val now = 100 * day
        assertNull(rules.evaluate(events(1, 3, 5, now = now), now, now - 5 * day))
    }

    @Test fun symptomsMakeItSpecificOnly() {
        val now = 100 * day
        val ev = events(1, 3, 5, now = now)
        assertTrue(rules.evaluate(ev, now, null, listOf(ev[0].startMs + 5 * minute))!!.withSymptoms)
        assertEquals(false, rules.evaluate(ev, now, null)!!.withSymptoms)
    }
}
