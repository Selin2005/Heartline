// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import com.heartline.datalayer.diag.HLog
import kotlinx.coroutines.CompletableDeferred

/**
 * Wakes the watch when a rhythm window has listened long enough. A real log (10/09) had windows
 * of 6–12 minutes that were awake for only 3–48 s: the processor slept through the wake lock, the
 * window's timers stood still, and the heart-rate sensor stayed on until the tracker's next batch.
 * An allow-while-idle alarm (no permission; at most about one per 9 minutes in deep sleep, and
 * windows are 15 minutes apart) wakes the watch at the window's end, so it can stop the sensor.
 */
object WindowAlarm {
    private const val TAG = "Heartline/Monitor"

    /** The running window's stop signal; null between windows. */
    private var current: CompletableDeferred<String>? = null

    @Synchronized
    fun begin(stop: CompletableDeferred<String>) {
        current = stop
    }

    /** Clears the window (it ended); a late alarm then does nothing. */
    @Synchronized
    fun end(stop: CompletableDeferred<String>) {
        if (current === stop) current = null
    }

    /** The alarm went off: ends the running window. @return whether there was one to end. */
    @Synchronized
    fun fire(): Boolean = current?.complete("alarm") == true

    fun schedule(context: Context, afterMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + afterMs, intent(context))
        }.onFailure { HLog.w(TAG, "window alarm not set", it) }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intent(context))
    }

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WindowAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Ends the running rhythm window, keeping the watch awake long enough to stop the sensor. */
class WindowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Held through the flush and the hand-over that follow (honoured during this alarm).
        context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heartline:window-alarm")
            ?.acquire(AWAKE_MS)
        val ended = WindowAlarm.fire()
        HLog.i("Heartline/Monitor", "window alarm: ${if (ended) "ending the window" else "no window running"}")
    }

    private companion object {
        const val AWAKE_MS = 15_000L
    }
}
