// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor.sdk

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.HrSamples
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.HrSource
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.diag.RawCapture
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * HEART_RATE_CONTINUOUS (sample: track-heart-rate-with-off-body-sensor.html).
 *
 * The app has one heart-rate tracker, and each listener replaces the previous one. The live screen,
 * stress and the background irregular-rhythm window could otherwise steal it from each other (and
 * the first to finish would stop it for everyone), so all collectors share one listener.
 */
class SdkHrSource(private val gateway: SdkSensorGateway) : HrSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val shared: SharedFlow<Result<HrSample>> = tracked()
        .map { Result.success(it) }
        .catch { emit(Result.failure(it)) }
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    override fun stream(): Flow<HrSample> = shared.map { it.getOrThrow() }

    private fun tracked(): Flow<HrSample> = callbackFlow {
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
                    RawCapture.sdk(TrackerKind.HEART_RATE_CONTINUOUS.name, points)
                    points.forEach { trySendBlocking(it.toSample()) }
                }

                override fun onFlushCompleted() = Unit

                override fun onError(error: HealthTracker.TrackerError) {
                    HLog.w(SdkSensorGateway.TAG, "HR tracker error: $error")
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
        if (ibis.isNotEmpty()) HLog.v(SdkSensorGateway.TAG, "HR status=$status ibis=$ibis ibiStatus=$ibiStatus")
        return HrSamples.fromTracker(timestamp, getValue(ValueKey.HeartRateSet.HEART_RATE) ?: 0, status, ibis, ibiStatus)
    }

}
