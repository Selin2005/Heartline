// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.di

import com.heartline.datalayer.diag.HLog
import com.heartline.phone.BuildConfig
import com.heartline.phone.update.ReleaseSource
import com.heartline.phone.diag.DiagnosticsRepository
import com.heartline.phone.diag.DiagnosticsViewModel
import com.heartline.phone.diag.PhoneLogExporter
import com.heartline.shared.diag.RemoteLogs
import com.heartline.shared.diag.WatchLogArchive
import com.heartline.phone.sync.WatchLogInbox
import com.heartline.phone.update.UpdateRepository
import com.heartline.phone.update.Updater
import com.heartline.phone.update.UpdatesViewModel
import com.heartline.datalayer.DataLayerTransport
import com.heartline.datalayer.RemoteOpener
import com.heartline.phone.link.PhoneStatusPublisher
import com.heartline.phone.link.WatchOpener
import com.heartline.phone.notify.Reminders
import com.heartline.phone.ui.model.WatchLinkViewModel
import com.heartline.phone.R
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.bp.PapageiEmbedder
import com.heartline.phone.ecg.EcgSecondOpinion
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.ProfileRepository
import com.heartline.phone.export.DataExporter
import com.heartline.phone.ui.model.OnboardingViewModel
import com.heartline.phone.ui.model.OpenOnWatchViewModel
import com.heartline.phone.ui.model.BodyCompositionViewModel
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
import com.heartline.shared.bp.MorphologyEmbedder
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.SyncTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.model.RecordKind
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val APP_SCOPE = named("appScope")

/** The phone keeps up to this much of the watch's moved log segments and raw sessions. */
private const val WATCH_ARCHIVE_BYTES = 300L * 1024 * 1024

/** Raw blood-pressure session logs (filesDir). */
private const val BP_SESSIONS = "bp-sessions"

