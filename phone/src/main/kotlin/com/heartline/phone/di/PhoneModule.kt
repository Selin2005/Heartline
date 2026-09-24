package com.heartline.phone.di

import com.heartline.datalayer.DataLayerTransport
import com.heartline.phone.R
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.ProfileRepository
import com.heartline.phone.export.DataExporter
import com.heartline.phone.ui.model.OnboardingViewModel
import com.heartline.phone.ui.model.OpenOnWatchViewModel
import com.heartline.phone.ui.model.MetricDetailViewModel
import com.heartline.phone.ui.model.ProfileViewModel
import com.heartline.phone.ui.model.BpHomeViewModel
import com.heartline.phone.ui.model.CalibrationViewModel
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.notify.PhoneNotifier
import com.heartline.phone.ui.model.AlertsViewModel
import com.heartline.phone.ui.model.HeartRateViewModel
import com.heartline.phone.data.WaveStore
import com.heartline.phone.report.EcgReportBuilder
import com.heartline.phone.ui.model.EcgDetailViewModel
import com.heartline.phone.ui.model.EcgListViewModel
import com.heartline.phone.ui.model.HomeViewModel
import com.heartline.phone.ui.model.RecordFormatter
import com.heartline.phone.ui.model.SettingsViewModel
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.SyncTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val APP_SCOPE = named("appScope")

val phoneModule = module {
    single(APP_SCOPE) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { HeartlineDatabase.create(androidContext()) }
    single { get<HeartlineDatabase>().records() }
    single { WaveStore(androidContext().filesDir) }
    single { RecordRepository(get(), get()) }
    single { DataLayerTransport(androidContext(), Protocol.CAPABILITY_WATCH) } bind SyncTransport::class
    single { PhoneNotifier(androidContext()) }
    single { get<HeartlineDatabase>().heart() }
    single { HeartRepository(get()) { alert -> get<PhoneNotifier>().alert(alert) } }
    single { SettingsRepository(androidContext()) }
    single { get<HeartlineDatabase>().bp() }
    single { BpRepository(get(), get()) { get() } }
    single { ProfileRepository(androidContext()) { get() } }
    single {
        PhoneSyncEngine(
            get(),
            get<RecordRepository>(),
            get<HeartRepository>(),
            onSettingsRequested = {
                // The watch says hello on start: reply with settings and the active calibration.
                get<PhoneSyncEngine>().sendSettings(get<SettingsRepository>().current())
                get<BpRepository>().resendCalibration()
                get<ProfileRepository>().resend()
            },
            onCaptureResult = { get<BpRepository>().onCaptureResult(it) },
        )
    }
    factory {
        val ctx = androidContext()
        RecordFormatter(
            ctx.getString(R.string.date_today),
            ctx.getString(R.string.date_yesterday),
            ctx.getString(R.string.home_bp_calibration_left).replace("%1\$d", "%d"),
            ctx.getString(R.string.bp_needs_calibration),
        )
    }
    viewModel { HomeViewModel(get(), get(), get(), get(), get()) }
    viewModel { EcgListViewModel(get(), get()) }
    single { EcgReportBuilder(androidContext()) }
    viewModel { params -> EcgDetailViewModel(params.get(), get(), get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get()) }
    viewModel { HeartRateViewModel(get(), get()) }
    viewModel { BpHomeViewModel(get(), get()) }
    viewModel { CalibrationViewModel(get()) }
    viewModel { params -> MetricDetailViewModel(params.get(), get(), get()) }
    viewModel { ProfileViewModel(get()) }
    viewModel { OpenOnWatchViewModel(get()) }
    viewModel { OnboardingViewModel(get()) }
    single { DataExporter(androidContext()) }
    viewModel { AlertsViewModel(get(), get()) }
}
