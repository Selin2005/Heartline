// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HeartLimits
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.irn.HeartRateAlertRules
import com.heartline.shared.sync.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateAlertRulesTest {
    private var ids = 0
    private val id = { "id-${ids++}" }
    private val rules = HeartRateAlertRules()
    private val on = MonitorSettings()

    private fun limits(high: Int = 120, low: Int = 40, sleepLow: Int = 35, exerciseMax: Int = 180) =
        HeartLimits(restNormal = 65, sleepNormal = 57, high = high, low = low, sleepLow = sleepLow, exerciseMax = exerciseMax)

    private fun minute(at: Int, bpm: Int, activity: HrContext = HrContext.REST) =
        HrMinute(at * 60_000L, bpm, bpm - 3, bpm + 3, resting = activity == HrContext.REST, activity = activity)

    @Test
    fun exerciseAtAHighButNormalRateNeverAlerts() {
        val workout = (0 until 45).map { minute(it, 165, HrContext.EXERCISE) }
        assertTrue(rules.evaluate(workout, on, limits(exerciseMax = 183), emptyMap(), id).isEmpty())
    }

    @Test
    fun exerciseAboveThePersonalMaximumForThreeMinutesAlerts() {
        val workout = (0 until 20).map { minute(it, 160, HrContext.EXERCISE) } + (20 until 23).map { minute(it, 186, HrContext.EXERCISE) }
        val alerts = rules.evaluate(workout, on, limits(exerciseMax = 180), emptyMap(), id)
        assertEquals(listOf(AlertKind.HIGH_HEART_RATE), alerts.map { it.kind })
        assertEquals(HrContext.EXERCISE, alerts.single().context)
        assertEquals(180, alerts.single().threshold)
        // Two minutes over the limit is a short peak, not a held rate.
        assertTrue(rules.evaluate(workout.dropLast(1), on, limits(exerciseMax = 180), emptyMap(), id).isEmpty())
    }

    @Test
    fun sparseRestingReadingsStillAlertAndNameTheNormal() {
        // Passive heart rate at rest arrives every few minutes, not every minute.
        val sparse = listOf(0, 6, 12, 18).map { minute(it, 108) }
        val alerts = rules.evaluate(sparse, on, limits(high = 101), emptyMap(), id)
        assertEquals(listOf(AlertKind.HIGH_HEART_RATE), alerts.map { it.kind })
        assertEquals(HrContext.REST, alerts.single().context)
        assertEquals(101, alerts.single().threshold)
        assertEquals(65, alerts.single().normal)
        // One reading under the limit in the span means it was not held.
        val dip = listOf(minute(0, 108), minute(6, 95), minute(12, 108), minute(18, 108))
        assertTrue(rules.evaluate(dip, on, limits(high = 101), emptyMap(), id).isEmpty())
    }

    @Test
    fun recoveryRightAfterExerciseDoesNotAlert() {
        val minutes = (0 until 30).map { minute(it, 160, HrContext.EXERCISE) } + (30 until 42).map { minute(it, 125) }
        assertTrue(rules.evaluate(minutes, on, limits(), emptyMap(), id).isEmpty())
        val later = minutes + (42 until 60).map { minute(it, 125) }
        assertEquals(listOf(AlertKind.HIGH_HEART_RATE), rules.evaluate(later, on, limits(), emptyMap(), id).map { it.kind })
    }

    @Test
    fun lowRateInSleepUsesTheSleepLimit() {
        val night = (0 until 30).map { minute(it, 38, HrContext.SLEEP) }
        assertTrue(rules.evaluate(night, on, limits(low = 40, sleepLow = 35), emptyMap(), id).isEmpty())
        val lower = (0 until 30).map { minute(it, 33, HrContext.SLEEP) }
        val alerts = rules.evaluate(lower, on, limits(low = 40, sleepLow = 35), emptyMap(), id)
        assertEquals(listOf(AlertKind.LOW_HEART_RATE), alerts.map { it.kind })
        assertEquals(35, alerts.single().threshold)
        assertEquals(57, alerts.single().normal)
        assertEquals(HrContext.SLEEP, alerts.single().context)
    }

    @Test
    fun lowRateIsNeverJudgedDuringExercise() {
        val odd = (0 until 20).map { minute(it, 30, HrContext.EXERCISE) }
        assertTrue(rules.evaluate(odd, on, limits(), emptyMap(), id).isEmpty())
    }

    @Test
    fun theSingleSwitchTurnsEverythingOff() {
        val high = (0 until 12).map { minute(it, 150) }
        assertEquals(1, rules.evaluate(high, on, limits(), emptyMap(), id).size)
        assertTrue(rules.evaluate(high, on.withMonitoring(false), limits(), emptyMap(), id).isEmpty())
    }

    @Test
    fun exerciseAndRestAlertsHaveSeparateCooldowns() {
        val rest = HeartRateAlertRules.key(AlertKind.HIGH_HEART_RATE, HrContext.REST)
        val workout = (0 until 5).map { minute(it, 200, HrContext.EXERCISE) }
        assertEquals(1, rules.evaluate(workout, on, limits(), mapOf(rest to 0L), id).size)
    }

    @Test
    fun olderSettingsAndMinutesStillDecode() {
        val settings = Protocol.json.decodeFromString(MonitorSettings.serializer(), """{"highBpm":110,"updatedAtMs":5}""")
        assertTrue(settings.heartMonitoring)
        // Someone who had turned every part off starts with the single switch off.
        val off = Protocol.json.decodeFromString(
            MonitorSettings.serializer(),
            """{"irregularRhythmEnabled":false,"heartRateAlertsEnabled":false,"backgroundHeartRate":false}"""
        )
        assertFalse(off.heartMonitoring)
        // One part on is enough: then the switch is on and every part follows it.
        val partly = Protocol.json.decodeFromString(MonitorSettings.serializer(), """{"irregularRhythmEnabled":false}""")
        assertTrue(partly.heartMonitoring)
        assertTrue(partly.normalized().irregularRhythmEnabled)
        val old = Protocol.json.decodeFromString(
            HrMinute.serializer(),
            """{"minuteStartMs":0,"avgBpm":90,"minBpm":80,"maxBpm":99,"resting":false}"""
        )
        assertEquals(HrContext.ACTIVE, old.activity)
    }

    @Test
    fun turningMonitoringOffAlsoTurnsOffThePartsOlderWatchesRead() {
        val off = MonitorSettings().withMonitoring(false)
        assertFalse(off.backgroundHeartRate || off.heartRateAlertsEnabled || off.irregularRhythmEnabled || off.passiveHeartRate)
    }
}
