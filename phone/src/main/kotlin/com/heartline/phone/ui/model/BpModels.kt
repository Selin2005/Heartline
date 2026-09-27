// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.BpRepository
import com.heartline.shared.bp.BpCalibration
import com.heartline.phone.data.BpValidationEntity
import com.heartline.shared.bp.BpPair
import com.heartline.shared.bp.BpAccuracy
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.bp.BpConformal
import com.heartline.shared.bp.BpDrift
import com.heartline.shared.bp.BpProfile
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.bp.CalibrationPoint
import com.heartline.shared.bp.PpgFeatureVector
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.Protocol
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    /** Outside what the calibration covered: an extrapolation. */
    val beyondCalibration: Boolean = false,
    val confirmed: Boolean = false,
    /** Refined by the phone's personal model; [watchSystolic]/[watchDiastolic] is what the watch showed. */
    val watchSystolic: Int? = null,
    val watchDiastolic: Int? = null,
    /** Algorithm 6: the channels fused (comma-separated BpChannel names) and the body's state. */
    val channels: String? = null,
    val bodyState: String? = null,
) {
    val category get() = BpCategory.of(systolic, diastolic)
    val safety get() = BpSafety.of(systolic, diastolic)
    val refined get() = watchSystolic != null
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
    /** Recent readings suggest pressure has moved away from the calibration: ask for a cuff check. */
    val drift: BpDrift.Direction? = null,
    /** Cuff checks keep disagreeing the same way: a fresh calibration is due. */
    val recalibrate: Boolean = false,
    /** Cuff systolic range the calibration has seen (base rounds plus cuff checks). */
    val calibrationSpan: IntRange? = null,
    val cuffChecksInCalibration: Int = 0,
    /** ± that covered 80 % of this user's cuff checks (split-conformal), once there are enough. */
    val personalRange80: Int? = null,
    /** Conditions and medicines that change the model (algorithm 5), stored with the calibration. */
    val profile: BpProfile = BpProfile.NONE,
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
            BpReadingUi(
                r.id,
                formatter.date(r.entity.startedAtMs),
                formatter.time(r.entity.startedAtMs),
                s.systolic,
                s.diastolic,
                s.pulse,
                s.uncertainty,
                s.beyondCalibration,
                s.confirmed,
                s.watchSystolic,
                s.watchDiastolic,
                s.channels,
                s.bodyState,
            ) to r.entity.startedAtMs
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
            drift = calibration?.takeIf { it.isValid(now()) }?.let { cal ->
                // Readings since the last cuff point that keep landing beyond the calibration, the same way.
                val since = cal.timedPoints().maxOf { it.second }
                val reference = cal.timedPoints().map { it.first.cuffSystolic }.average()
                BpDrift.fromReadings(readings.filter { it.second > since }.reversed().map { (ui, _) -> ui.beyondCalibration to ui.systolic - reference })
            },
            recalibrate = calibration?.takeIf { it.isValid(now()) }?.let { cal ->
                // Cuff checks that keep disagreeing the same way although each one was added to the calibration.
                BpDrift.fromResiduals(validations.filter { it.atMs >= cal.createdAtMs }.sortedBy { it.atMs }.map { (it.watchSystolic - it.cuffSystolic).toDouble() }) != null
            } ?: false,
            calibrationSpan = calibration?.takeIf { it.isValid(now()) }?.systolicSpan,
            cuffChecksInCalibration = calibration?.extraPoints?.size ?: 0,
            personalRange80 = BpConformal.halfWidth(validations.map { (it.watchSystolic - it.cuffSystolic).toDouble() })?.roundToInt(),
            profile = calibration?.profile ?: BpProfile.NONE,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BpHomeUi())

    /** Updates the health profile on the current calibration and sends it to the watch. */
    fun saveProfile(profile: BpProfile) {
        viewModelScope.launch { repository.saveProfile(profile) }
    }

    /** Stores a cuff reading taken right after the latest watch reading. @return false for implausible values. */
    fun validateLatest(systolic: Int?, diastolic: Int?): Boolean {
        val latest = state.value.latest ?: return false
        if (systolic == null || diastolic == null || systolic !in 70..250 || diastolic !in 40..150 || systolic <= diastolic + 10) return false
        viewModelScope.launch {
            repository.addValidation(BpValidationEntity(newId(), latest.id, now(), latest.systolic, latest.diastolic, systolic, diastolic))
        }
        return true
    }

    /** The user's BP data (calibration and cuff-checked readings with raw PPG) as JSON, for offline analysis. */
    /** Every raw session log with the calibration and cuff checks, as a zip (see [BpRepository.exportSessions]). */
    suspend fun exportSessions(dir: java.io.File, fileName: String): java.io.File = repository.exportSessions(java.io.File(dir, fileName))

    suspend fun exportDataset(dir: java.io.File, fileName: String): java.io.File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val data = repository.dataset()
        java.io.File(dir.apply { mkdirs() }, fileName).apply { writeText(data?.let { Protocol.json.encodeToString(it) } ?: "{}") }
    }

    private companion object {
        /** A cuff reading only says something about a watch reading taken within the last half hour. */
        const val VALIDATION_WINDOW_MS = 30 * 60_000L
    }
}

