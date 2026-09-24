package com.heartline.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.hr.MonitorSettings
import com.heartline.wear.BuildConfig
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.ui.screens.WatchSettingsUi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Watch settings: toggles change the shared settings here and sync them to the phone. */
class WatchSettingsViewModel(
    gateway: SensorGateway,
    private val settings: WatchSettingsStore,
    private val onChanged: suspend (MonitorSettings) -> Unit = {},
) : ViewModel() {
    val state: StateFlow<WatchSettingsUi> = combine(gateway.state, settings.settings) { g, s ->
        val connected = g as? GatewayState.Connected
        WatchSettingsUi(
            irregularRhythm = s.irregularRhythmEnabled,
            heartRateAlerts = s.heartRateAlertsEnabled,
            serviceVersion = connected?.serviceVersion,
            trackers = connected?.trackers?.map { it.name }?.sorted().orEmpty(),
            appVersion = BuildConfig.VERSION_NAME,
            backgroundHeartRate = s.backgroundHeartRate,
            haptics = s.haptics,
            liveWave = s.liveWave,
            highBpm = s.highBpm,
            lowBpm = s.lowBpm,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchSettingsUi(true, true, null, emptyList(), BuildConfig.VERSION_NAME))

    fun change(transform: (MonitorSettings) -> MonitorSettings) {
        val next = settings.change(transform)
        viewModelScope.launch { onChanged(next) }
    }
}
