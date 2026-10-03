// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import com.heartline.phone.data.StressSampleEntity
import com.heartline.phone.ui.model.BackgroundStress
import com.heartline.phone.ui.model.RecordFormatter
import com.heartline.phone.ui.onboarding.MonitoringSetup
import com.heartline.shared.hr.Answer
import com.heartline.shared.hr.HealthContext
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.stress.StressLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class MonitoringSetupTest {
    private val answered = HealthContext(Answer.NO, Answer.NO, Answer.NO, Answer.NO)

    @Test
    fun answersAdaptTheSwitchesAndAreListedInTheSummary() {
        val base = MonitorSettings()
        val af = MonitoringSetup.apply(base, true, base.copy(health = answered.copy(atrialFibrillation = Answer.YES)))
        assertTrue(af.heartActive && !af.rhythmActive && !af.stressActive)
        // Older watches read the old rhythm switch: it follows the answer.
        assertFalse(af.irregularRhythmEnabled)
        assertEquals(listOf(R.string.setup_adjust_af), MonitoringSetup.adjustments(af))
        // Only the heart: the parts keep the wearer's choice.
        val heartOnly = MonitoringSetup.apply(base, true, base.copy(health = answered, spo2Monitoring = false, skinTempMonitoring = false, stressMonitoring = false))
        assertTrue(heartOnly.heartActive && !heartOnly.spo2Active && !heartOnly.skinTempActive && !heartOnly.stressActive)
        assertTrue(MonitoringSetup.adjustments(heartOnly).isEmpty())
        // "Not now": everything off, all-day heart rate keeps recording.
        val off = MonitoringSetup.apply(base, false, base.copy(health = answered))
        assertFalse(off.heartMonitoring || off.heartActive)
        assertTrue(off.passiveHeartRate)
    }

    @Test
    fun stressCardShowsTodayInSlotsAndTheWeek() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 2)
        fun at(day: LocalDate, hour: Int, minute: Int) = day.atTime(hour, minute).toInstant(zone).toEpochMilli()
        val samples = listOf(
            StressSampleEntity(at(today.minusDays(1), 10, 0), 40.0, 66, 40, HrContext.REST),
            StressSampleEntity(at(today, 7, 0), 40.0, 66, 30, HrContext.REST),
            StressSampleEntity(at(today, 14, 15), 20.0, 78, 80, HrContext.REST),
            StressSampleEntity(at(today, 3, 0), 60.0, 55, null, HrContext.SLEEP),
        )
        val formatter = RecordFormatter("Today", "Yesterday", zone = zone, locale = java.util.Locale.US, today = { today })
        val ui = BackgroundStress.ui(samples, StressLimits(confidence = 0.9, usualNightRmssd = 58.0, lastNightRmssd = 61.0), today, formatter, zone)!!
        assertEquals(30, ui.today[0])
        assertEquals(80, ui.today[(14 - 7) * 4 + 1])
        assertEquals(55, ui.todayAverage)
        assertEquals(15, ui.highMinutes)
        assertEquals(listOf(null, null, null, null, null, 40, 55), ui.week)
        assertEquals(61, ui.lastNightRmssd)
        assertFalse(ui.learning)
        assertNull(BackgroundStress.ui(emptyList(), null, today, formatter, zone))
    }
}
