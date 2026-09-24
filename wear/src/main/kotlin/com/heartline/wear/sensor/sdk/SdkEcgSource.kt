package com.heartline.wear.sensor.sdk

import android.util.Log
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.EcgChunk
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** ECG_ON_DEMAND via the SDK (sample: sdk/official/1.4.1/sample-codes/ecg-monitor.html). */
class SdkEcgSource(private val gateway: SdkSensorGateway) : EcgSource {
    override fun stream(): Flow<EcgChunk> = callbackFlow {
        gateway.connect()
        val state = withTimeout(CONNECT_TIMEOUT_MS) { gateway.state.first { it is GatewayState.Connected || it is GatewayState.Failed } }
        if (state is GatewayState.Failed) throw SensorException(state.problem)
        if (TrackerKind.ECG_ON_DEMAND !in (state as GatewayState.Connected).trackers) throw SensorException(SensorProblem.NOT_SUPPORTED)
        val tracker = gateway.tracker(TrackerKind.ECG_ON_DEMAND) ?: throw SensorException(SensorProblem.NOT_SUPPORTED)

        var missing = 0
        tracker.setEventListener(
            object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(points: List<DataPoint>) {
                    if (points.isEmpty()) return
                    // A point without a value is skipped: a substituted 0 mV would be a spike after filtering.
                    val values = points.mapNotNull { it.getValue(ValueKey.EcgSet.ECG_MV)?.takeIf { v -> v.isFinite() } }
                    if (values.size < points.size) missing += points.size - values.size
                    val leadOff = points.any { it.getValue(ValueKey.EcgSet.LEAD_OFF) == LEAD_OFF_NO_CONTACT }
                    if (values.isNotEmpty()) trySendBlocking(EcgChunk(values.toFloatArray(), leadOff, points.last().timestamp))
                }

                override fun onFlushCompleted() = Unit

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.w(SdkSensorGateway.TAG, "ECG tracker error: $error")
                    close(SensorException(SdkSensorGateway.mapError(error)))
                }
            },
        )
        awaitClose {
            tracker.unsetEventListener()
            if (missing > 0) Log.w(SdkSensorGateway.TAG, "ECG: $missing points had no ECG_MV value")
        }
    }

    private companion object {
        /** LEAD_OFF value when the electrodes aren't both touched (Samsung ECG sample). */
        const val LEAD_OFF_NO_CONTACT = 5
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}
