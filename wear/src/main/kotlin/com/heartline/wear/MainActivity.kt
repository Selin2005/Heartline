// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.heartline.datalayer.DeepLinks
import com.heartline.datalayer.diag.HLog
import com.heartline.wear.link.AppForeground
import com.heartline.wear.link.WatchCommandBus
import com.heartline.wear.ui.HeartlineWearApp
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val bus: WatchCommandBus by inject()

    /** The last route this activity queued, kept across recreation so an old intent never opens twice. */
    private var handledRoute: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Also when the activity is recreated (the system had stopped the app): the phone's
        // request may be the very intent that brought it back.
        handledRoute = savedInstanceState?.getString(KEY_HANDLED)
        HLog.i(TAG, "created (restored=${savedInstanceState != null})")
        handle(intent, restored = savedInstanceState != null)
        setContent { HeartlineWearApp() }
    }

    /** singleTop: the phone (or a notification) opening a screen while the app is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        HLog.i(TAG, "new intent")
        handle(intent, restored = false)
    }

    /**
     * Queues the screen [intent] asks for. A [restored] activity sees the intent that first
     * launched it again: that one was already opened and is skipped.
     */
    private fun handle(intent: Intent?, restored: Boolean) {
        val route = routeOf(intent) ?: return
        if (restored && route == handledRoute) {
            HLog.i(TAG, "already opened: $route")
            return
        }
        handledRoute = route
        HLog.i(TAG, "open request: $route")
        bus.post(route)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_HANDLED, handledRoute)
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
        private const val KEY_HANDLED = "handled_route"
        private const val TAG = "Heartline/Open"
        const val ROUTE_BP = "blood_pressure"
        const val ROUTE_BP_CALIBRATION = "bp_calibration"
        const val ROUTE_ECG = "ecg"
        const val ROUTE_HEART_RATE = "heart_rate"
        const val ROUTE_SETUP = "setup"
        const val ROUTE_BREATHE = "breathe"
    }
}
