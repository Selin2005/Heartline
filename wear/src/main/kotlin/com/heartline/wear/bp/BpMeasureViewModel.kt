package com.heartline.wear.bp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.bp.BpEstimator
import com.heartline.shared.bp.BpOutcome
import com.heartline.shared.bp.PpgFeatures
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.CaptureResult
import com.heartline.shared.sync.Protocol
import com.heartline.wear.data.WatchRecordStore
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

    data class Measuring(val progress: Float, val secondsLeft: Int, val trace: FloatArray, val contact: Boolean) : BpState

    data class Done(val systolic: Int, val diastolic: Int, val pulse: Int, val category: BpCategory) : BpState

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
        val target = fs * seconds
        val buffer = FloatArray(target)
        var collected = 0
        var lastUi = 0L
        val startedAt = now()
        mutable.value = BpState.Measuring(0f, seconds, FloatArray(0), contact = true)
        job = viewModelScope.launch {
            var failure: SensorProblem? = null
            source.stream()
                .catch { e -> failure = (e as? SensorException)?.problem ?: SensorProblem.NOT_SUPPORTED }
                .takeWhile { collected < target }
                .collect { chunk ->
                    if (chunk.contact) {
                        val n = minOf(chunk.samples.size, target - collected)
                        chunk.samples.copyInto(buffer, collected, 0, n)
                        collected += n
                    }
                    val t = now()
                    if (t - lastUi >= uiIntervalMs) {
                        lastUi = t
                        val from = (collected - fs * 3).coerceAtLeast(0)
                        mutable.value = BpState.Measuring(
                            collected.toFloat() / target,
                            (target - collected + fs - 1) / fs,
                            buffer.copyOfRange(from, collected),
                            chunk.contact,
                        )
                    }
                }
            failure?.let {
                mutable.value = BpState.Failed(it)
                return@launch
            }
            val features = withContext(Dispatchers.Default) { PpgFeatures.extract(buffer.copyOf(collected), fs) }
            mutable.value = if (capture != null) finishCalibration(capture, features) else finishMeasurement(features, startedAt)
        }
    }

    private suspend fun finishCalibration(capture: CaptureRequest, features: com.heartline.shared.bp.PpgFeatureVector?): BpState {
        if (features == null || features.quality < BpEstimator.MIN_QUALITY) return BpState.PoorSignal
        val result = CaptureResult(UUID.randomUUID().toString(), capture.captureId, capture.round, features)
        records.enqueueMessage(result.id, Protocol.BP_CALIBRATION_CAPTURE, Protocol.json.encodeToString(result).encodeToByteArray())
        sync.schedule()
        bpStore.setPendingCapture(null)
        return BpState.CalibrationRecorded(capture.round)
    }

    private suspend fun finishMeasurement(features: com.heartline.shared.bp.PpgFeatureVector?, startedAt: Long): BpState =
        when (val outcome = BpEstimator.estimate(bpStore.calibration.value, features, now())) {
            BpOutcome.NeedsCalibration -> BpState.NeedsCalibration
            BpOutcome.PoorSignal -> BpState.PoorSignal
            is BpOutcome.Ok -> {
                val e = outcome.estimate
                val meta = RecordMeta(
                    UUID.randomUUID().toString(),
                    RecordKind.BLOOD_PRESSURE,
                    startedAt,
                    seconds * 1000L,
                    0,
                    0,
                    RecordSummary.BloodPressure(e.systolic, e.diastolic, e.pulse),
                )
                records.add(meta, null)
                sync.schedule()
                BpState.Done(e.systolic, e.diastolic, e.pulse, BpCategory.of(e.systolic, e.diastolic))
            }
        }

    fun cancel() {
        job?.cancel()
        mutable.value = BpState.Idle
    }

    fun reset() {
        mutable.value = BpState.Idle
    }
}
