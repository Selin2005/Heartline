// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import android.app.Application
import android.util.Log
import com.heartline.phone.data.BpRepository
import com.heartline.phone.link.PhoneStatusPublisher
import com.heartline.phone.notify.Reminders
import com.heartline.phone.widget.WidgetUpdater
import com.heartline.phone.data.DemoData
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.di.APP_SCOPE
import com.heartline.phone.di.phoneModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class PhoneApplication : Application() {
    /** Removes the sample records, alert, heart-rate history and BP calibration older debug builds seeded. */
    private suspend fun purgeDemoData() {
        val db = get<HeartlineDatabase>()
        val records = db.records().deleteDemo()
        val calibrations = db.bp().deleteDemo()
        db.heart().deleteDemoAlerts()
        // Demo minutes have no ids; no real minute could have arrived before this fix (the link never worked).
        db.heart().deleteMinutes()
        Log.i("Heartline/Data", "purged demo data: records=$records calibrations=$calibrations")
        if (calibrations > 0) get<BpRepository>().resendCalibration(orNull = true)
    }

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PhoneApplication)
            modules(phoneModule)
        }
        Log.i("Heartline/App", "phone app ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) on ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}")
        val scope = get<CoroutineScope>(APP_SCOPE)
        scope.launch {
            val settings = get<SettingsRepository>()
            if (settings.claimDemoPurge()) purgeDemoData()
            // Only builds made with -Pheartline.demoData=true start with sample records.
            if (BuildConfig.DEMO_DATA && settings.claimDemoSeed()) {
                DemoData.seedIfEmpty(get<RecordRepository>(), HeartRepository(get()))
            }
        }
        get<PhoneStatusPublisher>().start(scope)
        get<WidgetUpdater>().start(scope)
        scope.launch { Reminders.sync(this@PhoneApplication, get<SettingsRepository>().current()) }
    }
}
