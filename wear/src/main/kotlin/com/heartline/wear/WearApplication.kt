package com.heartline.wear

import android.app.Application
import com.heartline.wear.di.wearModule
import com.heartline.wear.di.APP_SCOPE
import com.heartline.wear.monitor.BackgroundMonitoring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
        get<CoroutineScope>(APP_SCOPE).launch { BackgroundMonitoring.sync(this@WearApplication, settings) }
        // The hello handshake (status, settings, calibration, profile) runs from the setup gate on every app start.
    }
}
