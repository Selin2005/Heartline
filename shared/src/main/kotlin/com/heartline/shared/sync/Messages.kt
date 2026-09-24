package com.heartline.shared.sync

import com.heartline.shared.model.Metric
import kotlinx.serialization.Serializable

@Serializable
data class Hello(
    val protocol: Int = Protocol.VERSION,
    val appVersion: String,
    val capabilities: List<Metric> = emptyList(),
    val sensorServiceVersion: String? = null
) {
    fun isCompatible() = protocol == Protocol.VERSION
}

@Serializable
data class Ack(val id: String, val ok: Boolean)

@Serializable
data class DeleteRecord(val id: String)

/** Phone → watch: take calibration round [round] (1..3) for wizard [captureId]. */
@Serializable
data class CaptureRequest(val captureId: String, val round: Int)

/** Watch → phone: PPG features recorded for a calibration round. */
@Serializable
data class CaptureResult(val id: String, val captureId: String, val round: Int, val features: com.heartline.shared.bp.PpgFeatureVector)
