package com.heartline.phone.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.BpRepository
import com.heartline.shared.bp.BpCalibration
import com.heartline.phone.data.BpValidationEntity
import com.heartline.shared.bp.BpPair
import com.heartline.shared.bp.BpAccuracy
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

data class BpReadingUi(
    val id: String,
    val date: String,
    val time: String,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?,
    val uncertainty: Int? = null,
) {
    val category get() = BpCategory.of(systolic, diastolic)
}

data class BpHomeUi(
    val calibrated: Boolean = false,
    val daysLeft: Int = 0,
    val latest: BpReadingUi? = null,
    val readings: List<BpReadingUi> = emptyList(),
    val average7: Pair<Int, Int>? = null,
    val average30: Pair<Int, Int>? = null,
    /** Watch-vs-cuff agreement from validation checks, if any were done. */
    val accuracy: BpAccuracy? = null,
    /** The latest reading can still be compared with a cuff (recent and not yet compared). */
    val canValidateLatest: Boolean = false,
)

class BpHomeViewModel(
    private val repository: BpRepository,
    formatter: RecordFormatter,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    val state: StateFlow<BpHomeUi> = combine(repository.calibration, repository.readings, repository.validations) { calibration, records, validations ->
        val readings = records.mapNotNull { r ->
            val s = r.summary as? RecordSummary.BloodPressure ?: return@mapNotNull null
            BpReadingUi(r.id, formatter.date(r.entity.startedAtMs), formatter.time(r.entity.startedAtMs), s.systolic, s.diastolic, s.pulse, s.uncertainty) to
                r.entity.startedAtMs
        }
        val latest = readings.firstOrNull()
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
            accuracy = BpAccuracy.of(validations.map { BpPair(it.watchSystolic, it.watchDiastolic, it.cuffSystolic, it.cuffDiastolic) }),
            canValidateLatest = latest != null && now() - latest.second <= VALIDATION_WINDOW_MS && validations.none { it.readingId == latest.first.id },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BpHomeUi())

    /** Stores a cuff reading taken right after the latest watch reading. @return false for implausible values. */
    fun validateLatest(systolic: Int?, diastolic: Int?): Boolean {
        val latest = state.value.latest ?: return false
        if (systolic == null || diastolic == null || systolic !in 70..250 || diastolic !in 40..150 || systolic <= diastolic + 10) return false
        viewModelScope.launch {
            repository.addValidation(BpValidationEntity(newId(), latest.id, now(), latest.systolic, latest.diastolic, systolic, diastolic))
        }
        return true
    }

    private companion object {
        /** A cuff reading only says something about a watch reading taken within the last half hour. */
        const val VALIDATION_WINDOW_MS = 30 * 60_000L
    }
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
    /** Opens the calibration screen on the watch right away (it then runs each round by itself). */
    private val openOnWatch: suspend (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val mutable = MutableStateFlow(CalibrationUi())
    val state: StateFlow<CalibrationUi> = mutable.asStateFlow()
    private val captureId = newId()
    private val points = mutableListOf<CalibrationPoint>()
    private var captured: PpgFeatureVector? = null
    private var capturedPpg: List<Float>? = null

    init {
        viewModelScope.launch {
            repository.captureResults.collect { result ->
                val ui = mutable.value
                if (result.captureId == captureId && result.round == ui.round && ui.phase == CalibrationUi.Phase.WAITING_FOR_WATCH) {
                    captured = result.features
                    capturedPpg = result.ppg
                    mutable.value = ui.copy(phase = CalibrationUi.Phase.ENTER_CUFF)
                }
            }
        }
    }

    /** Asks the watch to record this round; the open calibration screen there starts measuring at once. */
    fun startRound() = viewModelScope.launch {
        mutable.value = mutable.value.copy(phase = CalibrationUi.Phase.WAITING_FOR_WATCH, inputError = false)
        repository.requestCapture(CaptureRequest(captureId, mutable.value.round))
        openOnWatch(CALIBRATION_ROUTE)
    }

    fun submitCuff(systolic: Int?, diastolic: Int?, pulse: Int?) = viewModelScope.launch {
        val features = captured
        val valid = systolic != null && diastolic != null && systolic in 70..250 && diastolic in 40..150 && systolic > diastolic + 10
        if (features == null || !valid) {
            mutable.value = mutable.value.copy(inputError = true)
            return@launch
        }
        points += CalibrationPoint(features, systolic!!, diastolic!!, pulse, capturedPpg)
        captured = null
        capturedPpg = null
        val ui = mutable.value
        if (points.size < BpCalibration.REQUIRED_POINTS) {
            mutable.value = CalibrationUi(round = ui.round + 1, phase = CalibrationUi.Phase.WAITING_FOR_WATCH, completedRounds = points.size)
            repository.requestCapture(CaptureRequest(captureId, ui.round + 1))
            openOnWatch(CALIBRATION_ROUTE)
        } else {
            repository.saveCalibration(BpCalibration(newId(), now(), points.toList()))
            mutable.value = ui.copy(phase = CalibrationUi.Phase.DONE, completedRounds = points.size, inputError = false)
        }
    }

    private companion object {
        const val CALIBRATION_ROUTE = "bp_calibration"
    }
}
