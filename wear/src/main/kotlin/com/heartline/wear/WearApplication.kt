package com.heartline.wear

import android.app.Application
import com.heartline.wear.di.wearModule
import com.heartline.wear.monitor.HeartMonitorService
import com.heartline.wear.monitor.WatchSettingsStore
import org.koin.android.ext.android.get
import com.heartline.wear.sync.SyncWorker
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class WearApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@WearApplication)
            modules(wearModule)
        }
        // Deliver anything left over from a previous session.
        SyncWorker.enqueue(this)
        val settings = get<WatchSettingsStore>().settings.value
        HeartMonitorService.sync(this, settings.irregularRhythmEnabled || settings.heartRateAlertsEnabled)
        // The hello handshake (status, settings, calibration, profile) runs from the setup gate on every app start.
    }
}
