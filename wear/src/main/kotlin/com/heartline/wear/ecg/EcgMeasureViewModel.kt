package com.heartline.wear.ecg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.ecg.EcgRecorder
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.sensor.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface EcgMeasureState {
    data object Idle : EcgMeasureState

    data class Measuring(val progress: Float, val secondsLeft: Int, val trace: FloatArray, val leadOff: Boolean) : EcgMeasureState

    data object Analyzing : EcgMeasureState

    data class Done(val id: String, val result: EcgResult, val averageBpm: Int?) : EcgMeasureState

    data class Failed(val problem: SensorProblem) : EcgMeasureState
}

/**
 * Runs one 30-second ECG: streams from [source] into an [EcgRecorder], analyses the recording,
 * stores it in the outbox and schedules delivery to the phone.
 */
class EcgMeasureViewModel(
    private val source: EcgSource,
    private val store: WatchRecordStore,
    private val sync: SyncScheduler,
    private val now: () -> Long = System::currentTimeMillis,
    private val uiIntervalMs: Long = 40,
) : ViewModel() {
    private val mutable = MutableStateFlow<EcgMeasureState>(EcgMeasureState.Idle)
    val state: StateFlow<EcgMeasureState> = mutable.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val recorder = EcgRecorder(source.sampleRateHz)
        val startedAt = now()
        var lastUi = 0L
        mutable.value = EcgMeasureState.Measuring(0f, recorder.secondsLeft, FloatArray(0), leadOff = false)
        job = viewModelScope.launch {
            var failure: SensorProblem? = null
            source.stream()
                .catch { e -> failure = (e as? SensorException)?.problem ?: SensorProblem.NOT_SUPPORTED }
                .takeWhile { !recorder.isComplete && !recorder.isAbandoned }
                .collect { chunk ->
                    recorder.accept(chunk.samples, chunk.leadOff)
                    val t = now()
                    if (t - lastUi >= uiIntervalMs || recorder.isComplete) {
                        lastUi = t
                        mutable.value = EcgMeasureState.Measuring(recorder.progress, recorder.secondsLeft, recorder.recent(), recorder.leadOff)
                    }
                }
            failure?.let {
                mutable.value = EcgMeasureState.Failed(it)
                return@launch
            }
            mutable.value = EcgMeasureState.Analyzing
            mutable.value = finish(recorder, startedAt)
        }
    }

    fun cancel() {
        job?.cancel()
        mutable.value = EcgMeasureState.Idle
    }

    fun reset() {
        mutable.value = EcgMeasureState.Idle
    }

    private suspend fun finish(recorder: EcgRecorder, startedAt: Long): EcgMeasureState {
        val recording = recorder.recording()
        val analysis = withContext(Dispatchers.Default) {
            EcgAnalyzer.analyze(recording, recorder.sampleRateHz, if (recorder.isComplete) recorder.leadOffRatio else 1f)
        }
        val id = UUID.randomUUID().toString()
        val meta = RecordMeta(
            id = id,
            kind = RecordKind.ECG,
            startedAtMs = startedAt,
            durationMs = recording.size * 1000L / recorder.sampleRateHz,
            sampleRateHz = recorder.sampleRateHz,
            sampleCount = recording.size,
            summary = RecordSummary.Ecg(analysis.averageBpm, analysis.result, recorder.leadOffRatio),
        )
        // A recording abandoned for lost contact is shown as poor but not stored.
        if (recorder.isComplete) {
            store.add(meta, recording)
            sync.schedule()
        }
        return EcgMeasureState.Done(id, analysis.result, analysis.averageBpm)
    }
}
