package com.heartline.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.wear.BuildConfig
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.ui.screens.WatchSettingsUi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class WatchSettingsViewModel(gateway: SensorGateway, settings: WatchSettingsStore) : ViewModel() {
    val state: StateFlow<WatchSettingsUi> = combine(gateway.state, settings.settings) { g, s ->
        val connected = g as? GatewayState.Connected
        WatchSettingsUi(
            irregularRhythm = s.irregularRhythmEnabled,
            heartRateAlerts = s.heartRateAlertsEnabled,
            serviceVersion = connected?.serviceVersion,
            trackers = connected?.trackers?.map { it.name }?.sorted().orEmpty(),
            appVersion = BuildConfig.VERSION_NAME,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchSettingsUi(true, true, null, emptyList(), BuildConfig.VERSION_NAME))
}