/**
 * State of the cuff calibration wizard: 3 seated rounds, then an optional standing round
 * ([Phase.OFFER_STANDING]) so the model also sees how this user's pulse and pressure respond to
 * standing (algorithm 5). [profile]: the user's conditions and medicines, saved with it.
 */
data class CalibrationUi(
    val round: Int = 1,
    val phase: Phase = Phase.INTRO,
    val completedRounds: Int = 0,
    val inputError: Boolean = false,
    val profile: BpProfile = BpProfile.NONE,
    /** Record the rounds in precise mode (finger on the watch key: ECG + PPG + motion). */
    val precise: Boolean = false,
) {
    enum class Phase { INTRO, WAITING_FOR_WATCH, ENTER_CUFF, OFFER_STANDING, DONE }

    val standingRound: Boolean get() = round == BpCalibration.STANDING_ROUND
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
    private var capturedGravity: List<Double>? = null
    private var capturedChannels: com.heartline.shared.bp.ChannelCapture? = null

    init {
        // A recalibration keeps the profile the user already entered.
        viewModelScope.launch {
            repository.calibration.first()?.profile?.let { p -> if (mutable.value.phase == CalibrationUi.Phase.INTRO) mutable.value = mutable.value.copy(profile = p) }
        }
        viewModelScope.launch {
            repository.captureResults.collect { result ->
                val ui = mutable.value
                if (result.captureId == captureId && result.round == ui.round && ui.phase == CalibrationUi.Phase.WAITING_FOR_WATCH) {
                    captured = result.features
                    capturedPpg = result.ppg
                    capturedGravity = result.gravity
                    capturedChannels = result.capture
                    mutable.value = ui.copy(phase = CalibrationUi.Phase.ENTER_CUFF)
                }
            }
        }
    }

    fun setProfile(profile: BpProfile) {
        mutable.value = mutable.value.copy(profile = profile)
    }

    fun setPrecise(precise: Boolean) {
        mutable.value = mutable.value.copy(precise = precise)
    }

    /** Asks the watch to record this round; the open calibration screen there starts measuring at once. */
    fun startRound() = viewModelScope.launch {
        mutable.value = mutable.value.copy(phase = CalibrationUi.Phase.WAITING_FOR_WATCH, inputError = false)
        repository.requestCapture(CaptureRequest(captureId, mutable.value.round, mutable.value.precise))
        openOnWatch(CALIBRATION_ROUTE)
    }

    fun submitCuff(systolic: Int?, diastolic: Int?, pulse: Int?) = viewModelScope.launch {
        val features = captured
        val valid = systolic != null && diastolic != null && systolic in 70..250 && diastolic in 40..150 && systolic > diastolic + 10
        if (features == null || !valid) {
            mutable.value = mutable.value.copy(inputError = true)
            return@launch
        }
        val ui = mutable.value
        // With the watch's full capture every channel gets this cuff point (IR, BCG, ECG transit times).
        points += capturedChannels?.let { CalibrationPoint.of(it, systolic!!, diastolic!!, pulse, standing = ui.standingRound) }
            ?: CalibrationPoint(features, systolic!!, diastolic!!, pulse, capturedPpg, gravity = capturedGravity, standing = ui.standingRound)
        captured = null
        capturedPpg = null
        capturedGravity = null
        capturedChannels = null
        when {
            points.size < BpCalibration.REQUIRED_POINTS -> {
                mutable.value = ui.copy(round = ui.round + 1, phase = CalibrationUi.Phase.WAITING_FOR_WATCH, completedRounds = points.size, inputError = false)
                repository.requestCapture(CaptureRequest(captureId, ui.round + 1, ui.precise))
                openOnWatch(CALIBRATION_ROUTE)
            }
            ui.standingRound -> save()
            else -> mutable.value = ui.copy(phase = CalibrationUi.Phase.OFFER_STANDING, completedRounds = points.size, inputError = false)
        }
    }

    /** The optional 4th round: standing, watch arm across the chest at heart level. */
    fun addStandingRound() = viewModelScope.launch {
        mutable.value = mutable.value.copy(round = BpCalibration.STANDING_ROUND, phase = CalibrationUi.Phase.WAITING_FOR_WATCH, inputError = false)
        repository.requestCapture(CaptureRequest(captureId, BpCalibration.STANDING_ROUND, mutable.value.precise))
        openOnWatch(CALIBRATION_ROUTE)
    }

    /** Saves the calibration without the standing round. */
    fun finish() = viewModelScope.launch { save() }

    private suspend fun save() {
        repository.saveCalibration(BpCalibration(newId(), now(), points.toList(), profile = mutable.value.profile))
        mutable.value = mutable.value.copy(phase = CalibrationUi.Phase.DONE, completedRounds = points.size, inputError = false)
    }

    private companion object {
        const val CALIBRATION_ROUTE = "bp_calibration"
    }
}
