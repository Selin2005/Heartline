// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.wear.monitor.Charging
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChargingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION")
    private fun battery(plugged: Int) =
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, plugged).putExtra(BatteryManager.EXTRA_LEVEL, 100))

    @Test
    fun onTheChargerFullOrNot() {
        battery(BatteryManager.BATTERY_PLUGGED_WIRELESS)
        assertTrue(Charging.isCharging(context))
        battery(BatteryManager.BATTERY_PLUGGED_USB)
        assertTrue(Charging.isCharging(context))
        battery(0)
        assertFalse(Charging.isCharging(context))
    }
}
