package com.heartline.wear

import android.app.Application
import com.heartline.shared.sync.Hello
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.SyncTransport
import com.heartline.wear.di.APP_SCOPE
import com.heartline.wear.di.wearModule
import com.heartline.wear.monitor.HeartMonitorService
import com.heartline.wear.monitor.WatchSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
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
        // Ask the phone for its current settings (it replies on /settings).
        get<CoroutineScope>(APP_SCOPE).launch {
            val hello = Hello(appVersion = BuildConfig.VERSION_NAME)
            get<SyncTransport>().send(Protocol.HELLO, Protocol.json.encodeToString(hello).encodeToByteArray())
        }
    }
}