val phoneModule = module {
    single(APP_SCOPE) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { HeartlineDatabase.create(androidContext()) }
    single { get<HeartlineDatabase>().records() }
    single { WaveStore(androidContext().filesDir) }
    single { RecordRepository(get(), get()) }
    single { DataLayerTransport(androidContext(), Protocol.CAPABILITY_WATCH) } bind SyncTransport::class
    single { PhoneNotifier(androidContext()) }
    single { EcgSecondOpinion.fromAssets(androidContext()) }
    single { get<HeartlineDatabase>().heart() }
    single {
        HeartRepository(
            get(),
            onAlert = { alert -> get<PhoneNotifier>().alert(alert) },
            onLimits = { limits -> get<SettingsRepository>().saveHeartLimits(limits) },
        )
    }
    single { SettingsRepository(androidContext()) }
    single { UpdateRepository(androidContext(), BuildConfig.VERSION_NAME) }
    single { ReleaseSource("Heartline/${BuildConfig.VERSION_NAME} (Android)") }
    single { Updater(androidContext(), get(), get(), BuildConfig.VERSION_NAME, enabled = BuildConfig.UPDATER) }
    single { RemoteLogs(send = { get<PhoneSyncEngine>().requestLogs(it) }) }
    // The watch's finished log segments and raw sensor sessions, moved here as they're done.
    single { WatchLogArchive(java.io.File(androidContext().filesDir, "watch-logs"), WATCH_ARCHIVE_BYTES) }
    single { WatchLogInbox(get()) { ack -> get<PhoneSyncEngine>().ackArchive(ack) } }
    single {
        DiagnosticsRepository(
            androidContext(),
            get(),
            get(),
            BuildConfig.VERSION_NAME,
            get(),
            sendSettings = { settings -> get<PhoneSyncEngine>().sendSettings(settings) },
            archive = get(),
        )
    }
    single {
        PhoneLogExporter(
            androidContext(),
            get(),
            get(),
            get(),
            archive = get(),
            records = get(),
            bpSessions = java.io.File(androidContext().filesDir, BP_SESSIONS),
            bpData = { get<BpRepository>().diagnosticsFiles() },
        )
    }
    single { get<HeartlineDatabase>().bp() }
    single {
        // PaPaGei is loaded on first use (only once there are enough cuff checks to train on).
        val papagei by lazy { PapageiEmbedder.fromAssets(androidContext()) }
        BpRepository(
            get(),
            get(),
            onSafety = { get<PhoneNotifier>().bpSafety(it) },
            embedders = { listOfNotNull(MorphologyEmbedder, papagei) },
            sessionsDir = java.io.File(androidContext().filesDir, BP_SESSIONS),
        ) { get() }
    }
    single { ProfileRepository(androidContext()) { get() } }
    single {
        val records = get<RecordRepository>()
        PhoneSyncEngine(
            get(),
            // Blood-pressure readings are also handed to the BP repository (refinement, safety notice).
            object : RecordSink by records {
                override suspend fun save(meta: RecordMeta, wave: FloatArray?) {
                    records.save(meta, wave)
                    runCatching { get<BpRepository>().onRecordSaved(meta, wave) }.onFailure { HLog.w("Heartline/BP", "refine failed", it) }
                    runCatching { get<EcgSecondOpinion>().onRecordSaved(records, meta, wave) }.onFailure { HLog.w("Heartline/ECG", "second opinion failed", it) }
                    runCatching { get<ProfileRepository>().onRecordSaved(meta) }.onFailure { HLog.w("Heartline/Body", "weight update failed", it) }
                }
            },
            get<HeartRepository>(),
            onHello = { hello ->
                // The watch says hello on start and on every link check: reply with everything it gates on.
                HLog.i("Heartline/Link", "hello from watch: $hello")
                get<UpdateRepository>().setWatchVersion(hello.appVersion)
                val sync = get<PhoneSyncEngine>()
                // Settings changed on the watch while the phone was away may be newer than ours.
                hello.settings?.let { get<SettingsRepository>().applyRemote(it) }
                sync.sendStatus(get<PhoneStatusPublisher>().current())
                sync.sendSettings(get<SettingsRepository>().current())
                get<BpRepository>().resendCalibration(orNull = true)
                get<ProfileRepository>().resend()
            },
            onCaptureResult = { get<BpRepository>().onCaptureResult(it) },
            onSetupRequest = { get<PhoneNotifier>().setupRequest(it.target) },
            onSettings = { incoming ->
                if (get<SettingsRepository>().applyRemote(incoming)) {
                    HLog.i("Heartline/Settings", "changed on watch: $incoming")
                    Reminders.sync(androidContext(), incoming)
                }
            },
            // Raw blood-pressure session logs (every sensor), kept for the export.
            onSessionLog = { id, bytes -> get<BpRepository>().saveSession(id, bytes) },
            onLogs = { requestId, text -> get<RemoteLogs>().onLogs(requestId, text) },
            onLogSegment = { requestId, input -> get<RemoteLogs>().onSegment(requestId, input) },
            onArchive = { type, name, input -> get<WatchLogInbox>().receive(type, name, input) },
        )
    }
    single { PhoneStatusPublisher(get(), get(), get(), { get() }) }
    single { RemoteOpener(androidContext(), get()) }
    single { WatchOpener(get(), get()) { get() } }
    factory {
        val ctx = androidContext()
        RecordFormatter(
            ctx.getString(R.string.date_today),
            ctx.getString(R.string.date_yesterday),
            ctx.getString(R.string.home_bp_calibration_left).replace("%1\$d", "%d"),
            ctx.getString(R.string.bp_needs_calibration),
        )
    }
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { EcgListViewModel(get(), get()) }
    single { EcgReportBuilder(androidContext()) }
    viewModel { params -> EcgDetailViewModel(params.get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get()) { Reminders.sync(androidContext(), it) } }
    viewModel {
        HeartRateViewModel(
            get(),
            get(),
            profile = get<ProfileRepository>().profile.map { it?.age() to it?.calcSex },
            settings = get<SettingsRepository>().monitor,
            watchLimits = get<SettingsRepository>().heartLimits,
        )
    }
    viewModel { UpdatesViewModel(get(), get(), BuildConfig.VERSION_NAME) }
    viewModel { DiagnosticsViewModel(get(), get(), get()) }
    viewModel { BpHomeViewModel(get(), get()) }
    viewModel { CalibrationViewModel(get(), openOnWatch = { get<WatchOpener>().open(it) }) }
    viewModel { params -> MetricDetailViewModel(params.get(), get(), get()) }
    viewModel { BodyCompositionViewModel(get(), get(), get()) }
    viewModel { ProfileViewModel(get()) }
    viewModel { OpenOnWatchViewModel(get()) }
    viewModel { WatchLinkViewModel(get(), get()) }
    viewModel { com.heartline.phone.ui.share.ShareViewModel(get(), get()) }
    viewModel { OnboardingViewModel(get()) }
    single { DataExporter(androidContext()) }
    single { com.heartline.phone.widget.WidgetDataSource(get(), get(), get(), { get() }, profiles = get(), settings = get()) }
    single { com.heartline.phone.widget.WidgetUpdater(androidContext(), get(), get(), get(), get(), get()) }
    viewModel {
        AlertsViewModel(
            get(),
            get(),
            get<RecordRepository>().observe(RecordKind.ECG).map { list ->
                list.mapNotNull { r -> (r.summary as? RecordSummary.Ecg)?.result?.let { r.entity.startedAtMs to it } }
            },
        )
    }
}
