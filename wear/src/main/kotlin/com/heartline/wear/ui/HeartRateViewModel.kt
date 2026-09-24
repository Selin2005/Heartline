package com.heartline.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.wear.sensor.HrSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

data class HeartRateState(val bpm: Int? = null, val recent: List<Int> = emptyList(), val onBody: Boolean = true)

/** Live heart rate while the screen is open; the tracker stops when nobody is subscribed. */
class HeartRateViewModel(source: HrSource) : ViewModel() {
    val state: StateFlow<HeartRateState> = source.stream()
        .catch { }
        .runningFold(HeartRateState()) { acc, sample ->
            if (!sample.onBody) {
                acc.copy(onBody = false)
            } else if (sample.bpm <= 0) {
                acc
            } else {
                HeartRateState(sample.bpm, (acc.recent + sample.bpm).takeLast(30), onBody = true)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), HeartRateState())
}
