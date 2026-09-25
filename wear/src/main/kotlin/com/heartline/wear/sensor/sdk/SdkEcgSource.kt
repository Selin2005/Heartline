package com.heartline.wear.sensor.sdk

import android.util.Log
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.EcgChunk
import com.heartline.wear.sensor.EcgSource
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.sensor.batchContact
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
        var contact = false
        // LEAD_OFF values seen (null included) on the first point of each batch and on the others.
        val firstValues = mutableMapOf<Int?, Int>()
        val otherValues = mutableMapOf<Int?, Int>()
        tracker.setEventListener(
            object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(points: List<DataPoint>) {
                    if (points.isEmpty()) return
                    // A point without a value is skipped: a substituted 0 mV would be a spike after filtering.
                    val values = points.mapNotNull { it.getValue(ValueKey.EcgSet.ECG_MV)?.takeIf { v -> v.isFinite() } }
                    if (values.size < points.size) missing += points.size - values.size
                    // LEAD_OFF comes on the first point of a batch only (batchContact).
                    val flags = points.map { it.getValue(ValueKey.EcgSet.LEAD_OFF) }
                    firstValues.merge(flags.first(), 1, Int::plus)
                    flags.drop(1).forEach { otherValues.merge(it, 1, Int::plus) }
                    contact = batchContact(flags, contact)
                    val leadOff = !contact
                    val max = points.firstNotNullOfOrNull { it.getValue(ValueKey.EcgSet.MAX_THRESHOLD_MV) }
                    val min = points.firstNotNullOfOrNull { it.getValue(ValueKey.EcgSet.MIN_THRESHOLD_MV) }
                    val saturated = values.any { (max != null && it >= max) || (min != null && it <= min) }
                    // The PPG channel reported with each ECG sample (pulse arrival time); only kept when
                    // every point has both, so the two stay sample-aligned.
                    val ppg = if (values.size == points.size) {
                        points.mapNotNull { it.getValue(ValueKey.EcgSet.PPG_GREEN)?.toFloat() }.takeIf { it.size == points.size }?.toFloatArray()
                    } else {
                        null
                    }
                    if (values.isNotEmpty()) trySendBlocking(EcgChunk(values.toFloatArray(), leadOff, points.last().timestamp, ppg, saturated))
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
            Log.i(SdkSensorGateway.TAG, "ECG LEAD_OFF values seen: first point $firstValues, other points $otherValues")
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}
