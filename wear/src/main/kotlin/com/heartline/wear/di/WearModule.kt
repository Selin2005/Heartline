package com.heartline.wear.di

import com.heartline.datalayer.DataLayerTransport
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.SyncTransport
import com.heartline.shared.sync.WatchSyncEngine
import com.heartline.wear.BuildConfig
import com.heartline.wear.data.WatchDatabase
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.bp.BpMeasureViewModel
import com.heartline.wear.bp.WatchBpStore
import com.heartline.wear.sensor.AndroidMotionMeter
import com.heartline.shared.model.Metric
import com.heartline.wear.ecg.EcgMeasureViewModel
import com.heartline.wear.quick.QuickMeasureViewModel
import com.heartline.wear.quick.QuickSources
import com.heartline.wear.quick.WatchProfileStore
import com.heartline.wear.sensor.FakeQuickSource
import com.heartline.wear.sensor.StressSource
import com.heartline.wear.sensor.sdk.SdkBiaSource
import com.heartline.wear.sensor.sdk.SdkSkinTempSource
import com.heartline.wear.sensor.sdk.SdkSpo2Source
import com.heartline.wear.sensor.sdk.readSkinConductance
import com.heartline.wear.sensor.FakePpgSource
import com.heartline.wear.sensor.PpgSource
import com.heartline.wear.sensor.sdk.SdkPpgSource
import com.heartline.wear.monitor.BackgroundHeart
import com.heartline.wear.monitor.BackgroundMonitoring
import com.heartline.wear.monitor.WatchMonitorOutput
import com.heartline.wear.monitor.WatchNotifier
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.sensor.FakeHrSource
import com.heartline.wear.sensor.HrSource
import com.heartline.wear.sensor.sdk.SdkHrSource
import com.heartline.wear.ui.HeartRateViewModel
import com.heartline.wear.ui.WatchSettingsViewModel
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.FakeEcgSource
import com.heartline.wear.sensor.FakeSensorGateway
import com.heartline.wear.sensor.SyncScheduler
import com.heartline.wear.sensor.sdk.SdkEcgSource
import com.heartline.wear.sync.SyncWorker
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.sensor.sdk.SdkSensorGateway
import com.heartline.wear.ui.HistoryViewModel
import com.heartline.wear.ui.LauncherViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.heartline.datalayer.RemoteOpener
import com.heartline.shared.sync.Hello
import com.heartline.shared.sync.WatchLinkChecker
import com.heartline.wear.MainActivity
import com.heartline.wear.link.AppForeground
import com.heartline.wear.link.PhoneOpener
import com.heartline.wear.link.WatchCommandBus
import com.heartline.wear.link.WatchLinkStore
import com.heartline.wear.ui.setup.SetupGateViewModel
import com.heartline.wear.ui.setup.SetupPermissions
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val APP_SCOPE = named("appScope")

