// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import com.heartline.datalayer.diag.HLog
import android.app.Application
import com.heartline.wear.di.wearModule
import com.heartline.wear.di.APP_SCOPE
import com.heartline.wear.monitor.BackgroundMonitoring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.heartline.wear.monitor.WatchSettingsStore
import org.koin.android.ext.android.get
import com.heartline.wear.sync.SyncWorker
import com.heartline.shared.diag.Redactor
import com.heartline.wear.quick.WatchProfileStore
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class WearApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HLog.init(this)
        startKoin {
            androidContext(this@WearApplication)
            modules(wearModule)
        }
        HLog.i(
            "Heartline/App",
            "watch app ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}, fakeSensors=${BuildConfig.USE_FAKE_SENSORS}) " +
                "on ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}",
        )
        // Deliver anything left over from a previous session.
        SyncWorker.enqueue(this)
        val settings = get<WatchSettingsStore>().settings.value
        get<CoroutineScope>(APP_SCOPE).launch { BackgroundMonitoring.sync(this@WearApplication, settings) }
        get<com.heartline.wear.tile.TileUpdates>().start(get(APP_SCOPE))
        // Diagnostic logging follows the phone's choice (synced settings); names never reach the file.
        val scope = get<CoroutineScope>(APP_SCOPE)
        get<WatchSettingsStore>().settings.onEach { HLog.configure(it.diagnosticLogs, it.detailedLogsUntilMs) }.launchIn(scope)
        get<WatchProfileStore>().profile.onEach { HLog.setRedactor(Redactor.of(it)) }.launchIn(scope)
        // The hello handshake (status, settings, calibration, profile) runs from the setup gate on every app start.
    }
}
