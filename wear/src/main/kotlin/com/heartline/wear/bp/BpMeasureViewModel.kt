// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.bp

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.bp.BpConfirmation
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.MeasurementContext
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.bp.PulseRate
import com.heartline.shared.bp.UnsteadyReason
import com.heartline.shared.dsp.StreamingPpgFilter
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.CaptureResult
import com.heartline.shared.sync.Protocol
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.MotionMeter
import com.heartline.wear.sensor.PpgSource
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
import kotlinx.serialization.encodeToString
import java.util.UUID

sealed interface BpState {
    data object Idle : BpState

    data object NeedsCalibration : BpState

    /** [trace]: filtered, upright PPG for the sweep; [bpm]: live pulse. */
    data class Measuring(
        val progress: Float,
        val secondsLeft: Int,
        val trace: FloatArray,
        val contact: Boolean,
        val bpm: Int? = null,
        val endIndex: Long = trace.size.toLong(),
    ) : BpState

    /**
     * [beyondCalibration]: an extrapolation (shown, with its wider ±). [confirmed]: a second
     * reading within 10 minutes agreed. [safety]: very high or low, check with a cuff.
     * Algorithm 5: [rangeOnly] shows systolic/diastolic ± their uncertainty as a range without a
     * category; [notValidated]: a condition (pregnancy) cuffless readings aren't validated for;
     * [ectopicBeats]: premature beats that were left out.
     */
    data class Done(
        val systolic: Int,
        val diastolic: Int,
        val pulse: Int,
        val category: BpCategory,
        val uncertainty: Int = 0,
        val beyondCalibration: Boolean = false,
        val confirmed: Boolean = false,
        val safety: BpSafety = BpSafety.NONE,
        val rangeOnly: Boolean = false,
        val uncertaintyDia: Int = 0,
        val notValidated: Boolean = false,
        val ectopicBeats: Int = 0,
    ) : BpState {
        val needsConfirming get() = (beyondCalibration || safety != BpSafety.NONE) && !confirmed
    }

    /**
     * Algorithm 5: the body wasn't in the steady state the calibration holds for, so no number is
     * given (pulse-wave analysis reads these states as falsely high). [lowPressureSuspected]: the
     * pattern fits a drop in pressure.
     */
    data class Unsteady(val reason: UnsteadyReason, val lowPressureSuspected: Boolean = false) : BpState

    /** The recording isn't a trustworthy pulse wave (never used for a real change in pressure). */
    data object OutOfRange : BpState

    /** The arm moved during the recording. */
    data object Moving : BpState

    data class CalibrationRecorded(val round: Int) : BpState

    data object PoorSignal : BpState

    data class Failed(val problem: SensorProblem) : BpState
}

/**
 * One blood-pressure session: 20 s of green PPG while resting. In measure mode the features are
 * turned into an estimate with the active calibration; in calibration mode they are sent to the
 * phone, where they are paired with the user's cuff reading.
 */
