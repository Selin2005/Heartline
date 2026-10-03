// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.hr.AlertSensitivity
import com.heartline.shared.hr.Answer
import com.heartline.shared.hr.HealthContext
import com.heartline.shared.hr.HeartBaseline
import com.heartline.shared.hr.HeartHistory
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.MaxHr
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.hr.VitalAlert
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.stress.StressBaseline
import com.heartline.shared.stress.StressHistory
import com.heartline.shared.stress.StressMonitor
import com.heartline.shared.sync.Protocol
import com.heartline.shared.vitals.VitalsBaseline
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StressTest {
    private val zone = ZoneOffset.UTC
    private var ids = 0
    private val monitor = StressMonitor(zone) { "s${ids++}" }
    private val on = MonitorSettings()
    private val day0 = 20_000L
    private fun at(day: Long, hour: Int, minute: Int = 0) = day * 86_400_000L + hour * 3_600_000L + minute * 60_000L

    /** Days of calm awake windows every 15 minutes from 9:00 to 18:00 (RMSSD around [rmssd]). */
    private fun calmDays(from: Long, to: Long, rmssd: Double = 40.0, bpm: Int = 66, h0: StressHistory = StressHistory()): StressHistory {
        var h = h0
        for (d in from..to) {
            for (i in 0 until 36) {
                val wobble = 1 + ((i * 7 + d.toInt()) % 5 - 2) * 0.06
                h = monitor.onWindow(h, at(d, 9, i * 15), rmssd * wobble, bpm + (i % 3) - 1, HrContext.REST, on).first
            }
        }
        return h
    }

    @Test
    fun scoreStartsFromThePopulationAndBecomesPersonal() {
        // No history: the population score.
        assertEquals(StressIndex.score(25.0), StressBaseline.score(25.0, 70, null))
        // Someone whose usual RMSSD is 25 ms (high on the population scale) is at their usual: about 30.
        val h = calmDays(day0 - 5, day0 - 1, rmssd = 25.0)
        val normal = StressBaseline.normal(h, day0)!!
        assertTrue(StressIndex.score(25.0) > 50)
        assertTrue(StressBaseline.score(25.0, 66, normal) in 25..40)
        // Half of their usual RMSSD and 10 bpm faster: high.
        assertTrue(StressBaseline.score(12.5, 76, normal) >= 67)
    }

    @Test
    fun sustainedHighStressNotifiesOnceAndNotWhileLearningOrQuiet() {
        var h = calmDays(day0 - 6, day0 - 1)
        var alerts = 0
        for (i in 0 until 4) {
            val (next, sample, alert) = monitor.onWindow(h, at(day0, 14, i * 15), 18.0, 78, HrContext.REST, on)
            h = next
            assertNotNull(sample)
            if (alert != null) {
                alerts++
                assertEquals(VitalAlert.STRESS, alert.vital)
                assertEquals(at(day0, 14, 45), alert.atMs) // the fourth high window, 45 minutes on
            }
        }
        assertEquals(1, alerts)
        // Two high windows alone are not enough; nor while learning (a few windows only).
        val two = calmDays(day0 - 6, day0 - 1).let { base ->
            listOf(0, 15).fold(base to 0) { (hh, n), m ->
                monitor.onWindow(hh, at(day0, 14, m), 18.0, 78, HrContext.REST, on).let {
                    it.first to
                        n + (if (it.third != null) 1 else 0)
                }
            }
        }
        assertEquals(0, two.second)
        var learning = calmDays(day0 - 1, day0 - 1).copy(
            days = calmDays(day0 - 1, day0 - 1).days.map {
                it.copy(awakeLn = it.awakeLn.take(5), awakeBpm = it.awakeBpm.take(5))
            }
        )
        var learningAlerts = 0
        for (i in 0 until 4) {
            monitor.onWindow(learning, at(day0, 14, i * 15), 18.0, 78, HrContext.REST, on).also {
                learning = it.first
                if (it.third !=
                    null
                ) {
                    learningAlerts++
                }
            }
        }
        assertEquals(0, learningAlerts)
        // Quiet hours (22–7): no notice.
        var night = calmDays(day0 - 6, day0 - 1)
        var quietAlerts = 0
        for (i in 0 until 4) {
            monitor.onWindow(night, at(day0, 22, i * 15), 18.0, 78, HrContext.REST, on).also {
                night = it.first
                if (it.third !=
                    null
                ) {
                    quietAlerts++
                }
            }
        }
        assertEquals(0, quietAlerts)
    }

    @Test
    fun illnessExerciseAndSleepNeverGiveAStressNotice() {
        val base = calmDays(day0 - 6, day0 - 1)
        var h = base
        var n = 0
        for (i in 0 until 4) {
            monitor.onWindow(h, at(day0, 14, i * 15), 18.0, 78, HrContext.REST, on, illness = true).also {
                h = it.first
                if (it.third !=
                    null
                ) {
                    n++
                }
            }
        }
        assertEquals(0, n)
        // Exercise or moving: not a stress window at all.
        assertNull(monitor.onWindow(base, at(day0, 14), 10.0, 140, HrContext.EXERCISE, on).second)
        // Sleep: only the night's HRV.
        val (slept, sample, alert) = monitor.onWindow(base, at(day0, 2), 50.0, 55, HrContext.SLEEP, on)
        assertNull(sample!!.score)
        assertNull(alert)
        assertEquals(1, slept.day(day0).sleepLn.size)
    }

    @Test
    fun highWindowsAreNotLearnt() {
        var h = calmDays(day0 - 6, day0 - 1)
        val before = StressBaseline.normal(h, day0)!!
        for (i in 0 until 8) h = monitor.onWindow(h, at(day0, 12, i * 15), 15.0, 80, HrContext.REST, on).first
        assertEquals(before.ln, StressBaseline.normal(h, day0)!!.ln, 0.01)
    }

    @Test
    fun healthAnswersAdaptTheChecks() {
        fun with(c: HealthContext) = MonitorSettings(health = c)
        // Rate-lowering medicine: Brawner's maximum (164 − 0.7 × age) instead of Tanaka's.
        assertEquals(177, MaxHr.predicted(43))
        assertEquals(133, MaxHr.predicted(44, rateLowering = true))
        val limits = HeartBaseline.limits(
            HeartHistory(),
            day0,
            AlertSensitivity.STANDARD,
            44,
            null,
            HealthContext(rateLoweringMedicine = Answer.YES)
        )
        assertEquals(133, limits.exerciseMax)
        // Atrial fibrillation: no rhythm checks and no stress; high and low heart rate stay.
        val af = with(HealthContext(atrialFibrillation = Answer.YES))
        assertTrue(af.heartActive && af.lowHeartRateActive)
        assertFalse(af.rhythmActive || af.stressActive)
        // A pacemaker: no low heart rate either.
        val device = with(HealthContext(heartDevice = Answer.YES))
        assertFalse(device.lowHeartRateActive || device.rhythmActive || device.stressActive)
        // A lung condition: the oxygen limit is 88 % at every sensitivity.
        assertEquals(
            88,
            VitalsBaseline.spo2Low(with(HealthContext(lungCondition = Answer.YES)).copy(alertSensitivity = AlertSensitivity.HIGH))
        )
        assertEquals(90, VitalsBaseline.spo2Low(on))
        // Unsure counts as no.
        assertTrue(with(HealthContext(atrialFibrillation = Answer.UNSURE)).rhythmActive)
        // Pregnancy: temperature measured, no temperature notices.
        val pregnant = with(HealthContext(pregnant = true))
        assertTrue(pregnant.skinTempActive && !pregnant.temperatureNotices)
        // Endurance training: a lower first guess of the resting rate.
        assertEquals(
            50,
            HeartBaseline.limits(
                HeartHistory(),
                day0,
                AlertSensitivity.STANDARD,
                30,
                null,
                HealthContext(enduranceTraining = true)
            ).restNormal
        )
        assertFalse(HealthContext().answered)
        assertTrue(HealthContext(Answer.NO, Answer.NO, Answer.UNSURE, Answer.NO).answered)
    }

    @Test
    fun quietHoursWrapMidnightAndOldJsonDecodes() {
        assertTrue(on.isQuiet(23 * 60) && on.isQuiet(3 * 60) && !on.isQuiet(12 * 60))
        assertTrue(on.copy(quietStartMinute = 13 * 60, quietEndMinute = 14 * 60).isQuiet(13 * 60 + 30))
        val old = Protocol.json.decodeFromString(MonitorSettings.serializer(), """{"heartRateAlertsEnabled":true}""")
        assertTrue(old.stressActive && old.stressNotifications && !old.health.answered)
    }

    @Test
    fun limitsShowTodayAndTheNight() {
        var h = calmDays(day0 - 6, day0)
        for (i in 0 until 4) h = monitor.onWindow(h, at(day0, 2, i * 30), 55.0, 54, HrContext.SLEEP, on).first
        val l = StressBaseline.limits(h, day0)
        assertTrue(l.confidence > 0.9)
        assertTrue(l.usualRmssd!! in 35.0..45.0)
        assertEquals(55.0, l.lastNightRmssd!!, 0.5)
        assertTrue(l.todayAverage!! in 20..40)
        assertEquals(0, l.todayHighMinutes)
    }
}
