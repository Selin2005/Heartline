// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.measure.HeartRateCheck
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.diag.RawCapture
import com.heartline.wear.sensor.HrSource
import com.heartline.wear.sensor.SyncScheduler
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

sealed interface HeartRateCheckState {
    data object Idle : HeartRateCheckState

    /** [offWrist]: the sensor lost the wrist (the progress waits). */
    data class Measuring(val progress: Float, val secondsLeft: Int, val bpm: Int?, val offWrist: Boolean) : HeartRateCheckState

    data class Done(val recordId: String, val summary: RecordSummary.HeartRate) : HeartRateCheckState

    data object TooFewReadings : HeartRateCheckState
}

/** A 30-second heart-rate check on the shared heart-rate tracker, stored as a [RecordKind.HEART_RATE] record. */
class HeartRateCheckViewModel(
    private val source: HrSource,
    private val store: WatchRecordStore,
    private val sync: SyncScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutable = MutableStateFlow<HeartRateCheckState>(HeartRateCheckState.Idle)
    val state: StateFlow<HeartRateCheckState> = mutable.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val startedAt = now()
        val check = HeartRateCheck()
        mutable.value = HeartRateCheckState.Measuring(0f, (HeartRateCheck.DURATION_MS / 1000).toInt(), null, offWrist = false)
        val raw = RawCapture.begin("heart_rate_check")
        job = viewModelScope.launch {
            // The tracker may hand several readings over at once: count time by their own
            // timestamps, not by when they arrive, so the progress neither stalls nor jumps.
            var lastTs: Long? = null
            try {
                source.stream()
                    .catch { }
                    .takeWhile { !check.done && now() - startedAt < HeartRateCheck.TIMEOUT_MS }
                    .collect { sample ->
                        val good = sample.onBody && sample.bpm > 0 && sample.reliable
                        val elapsed = lastTs?.let { sample.tsMs - it } ?: 1_000L
                        check.add(sample.bpm, good, elapsed)
                        lastTs = sample.tsMs
                        mutable.value = HeartRateCheckState.Measuring(
                            check.progress,
                            (((1 - check.progress) * HeartRateCheck.DURATION_MS) / 1000).toInt().coerceAtLeast(1),
                            check.latest,
                            offWrist = !sample.onBody,
                        )
                    }
                val result = check.result()
                if (result == null || !check.done) {
                    mutable.value = HeartRateCheckState.TooFewReadings
                    return@launch
                }
                val summary = RecordSummary.HeartRate(result.bpm, result.min, result.max, result.samples)
                val meta = RecordMeta(UUID.randomUUID().toString(), RecordKind.HEART_RATE, startedAt, now() - startedAt, 0, 0, summary)
                store.add(meta, null)
                sync.schedule()
                mutable.value = HeartRateCheckState.Done(meta.id, summary)
            } finally {
                RawCapture.end(raw, notes = mapOf("result" to mutable.value.toString()))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        mutable.value = HeartRateCheckState.Idle
    }
}
