// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Whether the watch is on its charger. Background measurements don't run then: the watch is off
 * the wrist, and a real log showed rhythm windows lighting the heart-rate sensor on the charger
 * because the off-body sensor hadn't reported yet.
 */
object Charging {
    /** Plugged in by any means, full or not (the sticky battery broadcast; no receiver is registered). */
    fun isCharging(context: Context): Boolean {
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
}
