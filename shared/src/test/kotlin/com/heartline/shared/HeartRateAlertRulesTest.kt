// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.MaxHr
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.irn.HeartRateAlertRules
import com.heartline.shared.sync.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateAlertRulesTest {
    private var ids = 0
    private val id = { "id-${ids++}" }
    private val rules = HeartRateAlertRules()

    private fun minute(at: Int, bpm: Int, activity: HrContext = HrContext.REST) =
        HrMinute(at * 60_000L, bpm, bpm - 3, bpm + 3, resting = activity == HrContext.REST, activity = activity)

    @Test
    fun exerciseAtAHighButNormalRateNeverAlerts() {
        val workout = (0 until 45).map { minute(it, 165, HrContext.EXERCISE) }
        assertTrue(rules.evaluate(workout, MonitorSettings(), 35, emptyMap(), id).isEmpty())
    }

    @Test
    fun exerciseAboveTheAgeMaximumForThreeMinutesAlerts() {
        val max = MaxHr.predicted(40) // 180
        assertEquals(180, max)
        val workout = (0 until 20).map { minute(it, 160, HrContext.EXERCISE) } + (20 until 23).map { minute(it, 186, HrContext.EXERCISE) }
        val alerts = rules.evaluate(workout, MonitorSettings(), 40, emptyMap(), id)
        assertEquals(listOf(AlertKind.HIGH_HEART_RATE), alerts.map { it.kind })
        assertEquals(HrContext.EXERCISE, alerts.single().context)
        assertEquals(180, alerts.single().threshold)
        // Two minutes over the limit is a short peak, not a held rate.
        assertTrue(rules.evaluate(workout.dropLast(1), MonitorSettings(), 40, emptyMap(), id).isEmpty())
    }

    @Test
    fun userExerciseLimitAndToggleAreRespected() {
        val workout = (0 until 5).map { minute(it, 172, HrContext.EXERCISE) }
        assertTrue(rules.evaluate(workout, MonitorSettings(), 40, emptyMap(), id).isEmpty())
        assertEquals(1, rules.evaluate(workout, MonitorSettings(exerciseMaxBpm = 170), 40, emptyMap(), id).size)
        assertTrue(
            rules.evaluate(workout, MonitorSettings(exerciseMaxBpm = 170, exerciseAlertEnabled = false), 40, emptyMap(), id).isEmpty()
        )
    }

    @Test
    fun sparseRestingReadingsStillAlert() {
        // Passive heart rate at rest arrives every few minutes, not every minute.
        val sparse = listOf(0, 6, 12, 18).map { minute(it, 128) }
        val alerts = rules.evaluate(sparse, MonitorSettings(), 40, emptyMap(), id)
        assertEquals(listOf(AlertKind.HIGH_HEART_RATE), alerts.map { it.kind })
        assertEquals(HrContext.REST, alerts.single().context)
        // One reading under the limit in the span means it was not held.
        val dip = listOf(minute(0, 128), minute(6, 110), minute(12, 128), minute(18, 128))
        assertTrue(rules.evaluate(dip, MonitorSettings(), 40, emptyMap(), id).isEmpty())
    }

    @Test
    fun recoveryRightAfterExerciseDoesNotAlert() {
        val minutes = (0 until 30).map { minute(it, 160, HrContext.EXERCISE) } + (30 until 42).map { minute(it, 125) }
        assertTrue(rules.evaluate(minutes, MonitorSettings(), 40, emptyMap(), id).isEmpty())
        // Still high long after the workout ended: the rest rule applies again.
        val later = minutes + (42 until 60).map { minute(it, 125) }
        assertEquals(listOf(AlertKind.HIGH_HEART_RATE), rules.evaluate(later, MonitorSettings(), 40, emptyMap(), id).map { it.kind })
    }

    @Test
    fun lowRateInSleepUsesTheSleepLimit() {
        val night = (0 until 30).map { minute(it, 38, HrContext.SLEEP) }
        assertTrue(rules.evaluate(night, MonitorSettings(lowBpm = 40), 40, emptyMap(), id).isEmpty())
        val lower = (0 until 30).map { minute(it, 33, HrContext.SLEEP) }
        val alerts = rules.evaluate(lower, MonitorSettings(lowBpm = 40), 40, emptyMap(), id)
        assertEquals(listOf(AlertKind.LOW_HEART_RATE), alerts.map { it.kind })
        assertEquals(35, alerts.single().threshold)
        assertEquals(HrContext.SLEEP, alerts.single().context)
    }

    @Test
    fun lowRateIsNeverJudgedDuringExercise() {
        val odd = (0 until 20).map { minute(it, 30, HrContext.EXERCISE) }
        assertTrue(rules.evaluate(odd, MonitorSettings(), 40, emptyMap(), id).isEmpty())
    }

    @Test
    fun separateTogglesForHighAndLow() {
        val high = (0 until 12).map { minute(it, 130) }
        assertTrue(rules.evaluate(high, MonitorSettings(highAlertEnabled = false), 40, emptyMap(), id).isEmpty())
        val low = (0 until 12).map { minute(it, 35) }
        assertTrue(rules.evaluate(low, MonitorSettings(lowAlertEnabled = false), 40, emptyMap(), id).isEmpty())
    }

    @Test
    fun exerciseAndRestAlertsHaveSeparateCooldowns() {
        val rest = HeartRateAlertRules.key(AlertKind.HIGH_HEART_RATE, HrContext.REST)
        val workout = (0 until 5).map { minute(it, 200, HrContext.EXERCISE) }
        assertEquals(1, rules.evaluate(workout, MonitorSettings(), 40, mapOf(rest to 0L), id).size)
    }

    @Test
    fun olderSettingsAndMinutesStillDecode() {
        val settings = Protocol.json.decodeFromString(MonitorSettings.serializer(), """{"highBpm":110,"updatedAtMs":5}""")
        assertEquals(110, settings.highBpm)
        assertTrue(settings.exerciseAlertEnabled)
        assertEquals(35, settings.sleepLowLimit)
        val old = Protocol.json.decodeFromString(
            HrMinute.serializer(),
            """{"minuteStartMs":0,"avgBpm":90,"minBpm":80,"maxBpm":99,"resting":false}"""
        )
        assertEquals(HrContext.ACTIVE, old.activity)
    }
}
