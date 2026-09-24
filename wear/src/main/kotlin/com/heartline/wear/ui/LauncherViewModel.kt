package com.heartline.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sensor.MetricRequirements
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.ui.screens.LauncherEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface LauncherState {
    data object Loading : LauncherState

    data class Ready(val entries: List<LauncherEntry>) : LauncherState

    data class Problem(val problem: SensorProblem, val resolvable: Boolean) : LauncherState
}

/** Launcher list: only metrics this watch's capabilities support, with their last value. */
class LauncherViewModel(private val gateway: SensorGateway, store: WatchRecordStore) : ViewModel() {
    val state: StateFlow<LauncherState> = combine(gateway.state, store.recent) { gatewayState, recent ->
        when (gatewayState) {
            is GatewayState.Connected -> LauncherState.Ready(
                MetricRequirements.supportedMetrics(gatewayState.trackers).map { metric ->
                    LauncherEntry(metric, recent.firstOrNull { it.kind.metric == metric }?.let(::lastValue))
                },
            )
            is GatewayState.Failed -> LauncherState.Problem(gatewayState.problem, gatewayState.resolvable)
            else -> LauncherState.Loading
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherState.Loading)

    fun connect() = gateway.connect()

    companion object {
        fun lastValue(meta: RecordMeta): String? = when (val s = meta.summary) {
            is RecordSummary.Ecg -> s.averageBpm?.let { "$it bpm" }
            is RecordSummary.BloodPressure -> "${s.systolic}/${s.diastolic}"
            is RecordSummary.Spo2 -> "${s.percent}%"
            is RecordSummary.SkinTemperature -> "%.1f °C".format(s.skinCelsius)
            is RecordSummary.BodyComposition -> "%.1f%%".format(s.bodyFatPercent)
            is RecordSummary.Stress -> "${s.score}/100"
        }
    }
}

