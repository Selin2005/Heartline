// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.wear.monitor.WindowAlarm
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class WindowAlarmTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theAlarmEndsTheRunningWindowOnlyOnce() {
        val stop = CompletableDeferred<String>()
        WindowAlarm.begin(stop)
        assertTrue(WindowAlarm.fire())
        assertEquals("alarm", stop.getCompleted())
        // A second alarm, or one after the window ended, does nothing.
        assertFalse(WindowAlarm.fire())
        WindowAlarm.end(stop)
        assertFalse(WindowAlarm.fire())
    }

    @Test
    fun aWindowThatEndedByItselfIsNotChangedByALateAlarm() {
        val stop = CompletableDeferred<String>()
        WindowAlarm.begin(stop)
        stop.complete("covered")
        WindowAlarm.end(stop)
        assertFalse(WindowAlarm.fire())
        assertEquals("covered", stop.getCompleted())
    }

    @Test
    fun theAlarmIsSetForTheWindowAndCancelledAfter() {
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        WindowAlarm.schedule(context, 90_000)
        // peek: getNextScheduledAlarm also removes it.
        val alarm = alarms.peekNextScheduledAlarm()
        assertNotNull(alarm)
        assertTrue(alarm!!.isAllowWhileIdle)
        WindowAlarm.cancel(context)
        assertNull(alarms.peekNextScheduledAlarm())
    }
}
