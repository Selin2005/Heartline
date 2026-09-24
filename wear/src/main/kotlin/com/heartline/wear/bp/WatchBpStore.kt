package com.heartline.wear.bp

import android.content.Context
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.sync.CaptureRequest
import com.heartline.shared.sync.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/** The active BP calibration (sent by the phone) and any pending calibration capture. */
class WatchBpStore(context: Context) {
    private val prefs = context.getSharedPreferences("bp", Context.MODE_PRIVATE)
    private val calibrationState = MutableStateFlow(read<BpCalibration>(KEY_CAL))
    private val captureState = MutableStateFlow(read<CaptureRequest>(KEY_CAPTURE))

    val calibration: StateFlow<BpCalibration?> = calibrationState.asStateFlow()
    val pendingCapture: StateFlow<CaptureRequest?> = captureState.asStateFlow()

    private inline fun <reified T> read(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { Protocol.json.decodeFromString<T>(it) }.getOrNull() }

    fun setCalibration(value: BpCalibration?) {
        prefs.edit().putString(KEY_CAL, value?.let { Protocol.json.encodeToString(it) }).apply()
        calibrationState.value = value
    }

    fun setPendingCapture(value: CaptureRequest?) {
        prefs.edit().putString(KEY_CAPTURE, value?.let { Protocol.json.encodeToString(it) }).apply()
        captureState.value = value
    }

    private companion object {
        const val KEY_CAL = "calibration"
        const val KEY_CAPTURE = "capture"
    }
}
