// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.notify.Reminders
import com.heartline.shared.model.Metric
import com.heartline.shared.profile.WeekSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class WeeklySummaryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun comesOnFridayEvening() {
        // Saturday 26 Sep 2026, 10:00 → Friday 2 Oct, 19:00.
        assertEquals(Duration.ofHours(6 * 24 + 9), Reminders.delayUntilWeekly(LocalDateTime.of(2026, 9, 26, 10, 0)))
        // Friday before 19:00 → the same evening.
        assertEquals(Duration.ofHours(2), Reminders.delayUntilWeekly(LocalDateTime.of(2026, 10, 2, 17, 0)))
    }

    @Test
    fun weekInOneLine() {
        val week = WeekSummary(12, mapOf(Metric.ECG to 5), goalDays = 5, bpAverage = 119 to 77, bpChange = -2, streak = 4)
        val (title, text) = Reminders.weeklyText(context, week, "Sara")
        assertEquals("Your week, Sara", title)
        assertEquals("12 measurements · check-ins done on 5 of 7 days · 4-day streak · blood pressure 119/77 (-2 vs last week)", text)
    }
}
