package com.heartline.phone.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.BpRepository
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.PpgFeatureVector
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.CaptureRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

data class BpReadingUi(val id: String, val date: String, val time: String, val systolic: Int, val diastolic: Int, val pulse: Int?) {
    val category get() = BpCategory.of(systolic, diastolic)
}

data class BpHomeUi(
    val calibrated: Boolean = false,
    val daysLeft: Int = 0,
    val latest: BpReadingUi? = null,
    val readings: List<BpReadingUi> = emptyList(),
    val average7: Pair<Int, Int>? = null,
    val average30: Pair<Int, Int>? = null,
)

class BpHomeViewModel(repository: BpRepository, formatter: RecordFormatter, private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    val state: StateFlow<BpHomeUi> = combine(repository.calibration, repository.readings) { calibration, records ->
        val readings = records.mapNotNull { r ->
            val s = r.summary as? RecordSummary.BloodPressure ?: return@mapNotNull null
            BpReadingUi(r.id, formatter.date(r.entity.startedAtMs), formatter.time(r.entity.startedAtMs), s.systolic, s.diastolic, s.pulse) to
                r.entity.startedAtMs
        }
        fun average(days: Int): Pair<Int, Int>? {
            val window = readings.filter { now() - it.second <= days * BpCalibration.DAY_MS }.map { it.first }
            if (window.isEmpty()) return null
            return window.map { it.systolic }.average().roundToInt() to window.map { it.diastolic }.average().roundToInt()
        }
        BpHomeUi(
            calibrated = calibration?.isValid(now()) == true,
            daysLeft = calibration?.daysLeft(now()) ?: 0,
            latest = readings.firstOrNull()?.first,
            readings = readings.map { it.first },
            average7 = average(7),
            average30 = average(30),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BpHomeUi())
}

/** State of the 3-round cuff calibration wizard (MASTER_PLAN F7). */
data class CalibrationUi(
    val round: Int = 1,
    val phase: Phase = Phase.INTRO,
    val completedRounds: Int = 0,
    val inputError: Boolean = false,
) {
    enum class Phase { INTRO, WAITING_FOR_WATCH, ENTER_CUFF, DONE }
}

class CalibrationViewModel(
    private val repository: BpRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val mutable = MutableStateFlow(CalibrationUi())
    val state: StateFlow<CalibrationUi> = mutable.asStateFlow()
    private val captureId = newId()
    private val points = mutableListOf<CalibrationPoint>()
    private var captured: PpgFeatureVector? = null

    init {
        viewModelScope.launch {
            repository.captureResults.collect { result ->
                val ui = mutable.value
                if (result.captureId == captureId && result.round == ui.round && ui.phase == CalibrationUi.Phase.WAITING_FOR_WATCH) {
                    captured = result.features
                    mutable.value = ui.copy(phase = CalibrationUi.Phase.ENTER_CUFF)
                }
            }
        }
    }

    /** Asks the watch to record this round (the user then taps the watch notification). */
    fun startRound() = viewModelScope.launch {
        mutable.value = mutable.value.copy(phase = CalibrationUi.Phase.WAITING_FOR_WATCH, inputError = false)
        repository.requestCapture(CaptureRequest(captureId, mutable.value.round))
    }

    fun submitCuff(systolic: Int?, diastolic: Int?, pulse: Int?) = viewModelScope.launch {
        val features = captured
        val valid = systolic != null && diastolic != null && systolic in 70..250 && diastolic in 40..150 && systolic > diastolic + 10
        if (features == null || !valid) {
            mutable.value = mutable.value.copy(inputError = true)
            return@launch
        }
        points += CalibrationPoint(features, systolic!!, diastolic!!, pulse)
        captured = null
        val ui = mutable.value
        if (points.size < BpCalibration.REQUIRED_POINTS) {
            mutable.value = CalibrationUi(round = ui.round + 1, phase = CalibrationUi.Phase.WAITING_FOR_WATCH, completedRounds = points.size)
            repository.requestCapture(CaptureRequest(captureId, ui.round + 1))
        } else {
            repository.saveCalibration(BpCalibration(newId(), now(), points.toList()))
            mutable.value = ui.copy(phase = CalibrationUi.Phase.DONE, completedRounds = points.size, inputError = false)
        }
    }
}
