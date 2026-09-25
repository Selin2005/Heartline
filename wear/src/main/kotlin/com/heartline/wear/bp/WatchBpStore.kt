package com.heartline.wear.bp

import android.content.Context
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpEstimate
import com.heartline.shared.bp.PpgFeatureVector
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** The previous reading, kept so a second one can confirm it. */
@Serializable
data class LastBpReading(
    val atMs: Long,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val uncertainty: Int,
    val beyondCalibration: Boolean,
    val deltaSystolic: Double
) {
    fun toEstimate() = BpEstimate(systolic, diastolic, pulse, uncertainty, (uncertainty * 0.7).toInt(), beyondCalibration, deltaSystolic)

    companion object {
        fun of(e: BpEstimate, atMs: Long) = LastBpReading(atMs, e.systolic, e.diastolic, e.pulse, e.uncertaintySys, e.beyondCalibration, e.deltaSystolic)
    }
}

/**
 * The active BP calibration (sent by the phone), any pending calibration capture, the features of
 * recent in-range readings (the estimator learns this user's normal spread from them) and the
 * previous reading (for confirmation).
 */
class WatchBpStore(context: Context) {
    private val prefs = context.getSharedPreferences("bp", Context.MODE_PRIVATE)
    private val calibrationState = MutableStateFlow(read<BpCalibration>(KEY_CAL)?.takeUnless { it.isDemo })
    private val captureState = MutableStateFlow(read<CaptureRequest>(KEY_CAPTURE))

    private var historyCache: List<PpgFeatureVector> = read<List<PpgFeatureVector>>(KEY_HISTORY).orEmpty()

    val calibration: StateFlow<BpCalibration?> = calibrationState.asStateFlow()

    val history: List<PpgFeatureVector> get() = historyCache

    var lastReading: LastBpReading?
        get() = read<LastBpReading>(KEY_LAST)
        set(value) {
            prefs.edit().putString(KEY_LAST, value?.let { Protocol.json.encodeToString(it) }).apply()
        }

    /** Keeps the latest [MAX_HISTORY] in-range readings' features (without their pulse shape, to stay small). */
    fun addHistory(features: PpgFeatureVector) {
        historyCache = (historyCache + features.copy(shape = emptyList())).takeLast(MAX_HISTORY)
        prefs.edit().putString(KEY_HISTORY, Protocol.json.encodeToString(historyCache)).apply()
    }
    val pendingCapture: StateFlow<CaptureRequest?> = captureState.asStateFlow()

    private inline fun <reified T> read(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { Protocol.json.decodeFromString<T>(it) }.getOrNull() }

    /** A synthetic calibration from an old debug phone build is never used: it pinned every reading. */
    fun setCalibration(incoming: BpCalibration?) {
        val value = incoming?.takeUnless { it.isDemo }
        // A new calibration (not just the same one with added cuff checks) starts a fresh history.
        if (value?.id != calibrationState.value?.id) {
            historyCache = emptyList()
            prefs.edit().remove(KEY_HISTORY).remove(KEY_LAST).apply()
        }
        prefs.edit().putString(KEY_CAL, value?.let { Protocol.json.encodeToString(it) }).apply()
        calibrationState.value = value
    }

    fun setPendingCapture(value: CaptureRequest?) {
        prefs.edit().putString(KEY_CAPTURE, value?.let { Protocol.json.encodeToString(it) }).apply()
        captureState.value = value
    }

    private val BpCalibration.isDemo get() = id.startsWith("demo")

    private companion object {
        const val KEY_CAL = "calibration"
        const val KEY_CAPTURE = "capture"
        const val KEY_HISTORY = "history"
        const val KEY_LAST = "last"
        const val MAX_HISTORY = 30
    }
}
