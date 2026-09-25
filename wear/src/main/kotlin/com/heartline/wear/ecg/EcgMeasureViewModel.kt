package com.heartline.wear.ecg

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.dsp.StreamingEcgFilter
import com.heartline.shared.ecg.EcgAnalyzer
import com.heartline.shared.bp.PulseArrival
import com.heartline.shared.ecg.EcgRecorder
import com.heartline.shared.ecg.EcgSession
import com.heartline.shared.ecg.RPeakDetector
import com.heartline.shared.model.EcgMetrics
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

    /**
     * [trace]: the last seconds of the filtered live signal (shown from the moment measuring starts,
     * even before the finger touches). [bpm]: live heart rate once a few seconds are recorded.
     */
    data class Measuring(
        val progress: Float,
        val secondsLeft: Int,
        val trace: FloatArray,
        val leadOff: Boolean,
        val waitingForTouch: Boolean = false,
        val bpm: Int? = null,
        /** Total live samples so far: positions the sweep's write head. */
        val endIndex: Long = trace.size.toLong(),
    ) : EcgMeasureState

    data object Analyzing : EcgMeasureState

    data class Done(val id: String, val result: EcgResult, val averageBpm: Int?, val metrics: EcgMetrics? = null) : EcgMeasureState

    data class Failed(val problem: SensorProblem) : EcgMeasureState
}

