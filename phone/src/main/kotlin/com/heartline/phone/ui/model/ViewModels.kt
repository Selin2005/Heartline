// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.model

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.BpRepository
import com.heartline.phone.export.DataExporter
import com.heartline.phone.data.HeartRepository
import com.heartline.shared.model.RecordSummary
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.SettingsRepository
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.model.Metric
import com.heartline.phone.data.ProfileRepository
import com.heartline.phone.ui.settings.SettingChange
import com.heartline.phone.link.OpenResult
import com.heartline.phone.link.PhoneStatusPublisher
import com.heartline.phone.link.WatchLinkUi
import com.heartline.phone.link.WatchOpener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import com.heartline.phone.report.EcgReportBuilder
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.Symptom
import com.heartline.shared.sync.PhoneSyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WHILE_SUBSCRIBED = SharingStarted.WhileSubscribed(5_000)

/** ECG list for the ECG home and history screens; the latest record also carries its waveform. */
@OptIn(ExperimentalCoroutinesApi::class)
class EcgListViewModel(private val repository: RecordRepository, private val formatter: RecordFormatter) : ViewModel() {
    val state: StateFlow<EcgListState> = repository.observe(RecordKind.ECG)
        .flatMapLatest { records ->
            flow {
                val latestWave = records.firstOrNull()?.let { repository.displayWave(it) }
                emit(
                    EcgListState(
                        records.mapIndexed { i, r -> formatter.ecg(r, if (i == 0) latestWave else null) },
                        loading = false,
                    ),
                )
            }
        }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, EcgListState())
}

@OptIn(ExperimentalCoroutinesApi::class)
class EcgDetailViewModel(
    private val id: String,
    private val repository: RecordRepository,
    private val sync: PhoneSyncEngine,
    private val formatter: RecordFormatter,
) : ViewModel() {
    val state: StateFlow<EcgRecordUi?> = repository.observe(id)
        .flatMapLatest { record -> flow { emit(record?.let { formatter.ecg(it, repository.displayWave(it)) }) } }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED, null)

    fun updateSymptoms(symptoms: List<Symptom>) = viewModelScope.launch { repository.updateEcgSymptoms(id, symptoms, null) }

    /** Deletes here and asks the watch to drop its copy too. */
    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        sync.requestDelete(id)
        onDone()
    }
}

class HomeViewModel(
    repository: RecordRepository,
    heart: HeartRepository,
    settings: SettingsRepository,
    bp: BpRepository,
    formatter: RecordFormatter,
    profiles: com.heartline.phone.data.ProfileRepository? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val profile = profiles?.profile ?: kotlinx.coroutines.flow.flowOf(null)

    private val others = combine(
        repository.observe(RecordKind.SPO2),
        repository.observe(RecordKind.SKIN_TEMPERATURE),
        repository.observe(RecordKind.BODY_COMPOSITION),
        repository.observe(RecordKind.STRESS),
    ) { spo2, temp, body, stress ->
        buildMap {
            listOf(Metric.SPO2 to spo2, Metric.SKIN_TEMPERATURE to temp, Metric.BODY_COMPOSITION to body, Metric.STRESS to stress).forEach { (metric, list) ->
                MetricFormat.readings(list.take(8), formatter).firstOrNull()?.let { r ->
                    put(metric, TileValue(r.value, r.unit, "${r.date} ${r.time}", r.details.firstOrNull()?.second))
                }
            }
        }
    }

    val state: StateFlow<HomeState> = combine(
        repository.observe(RecordKind.ECG),
        heart.latestMinute,
        settings.monitor,
        combine(bp.readings, bp.calibration) { r, c -> r to c },
        others,
    ) { records, minute, monitor, (bpReadings, calibration), otherTiles ->
        val latest = records.firstOrNull()
        val tiles = buildMap {
            putAll(otherTiles)
            bpReadings.firstOrNull()?.let { r ->
                val s = r.summary as RecordSummary.BloodPressure
                val detail = if (calibration?.isValid(now()) == true) {
                    formatter.calibrationDaysLeft(calibration.daysLeft(now()))
                } else {
                    formatter.calibrationNeeded
                }
                put(
                    Metric.BLOOD_PRESSURE,
                    TileValue("${s.systolic}/${s.diastolic}", "mmHg", "${formatter.date(r.entity.startedAtMs)} ${formatter.time(r.entity.startedAtMs)}", detail),
                )
            }
            minute?.let {
                put(Metric.HEART_RATE, TileValue("${it.avgBpm}", "bpm", "${formatter.date(it.minuteStartMs)} ${formatter.time(it.minuteStartMs)}"))
            }
        }
        HomeState(
            latestEcg = latest?.let { formatter.ecg(it, repository.displayWave(it)) },
            tiles = tiles,
            irregularRhythmNotifications = monitor.irregularRhythmEnabled,
        )
    }.combine(profile) { home, p ->
        val local = java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.systemDefault())
        home.copy(
            name = p?.displayName?.takeIf { it.isNotBlank() },
            dayPart = com.heartline.shared.profile.DayPart.of(local.hour),
            birthday = p?.isBirthday(local.toLocalDate()) == true,
        )
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED, HomeState())
}