val wearModule = module {
    single(APP_SCOPE) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { WatchDatabase.create(androidContext()) }
    single { get<WatchDatabase>().records() }
    single { get<WatchDatabase>().messages() }
    single { WatchRecordStore(get(), androidContext().filesDir, get()) }
    single { WatchSettingsStore(androidContext()) }
    single { WatchNotifier(androidContext()) }
    single { WatchMonitorOutput(get(), get(), get(), get()) }
    single { BackgroundHeart(get<WatchMonitorOutput>()) { get<WatchSettingsStore>().settings.value } }
    single<HrSource> {
        if (BuildConfig.USE_FAKE_SENSORS) FakeHrSource() else SdkHrSource(get<SensorGateway>() as SdkSensorGateway)
    }
    single { DataLayerTransport(androidContext(), Protocol.CAPABILITY_PHONE) } bind SyncTransport::class
    single {
        WatchSyncEngine(
            get(),
            get<WatchRecordStore>(),
            onRemoteDelete = { get<WatchRecordStore>().delete(it) },
            onSettings = { settings ->
                if (get<WatchSettingsStore>().offer(settings)) BackgroundMonitoring.sync(androidContext(), settings)
            },
            onCalibration = { get<WatchBpStore>().setCalibration(it) },
            onProfile = { get<WatchProfileStore>().update(it) },
            // The phone opens screens directly; these messages are the fallback. In the foreground
            // they navigate at once, in the background they leave one deep-linking notification.
            onOpen = { route -> if (AppForeground.resumed) get<WatchCommandBus>().post(route) else get<WatchNotifier>().openRequest(route) },
            onCaptureRequest = { request ->
                get<WatchBpStore>().setPendingCapture(request)
                if (AppForeground.resumed) {
                    get<WatchCommandBus>().post(MainActivity.ROUTE_BP_CALIBRATION)
                } else {
                    get<WatchNotifier>().calibrationRequest(request.round)
                }
            },
            onStatus = { get<WatchLinkStore>().update(it) },
        )
    }
    single { WatchLinkStore(androidContext()) }
    single { WatchCommandBus() }
    single { RemoteOpener(androidContext(), get()) }
    single { PhoneOpener(get(), get()) }
    single {
        WatchLinkChecker(
            get<DataLayerTransport>(),
            get(),
            get<WatchLinkStore>().latest,
            hello = { Hello(appVersion = BuildConfig.VERSION_NAME, deviceName = Build.MODEL, settings = get<WatchSettingsStore>().settings.value) },
            log = { Log.i("Heartline/Link", it) },
        )
    }
    single<SensorGateway> {
        // Debug builds use real sensors when the build flag is off; see BuildConfig.USE_FAKE_SENSORS.
        if (BuildConfig.USE_FAKE_SENSORS) FakeSensorGateway() else SdkSensorGateway(androidContext())
    }
    single<EcgSource> {
        if (BuildConfig.USE_FAKE_SENSORS) FakeEcgSource() else SdkEcgSource(get<SensorGateway>() as SdkSensorGateway)
    }
    single { SyncScheduler { SyncWorker.enqueue(androidContext()) } }
    single { WatchBpStore(androidContext()) }
    single { WatchProfileStore(androidContext()) }
    single { com.heartline.wear.tile.TileUpdates(androidContext(), get(), get(), get()) }
    single { com.heartline.wear.tile.TileDataLoader(androidContext(), get(), get(), get(), get()) }
    single {
        val hr = StressSource(get(), skinConductance = { (get<SensorGateway>() as? SdkSensorGateway)?.readSkinConductance() })
        if (BuildConfig.USE_FAKE_SENSORS) {
            QuickSources(FakeQuickSource.all() + hr)
        } else {
            val gateway = get<SensorGateway>() as SdkSensorGateway
            QuickSources(listOf(SdkSpo2Source(gateway), SdkSkinTempSource(gateway), SdkBiaSource(gateway), hr))
        }
    }
    single<PpgSource> {
        if (BuildConfig.USE_FAKE_SENSORS) FakePpgSource() else SdkPpgSource(get<SensorGateway>() as SdkSensorGateway)
    }
    viewModel { LauncherViewModel(get(), get()) }
    viewModel {
        val context = androidContext()
        SetupGateViewModel(get(), get(), get(), get()) {
            SetupPermissions.required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        }
    }
    viewModel { HistoryViewModel(get()) }
    viewModel { HeartRateViewModel(get()) }
    viewModel {
        WatchSettingsViewModel(get(), get()) { changed ->
            BackgroundMonitoring.sync(androidContext(), changed)
            get<WatchSyncEngine>().sendSettings(changed)
        }
    }
    viewModel { BpMeasureViewModel(get(), get(), get(), get(), motion = AndroidMotionMeter(androidContext())) }
    viewModel { params -> QuickMeasureViewModel(get<QuickSources>()[params.get<Metric>()]!!, get(), get(), get()) }
    viewModel { EcgMeasureViewModel(get(), get(), get()) }
}
