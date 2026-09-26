// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor.sdk

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.PpgChunk
import com.heartline.wear.sensor.PpgSource
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** PPG_ON_DEMAND with the green channel (100 Hz). */
class SdkPpgSource(private val gateway: SdkSensorGateway) : PpgSource {
    override fun stream(): Flow<PpgChunk> = callbackFlow {
        gateway.connect()
        val state = withTimeout(10_000) { gateway.state.first { it is GatewayState.Connected || it is GatewayState.Failed } }
        if (state is GatewayState.Failed) throw SensorException(state.problem)
        if (TrackerKind.PPG_ON_DEMAND !in (state as GatewayState.Connected).trackers) throw SensorException(SensorProblem.NOT_SUPPORTED)
        val tracker = gateway.ppgTracker(setOf(PpgType.GREEN)) ?: throw SensorException(SensorProblem.NOT_SUPPORTED)
        tracker.setEventListener(
            object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(points: List<DataPoint>) {
                    if (points.isEmpty()) return
                    // Points without a value are skipped: a substituted 0 would be a huge fake pulse.
                    val samples = points.mapNotNull { it.getValue(ValueKey.PpgSet.PPG_GREEN)?.toFloat() }.toFloatArray()
                    if (samples.isEmpty()) return
                    // Status 0 is a normal reading; anything else means poor contact.
                    val contact = points.all { (it.getValue(ValueKey.PpgSet.GREEN_STATUS) ?: 0) == 0 }
                    trySendBlocking(PpgChunk(samples, contact))
                }

                override fun onFlushCompleted() = Unit

                override fun onError(error: HealthTracker.TrackerError) {
                    HLog.w(SdkSensorGateway.TAG, "PPG tracker error: $error")
                    close(SensorException(SdkSensorGateway.mapError(error)))
                }
            },
        )
        awaitClose { tracker.unsetEventListener() }
    }
}