class SettingsViewModel(
    private val repository: RecordRepository,
    private val heart: HeartRepository,
    private val bp: BpRepository,
    private val settings: SettingsRepository,
    private val sync: PhoneSyncEngine,
    private val onApplied: (MonitorSettings) -> Unit = {},
) : ViewModel() {
    val monitor: StateFlow<MonitorSettings> = settings.monitor.stateIn(viewModelScope, WHILE_SUBSCRIBED, MonitorSettings())

    val sharing: StateFlow<SettingsRepository.SharingPrefs> = settings.sharing.stateIn(viewModelScope, WHILE_SUBSCRIBED, SettingsRepository.SharingPrefs())

    fun setAiPrompt(prompt: String?) = viewModelScope.launch { settings.setAiPrompt(prompt) }

    fun setAiAttachPdf(on: Boolean) = viewModelScope.launch { settings.setAiAttachPdf(on) }

    fun setReportName(choice: com.heartline.shared.profile.ReportName) = viewModelScope.launch { settings.setReportName(choice) }

    /** Saves the change here, pushes it to the watch and re-arms the phone reminders. */
    fun change(change: SettingChange) = viewModelScope.launch {
        val next = settings.update { change.applyTo(it) }
        sync.sendSettings(next)
        onApplied(next)
    }

    fun export(exporter: DataExporter, onReady: (Intent) -> Unit) = viewModelScope.launch {
        val file = withContext(Dispatchers.IO) { exporter.export(repository.all()) }
        onReady(exporter.shareIntent(file))
    }

    /** The CSV under a chosen name, for the share sheet. */
    suspend fun exportFile(exporter: DataExporter, fileName: String) = withContext(Dispatchers.IO) { exporter.export(repository.all(), fileName) }

    fun deleteAll() = viewModelScope.launch {
        repository.deleteAll()
        heart.deleteAll()
        bp.deleteAll()
    }
}

/** "Record on watch" buttons: asks the watch to show a tap-to-open notification. */
/** "Measure on watch" buttons: opens the screen on the watch and reports how it went. */
class OpenOnWatchViewModel(private val opener: WatchOpener) : ViewModel() {
    private val results = MutableSharedFlow<OpenResult>(extraBufferCapacity = 4)
    val events: SharedFlow<OpenResult> = results.asSharedFlow()

    fun open(route: String) = viewModelScope.launch { results.emit(opener.open(route)) }
}

/** The watch connection card (Home, onboarding). Re-probed whenever the screen asks. */
class WatchLinkViewModel(private val opener: WatchOpener, status: PhoneStatusPublisher) : ViewModel() {
    private val probe = MutableStateFlow<WatchLinkUi?>(null)
    val link: StateFlow<WatchLinkUi?> = probe.asStateFlow()
    val setupComplete: StateFlow<Boolean> = status.status.map { it.setupComplete }.stateIn(viewModelScope, WHILE_SUBSCRIBED, false)

    fun refresh() = viewModelScope.launch { probe.value = opener.probe() }

    fun openWatchApp() = viewModelScope.launch { opener.open("") }
}

class OnboardingViewModel(private val settings: SettingsRepository) : ViewModel() {
    val onboarded: StateFlow<Boolean?> = settings.onboarded.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Null while loading; the accepted terms version otherwise (0 = never accepted). */
    val acceptedTerms: StateFlow<Int?> = settings.acceptedTermsVersion.map<Int, Int?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun finish() = viewModelScope.launch { settings.setOnboarded() }

    fun acceptTerms() = viewModelScope.launch { settings.acceptTerms() }
}
