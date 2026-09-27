// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor.sdk

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.PpgChunk
import com.heartline.wear.sensor.PpgPoint
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

/**
 * PPG_ON_DEMAND at 100 Hz: green, plus infrared and red when the watch offers them (they reach
 * deeper tissue and are logged and used as extra channels; see docs/algorithms/BP_ALGORITHM.md).
 * Falls back to green alone when the multi-wavelength tracker isn't supported.
 */
class SdkPpgSource(private val gateway: SdkSensorGateway) : PpgSource {
    /** Channels the last [stream] actually opened, for the session log. */
    @Volatile var channels: Set<PpgType> = emptySet()
        private set

    override fun stream(): Flow<PpgChunk> = callbackFlow {
        gateway.connect()
        val state = withTimeout(10_000) { gateway.state.first { it is GatewayState.Connected || it is GatewayState.Failed } }
        if (state is GatewayState.Failed) throw SensorException(state.problem)
        if (TrackerKind.PPG_ON_DEMAND !in (state as GatewayState.Connected).trackers) throw SensorException(SensorProblem.NOT_SUPPORTED)
        val all = setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)
        val (tracker, types) = runCatching { gateway.ppgTracker(all) }.getOrNull()?.let { it to all }
            ?: (gateway.ppgTracker(setOf(PpgType.GREEN)) ?: throw SensorException(SensorProblem.NOT_SUPPORTED)) to setOf(PpgType.GREEN)
        channels = types
        HLog.i(SdkSensorGateway.TAG, "PPG channels: $types")
        tracker.setEventListener(
            object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(points: List<DataPoint>) {
                    if (points.isEmpty()) return
                    val raw = points.map { p ->
                        PpgPoint(
                            p.timestamp,
                            p.float(ValueKey.PpgSet.PPG_GREEN),
                            if (PpgType.IR in types) p.float(ValueKey.PpgSet.PPG_IR) else Float.NaN,
                            if (PpgType.RED in types) p.float(ValueKey.PpgSet.PPG_RED) else Float.NaN,
                            p.status(ValueKey.PpgSet.GREEN_STATUS),
                            if (PpgType.IR in types) p.status(ValueKey.PpgSet.IR_STATUS) else -1,
                            if (PpgType.RED in types) p.status(ValueKey.PpgSet.RED_STATUS) else -1,
                        )
                    }
                    // Points without a green value are skipped: a substituted 0 would be a huge fake pulse.
                    val kept = raw.filter { !it.green.isNaN() }
                    if (kept.isEmpty()) return
                    // Status 0 is a normal reading; anything else means poor contact.
                    val contact = kept.all { it.greenStatus <= 0 }
                    trySendBlocking(
                        PpgChunk(
                            kept.map { it.green }.toFloatArray(),
                            contact,
                            ir = if (PpgType.IR in types) kept.map { it.ir }.toFloatArray() else null,
                            red = if (PpgType.RED in types) kept.map { it.red }.toFloatArray() else null,
                            points = raw,
                        ),
                    )
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

    private fun DataPoint.float(key: ValueKey<Int>): Float = runCatching { getValue(key)?.toFloat() }.getOrNull() ?: Float.NaN

    private fun DataPoint.status(key: ValueKey<Int>): Int = runCatching { getValue(key) }.getOrNull() ?: 0
}
