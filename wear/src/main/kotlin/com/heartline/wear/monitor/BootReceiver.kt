package com.heartline.wear.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-applies background monitoring after a reboot (passive registration and IRN windows). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val settings = WatchSettingsStore(context).settings.value
        CoroutineScope(Dispatchers.Default).launch {
            try {
                BackgroundMonitoring.sync(context.applicationContext, settings)
            } finally {
                pending.finish()
            }
        }
    }
}
