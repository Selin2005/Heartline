package com.heartline.wear.sensor

import android.app.Activity
import com.heartline.shared.sensor.TrackerKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Emulates a Galaxy Watch8 (all trackers) for development without hardware. */
class FakeSensorGateway(
    private val trackers: Set<TrackerKind> = TrackerKind.entries.toSet(),
    private val failure: SensorProblem? = null,
) : SensorGateway {
    private val mutable = MutableStateFlow<GatewayState>(GatewayState.Disconnected)
    override val state: StateFlow<GatewayState> = mutable.asStateFlow()

    override fun connect() {
        mutable.value = failure?.let { GatewayState.Failed(it, resolvable = false) }
            ?: GatewayState.Connected(trackers, serviceVersion = "fake")
    }

    override fun disconnect() {
        mutable.value = GatewayState.Disconnected
    }

    override fun resolve(activity: Activity) = Unit

    override suspend fun probeHealth(): SensorProblem? {
        connect()
        return failure
    }
}
