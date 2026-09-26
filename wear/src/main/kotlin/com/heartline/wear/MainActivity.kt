// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.heartline.datalayer.DeepLinks
import com.heartline.wear.link.AppForeground
import com.heartline.wear.link.WatchCommandBus
import com.heartline.wear.ui.HeartlineWearApp
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val bus: WatchCommandBus by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val route = if (savedInstanceState == null) routeOf(intent) else null
        setContent { HeartlineWearApp(startRoute = route) }
    }

    /** singleTop: the phone (or a notification) opening a screen while the app is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeOf(intent)?.let(bus::post)
    }

    override fun onResume() {
        super.onResume()
        AppForeground.resumed = true
    }

    override fun onPause() {
        AppForeground.resumed = false
        super.onPause()
    }

    private fun routeOf(intent: Intent?): String? =
        DeepLinks.route(intent?.data, DeepLinks.WATCH_HOST) ?: intent?.getStringExtra(EXTRA_ROUTE)

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ROUTE_BP = "blood_pressure"
        const val ROUTE_BP_CALIBRATION = "bp_calibration"
        const val ROUTE_ECG = "ecg"
        const val ROUTE_HEART_RATE = "heart_rate"
        const val ROUTE_SETUP = "setup"
    }
}
