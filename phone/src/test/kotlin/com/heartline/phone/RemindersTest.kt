// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import com.heartline.phone.notify.Reminders
import com.heartline.shared.bp.BpCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class RemindersTest {
    @Test
    fun dailyReminderIsTheNextOccurrence() {
        val morning = LocalDateTime.of(2026, 9, 24, 8, 0)
        assertEquals(Duration.ofHours(1), Reminders.delayUntil(9 * 60, morning))
        val evening = LocalDateTime.of(2026, 9, 24, 21, 30)
        assertEquals(Duration.ofMinutes(11 * 60 + 30), Reminders.delayUntil(9 * 60, evening))
        // Exactly at the time: tomorrow, not now (no double fire).
        assertEquals(Duration.ofDays(1), Reminders.delayUntil(9 * 60, LocalDateTime.of(2026, 9, 24, 9, 0)))
    }

    @Test
    fun calibrationReminderOnlyInTheLastThreeDays() {
        val cal = BpCalibration("c", 0, emptyList())
        assertFalse(Reminders.calibrationDue(null, 0))
        // Empty calibration is invalid: the watch asks directly, no reminder.
        assertFalse(Reminders.calibrationDue(cal, 0))
    }

    @Test
    fun calibrationDueWindow() {
        val day = BpCalibration.DAY_MS
        val points = listOf(SampleCalibration.point, SampleCalibration.point, SampleCalibration.point)
        val cal = BpCalibration("c", 0, points)
        assertFalse(Reminders.calibrationDue(cal, 10 * day))
        assertTrue(Reminders.calibrationDue(cal, 26 * day))
        assertFalse(Reminders.calibrationDue(cal, 29 * day))
    }
}

private object SampleCalibration {
    val point by lazy {
        val features = com.heartline.shared.bp.PpgFeatures.extract(com.heartline.shared.sample.SyntheticPpg.generate(20.0), 100)!!
        com.heartline.shared.bp.CalibrationPoint(features, 120, 80, 68)
    }
}