class BpMeasureViewModel(
    private val source: PpgSource,
    private val bpStore: WatchBpStore,
    private val records: WatchRecordStore,
    private val sync: SyncScheduler,
    private val now: () -> Long = System::currentTimeMillis,
    private val seconds: Int = 20,
    private val uiIntervalMs: Long = 40,
    private val motion: MotionMeter = MotionMeter.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow<BpState>(BpState.Idle)
    val state: StateFlow<BpState> = mutable.asStateFlow()
    private var job: Job? = null

    /** Calibration capture requested by the phone, if any. */
    val pendingCapture: StateFlow<CaptureRequest?> get() = bpStore.pendingCapture

    fun checkReady() {
        if (bpStore.pendingCapture.value == null && bpStore.calibration.value?.isValid(now()) != true) mutable.value = BpState.NeedsCalibration
    }

    fun start() {
        if (job?.isActive == true) return
        val capture = bpStore.pendingCapture.value
        if (capture == null && bpStore.calibration.value?.isValid(now()) != true) {
            mutable.value = BpState.NeedsCalibration
            return
        }
        val fs = source.sampleRateHz
        // Known atrial fibrillation: a longer recording, so enough similar beats are found.
        val profile = bpStore.calibration.value?.profile
        val recordSeconds = if (capture == null && profile?.atrialFibrillation == true) maxOf(seconds, profile.recordingSeconds) else seconds
        val target = fs * recordSeconds
        val buffer = FloatArray(target)
        var collected = 0
        var lastUi = 0L
        val startedAt = now()
        val live = LivePpg(fs)
        mutable.value = BpState.Measuring(0f, recordSeconds, FloatArray(0), contact = true)
        motion.start()
        job = viewModelScope.launch {
            var failure: SensorProblem? = null
            source.stream()
                .catch { e -> failure = (e as? SensorException)?.problem ?: SensorProblem.NOT_SUPPORTED }
                .takeWhile { collected < target }
                .collect { chunk ->
                    live.add(chunk.samples)
                    if (chunk.contact) {
                        val n = minOf(chunk.samples.size, target - collected)
                        chunk.samples.copyInto(buffer, collected, 0, n)
                        collected += n
                    }
                    val t = now()
                    if (t - lastUi >= uiIntervalMs) {
                        lastUi = t
                        mutable.value = BpState.Measuring(
                            collected.toFloat() / target,
                            (target - collected + fs - 1) / fs,
                            live.recent(3.0),
                            chunk.contact,
                            live.bpm(t),
                            live.total,
                        )
                    }
                }
            val movement = motion.stop()
            val gravity = motion.gravity()
            failure?.let {
                mutable.value = BpState.Failed(it)
                return@launch
            }
            if (movement != null && movement > MotionMeter.MAX_STILL) {
                Log.i(TAG, "BP: moved during recording (${"%.2f".format(movement)} m/s²)")
                mutable.value = BpState.Moving
                return@launch
            }
            val recording = buffer.copyOf(collected)
            mutable.value = if (capture != null) {
                val features = withContext(Dispatchers.Default) { PpgFeatures.extract(recording, fs) }
                Log.i(TAG, "BP calibration round ${capture.round}: samples=$collected features=$features")
                finishCalibration(capture, features, recording, gravity)
            } else {
                finishMeasurement(recording, startedAt, recordSeconds, MeasurementContext(gravity))
            }
        }
    }

    private suspend fun finishCalibration(
        capture: CaptureRequest,
        features: com.heartline.shared.bp.PpgFeatureVector?,
        ppg: FloatArray,
        gravity: List<Double>?,
    ): BpState {
        if (features == null || features.quality < BpEstimator.MIN_QUALITY || features.beats < BpEstimator.MIN_BEATS) return BpState.PoorSignal
        val result = CaptureResult(UUID.randomUUID().toString(), capture.captureId, capture.round, features, ppg.toList(), gravity)
        records.enqueueMessage(result.id, Protocol.BP_CALIBRATION_CAPTURE, Protocol.json.encodeToString(result).encodeToByteArray())
        sync.schedule()
        bpStore.setPendingCapture(null)
        return BpState.CalibrationRecorded(capture.round)
    }

    private suspend fun finishMeasurement(recording: FloatArray, startedAt: Long, durationSeconds: Int, context: MeasurementContext): BpState {
        val (features, outcome) = withContext(Dispatchers.Default) {
            BpEstimator.estimateRecording(bpStore.calibration.value, recording, source.sampleRateHz, now(), bpStore.history, context)
        }
        Log.i(TAG, "BP measurement: samples=${recording.size} features=$features outcome=$outcome")
        return when (outcome) {
            BpOutcome.NeedsCalibration -> BpState.NeedsCalibration
            BpOutcome.PoorSignal -> BpState.PoorSignal
            is BpOutcome.OutOfRange -> BpState.OutOfRange
            is BpOutcome.Unsteady -> BpState.Unsteady(outcome.reason, outcome.lowPressureSuspected)
            is BpOutcome.Ok -> {
                val e = outcome.estimate
                val t = now()
                val previous = bpStore.lastReading
                val confirmed = previous != null && BpConfirmation.confirms(previous.toEstimate(), previous.atMs, e, t)
                bpStore.lastReading = LastBpReading.of(e, t)
                if (!e.beyondCalibration && features != null) bpStore.addHistory(features)
                val meta = RecordMeta(
                    UUID.randomUUID().toString(),
                    RecordKind.BLOOD_PRESSURE,
                    startedAt,
                    durationSeconds * 1000L,
                    source.sampleRateHz,
                    recording.size,
                    RecordSummary.BloodPressure(
                        e.systolic,
                        e.diastolic,
                        e.pulse,
                        e.uncertaintySys,
                        algorithm = ALGORITHM,
                        beyondCalibration = e.beyondCalibration,
                        confirmed = confirmed,
                        rangeOnly = e.rangeOnly,
                    ),
                )
                // The raw pulse wave goes to the phone too: it lets the phone's personal model refine
                // the reading and lets the algorithm be re-evaluated on real data later.
                records.add(meta, recording)
                sync.schedule()
                BpState.Done(
                    e.systolic,
                    e.diastolic,
                    e.pulse,
                    BpCategory.of(e.systolic, e.diastolic),
                    e.uncertaintySys,
                    e.beyondCalibration,
                    confirmed,
                    e.safety,
                    rangeOnly = e.rangeOnly,
                    uncertaintyDia = e.uncertaintyDia,
                    notValidated = e.notValidated,
                    ectopicBeats = e.ectopicBeats,
                )
            }
        }
    }

    fun cancel() {
        if (job?.isActive == true) motion.stop()
        job?.cancel()
        mutable.value = BpState.Idle
    }

    fun reset() {
        mutable.value = BpState.Idle
    }

    /** Band-passed PPG, flipped upright when the raw signal is inverted, and a live pulse. */
    private class LivePpg(private val fs: Int) {
        private val filter = StreamingPpgFilter(fs)
        private val ring = FloatArray(fs * 8)
        var total = 0L
            private set
        private var inverted: Boolean? = null
        private var lastBpmAt = 0L
        private var bpm: Int? = null

        fun add(samples: FloatArray) {
            filter.process(samples).forEach {
                ring[(total % ring.size).toInt()] = it
                total++
            }
            // Decide the polarity once there's enough signal; raw watch PPG is usually upside down.
            if (inverted == null && total >= fs * 4) inverted = PpgFeatures.isInverted(raw(4.0))
        }

        private fun raw(seconds: Double): FloatArray {
            val n = (seconds * fs).toInt().coerceAtMost(minOf(total, ring.size.toLong()).toInt())
            return FloatArray(n) { k -> ring[((total - n + k) % ring.size).toInt()] }
        }

        fun recent(seconds: Double): FloatArray = raw(seconds).let { x -> if (inverted == true) FloatArray(x.size) { -x[it] } else x }

        fun bpm(nowMs: Long): Int? {
            if (nowMs - lastBpmAt >= 1_000) {
                lastBpmAt = nowMs
                bpm = PulseRate.bpm(recent(6.0), fs) ?: bpm
            }
            return bpm
        }
    }

    private companion object {
        const val TAG = "Heartline/BP"
        const val ALGORITHM = 5
    }
}
