package com.heartline.wear.sensor.sdk

import android.util.Log
import com.heartline.shared.hr.HrSample
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.HrSource
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

/** HEART_RATE_CONTINUOUS (sample: track-heart-rate-with-off-body-sensor.html). */
class SdkHrSource(private val gateway: SdkSensorGateway) : HrSource {
    override fun stream(): Flow<HrSample> = callbackFlow {
        gateway.connect()
        val state = withTimeout(10_000) { gateway.state.first { it is GatewayState.Connected || it is GatewayState.Failed } }
        if (state is GatewayState.Failed) throw SensorException(state.problem)
        if (TrackerKind.HEART_RATE_CONTINUOUS !in (state as GatewayState.Connected).trackers) {
            throw SensorException(SensorProblem.NOT_SUPPORTED)
        }
        val tracker = gateway.tracker(TrackerKind.HEART_RATE_CONTINUOUS) ?: throw SensorException(SensorProblem.NOT_SUPPORTED)
        tracker.setEventListener(
            object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(points: List<DataPoint>) {
                    points.forEach { trySendBlocking(it.toSample()) }
                }

                override fun onFlushCompleted() = Unit

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.w(SdkSensorGateway.TAG, "HR tracker error: $error")
                    close(SensorException(SdkSensorGateway.mapError(error)))
                }
            },
        )
        awaitClose { tracker.unsetEventListener() }
    }

    private fun DataPoint.toSample(): HrSample {
        val status = getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) ?: 0
        val ibis = getValue(ValueKey.HeartRateSet.IBI_LIST).orEmpty()
        val ibiStatus = getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST).orEmpty()
        // IBI status 0 marks a reliable interval; others are dropped.
        val good = ibis.filterIndexed { i, _ -> ibiStatus.getOrNull(i) == 0 }
        return HrSample(
            tsMs = timestamp,
            bpm = getValue(ValueKey.HeartRateSet.HEART_RATE) ?: 0,
            ibiMs = good,
            onBody = status != STATUS_OFF_BODY,
        )
    }

    private companion object {
        const val STATUS_OFF_BODY = -3
    }
}
