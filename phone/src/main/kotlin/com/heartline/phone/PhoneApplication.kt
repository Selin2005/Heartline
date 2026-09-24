package com.heartline.phone

import android.app.Application
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
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PhoneApplication)
            modules(phoneModule)
        }
        // Debug builds start with sample records so the UI can be explored without a watch.
        if (BuildConfig.DEBUG) {
            get<CoroutineScope>(APP_SCOPE).launch {
                if (get<SettingsRepository>().claimDemoSeed()) {
                    DemoData.seedIfEmpty(get<RecordRepository>(), HeartRepository(get()), get<HeartlineDatabase>().bp())
                }
            }
        }
    }
}
