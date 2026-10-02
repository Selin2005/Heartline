// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.hr.AlertSensitivity
import com.heartline.shared.hr.MonitorSettings
import com.heartline.wear.BuildConfig
import com.heartline.wear.diag.RawCapture
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.ui.screens.WatchSettingsUi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import com.heartline.datalayer.diag.HLog
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
            heartMonitoring = s.heartMonitoring,
            allDayHeartRate = s.passiveHeartRate,
            sensitivity = s.alertSensitivity,
            serviceVersion = connected?.serviceVersion,
            trackers = connected?.trackers?.map { it.name }?.sorted().orEmpty(),
            appVersion = BuildConfig.VERSION_NAME,
            haptics = s.haptics,
            liveWave = s.liveWave,
            limits = settings.limits,
            diagnosticLogs = s.diagnosticLogs,
            logBytes = HLog.sizeBytes() + RawCapture.sizeBytes(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchSettingsUi(true, AlertSensitivity.STANDARD, null, emptyList(), BuildConfig.VERSION_NAME))

    fun change(transform: (MonitorSettings) -> MonitorSettings) {
        val next = settings.change(transform)
        viewModelScope.launch { onChanged(next) }
    }
}