/**
 * Runs one 30-second ECG: streams from [source] into an [EcgRecorder] (and a causal filter for the
 * live strip), analyses the recording, stores it — poor ones too, so they can be reviewed — and
 * schedules delivery to the phone.
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
        val fs = source.sampleRateHz
        val recorder = EcgRecorder(fs)
        val live = LiveStrip(fs)
        val rate = RateMeter()
        val startedAt = now()
        var lastUi = 0L
        var lastBpmAt = 0L
        var bpm: Int? = null
        var touched = false
        val paired = PairedPpg(fs * recorder.targetSeconds)
        mutable.value = EcgMeasureState.Measuring(0f, recorder.secondsLeft, FloatArray(0), leadOff = true, waitingForTouch = true)
        job = viewModelScope.launch {
            var failure: SensorProblem? = null
            source.stream()
                .catch { e -> failure = (e as? SensorException)?.problem ?: SensorProblem.NOT_SUPPORTED }
                .takeWhile { !recorder.isComplete && !recorder.isAbandoned }
                .collect { chunk ->
                    recorder.accept(chunk.samples, chunk.leadOff)
                    if (!chunk.leadOff) paired.add(chunk.samples, chunk.ppg)
                    live.add(chunk.samples, chunk.leadOff)
                    rate.add(chunk.samples.size, chunk.leadOff, chunk.timestampMs)
                    if (!chunk.leadOff) touched = true
                    val t = now()
                    if (!chunk.leadOff && t - lastBpmAt >= 1_000 && recorder.progress * recorder.targetSeconds >= 4) {
                        lastBpmAt = t
                        bpm = liveBpm(live.recent(6.0), fs)
                    }
                    if (chunk.leadOff) bpm = null
                    if (t - lastUi >= uiIntervalMs || recorder.isComplete) {
                        lastUi = t
                        mutable.value = EcgMeasureState.Measuring(
                            recorder.progress,
                            recorder.secondsLeft,
                            live.recent(3.0),
                            recorder.leadOff,
                            waitingForTouch = !touched,
                            bpm = bpm,
                            endIndex = live.total,
                        )
                    }
                }
            failure?.let {
                Log.w(TAG, "ECG failed: $it")
                mutable.value = EcgMeasureState.Failed(it)
                return@launch
            }
            mutable.value = EcgMeasureState.Analyzing
            mutable.value = finish(recorder, startedAt, rate.hz(), paired)
        }
    }

    fun cancel() {
        job?.cancel()
        mutable.value = EcgMeasureState.Idle
    }

    fun reset() {
        mutable.value = EcgMeasureState.Idle
    }

    private suspend fun finish(recorder: EcgRecorder, startedAt: Long, measuredHz: Float?, paired: PairedPpg? = null): EcgMeasureState {
        val recording = recorder.recording()
        val endedAt = now()
        val session = EcgSession(
            startedAtMs = startedAt,
            endedAtMs = endedAt,
            leadOffSec = recorder.leadOffSeconds,
            leadOffRatio = recorder.leadOffRatio,
            measuredRateHz = measuredHz,
        )
        val analysis = withContext(Dispatchers.Default) { EcgAnalyzer.analyze(recording, recorder.sampleRateHz, session) }
        val pat = paired?.takeIf { it.usable }?.let { p -> withContext(Dispatchers.Default) { PulseArrival.compute(p.ecg(), p.ppg(), recorder.sampleRateHz) } }
        Log.i(TAG, "PAT: ppg=${paired?.usable} samples=${paired?.size} result=$pat")
        val m = analysis.metrics.copy(pulseArrivalMs = pat?.medianMs, pulseArrivalBeats = pat?.beats)
        Log.i(
            TAG,
            "ECG done: result=${analysis.result} reason=${m.poorReason} quality=${m.qualityScore} " +
                "duration=${m.durationSec}s usable=${m.usableSec}s motion=${m.motionSec}s muscle=${m.muscleNoiseSec}s " +
                "leadOff=${m.leadOffSec}s beats=${m.beats} hr=${m.averageBpm} (${m.minBpm}-${m.maxBpm}) rmssd=${m.rmssdMs} " +
                "pWave=${analysis.evidence.pWaveRatio} inverted=${m.inverted} rate=${m.sampleRateHz}Hz " +
                "raw[min=${recording.minOrNull()} max=${recording.maxOrNull()} mean=${recording.average()}] noisy=${m.noisySeconds}",
        )
        val id = UUID.randomUUID().toString()
        val meta = RecordMeta(
            id = id,
            kind = RecordKind.ECG,
            startedAtMs = startedAt,
            durationMs = recording.size * 1000L / recorder.sampleRateHz,
            sampleRateHz = recorder.sampleRateHz,
            sampleCount = recording.size,
            summary = RecordSummary.Ecg(analysis.averageBpm, analysis.result, recorder.leadOffRatio, metrics = m),
        )
        // Every recording worth looking at is kept (poor ones too, for review and tuning);
        // only a few seconds of signal from an abandoned attempt are dropped.
        if (recording.size >= MIN_STORED_SEC * recorder.sampleRateHz) {
            store.add(meta, recording)
            sync.schedule()
        }
        return EcgMeasureState.Done(id, analysis.result, analysis.averageBpm, m)
    }

    /** ECG and the PPG channel reported with it, kept sample-aligned (contact stretches only). */
    private class PairedPpg(private val capacity: Int) {
        private val ecg = FloatArray(capacity)
        private val ppg = FloatArray(capacity)
        var size = 0
            private set
        private var missing = false

        val usable get() = !missing && size > 0

        fun add(samples: FloatArray, values: FloatArray?) {
            if (values == null || values.size != samples.size) {
                missing = true
                return
            }
            val n = minOf(samples.size, capacity - size)
            samples.copyInto(ecg, size, 0, n)
            values.copyInto(ppg, size, 0, n)
            size += n
        }

        fun ecg() = ecg.copyOf(size)

        fun ppg() = ppg.copyOf(size)
    }

    private fun liveBpm(recent: FloatArray, fs: Int): Int? {
        if (recent.size < fs * 3) return null
        return RPeakDetector.heartRateBpm(RPeakDetector.detect(recent, fs), fs)?.takeIf { it in 30..220 }
    }

    /** Filtered live signal, including before the first touch (flat while there's no contact). */
    private class LiveStrip(private val fs: Int) {
        private val filter = StreamingEcgFilter(fs)
        private val ring = FloatArray(fs * 8)
        private var count = 0L
        private var wasLeadOff = true

        val total: Long get() = count

        fun add(samples: FloatArray, leadOff: Boolean) {
            if (!leadOff && wasLeadOff) filter.reset()
            wasLeadOff = leadOff
            val values = if (leadOff) FloatArray(samples.size) else filter.process(samples)
            values.forEach {
                ring[(count % ring.size).toInt()] = it
                count++
            }
        }

        fun recent(seconds: Double): FloatArray {
            val n = (seconds * fs).toInt().coerceAtMost(minOf(count, ring.size.toLong()).toInt())
            return FloatArray(n) { k -> ring[((count - n + k) % ring.size).toInt()] }
        }
    }

    /** Real sample rate from sensor timestamps over contact time (the SDK promises 500 Hz). */
    private class RateMeter {
        private var samples = 0L
        private var elapsedMs = 0L
        private var lastTs: Long? = null

        fun add(n: Int, leadOff: Boolean, ts: Long?) {
            val previous = lastTs
            if (!leadOff && ts != null && previous != null && ts > previous && ts - previous < 500) {
                samples += n
                elapsedMs += ts - previous
            }
            lastTs = if (leadOff) null else ts
        }

        fun hz(): Float? = if (elapsedMs < 2_000) null else samples * 1000f / elapsedMs
    }

    private companion object {
        const val TAG = "Heartline/ECG"
        const val MIN_STORED_SEC = 10
    }
}
