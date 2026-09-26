// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sensor

import android.app.Activity
import com.heartline.shared.sensor.TrackerKind
import kotlinx.coroutines.flow.StateFlow

/** Problems the user can act on. */
enum class SensorProblem {
    NOT_SUPPORTED,
    PERMISSION,
    SERVICE_MISSING,
    SERVICE_OUTDATED,
    SDK_POLICY,
    OFF_BODY,
}

sealed interface GatewayState {
    data object Disconnected : GatewayState

    data object Connecting : GatewayState

    data class Connected(val trackers: Set<TrackerKind>, val serviceVersion: String?) : GatewayState

    data class Failed(val problem: SensorProblem, val resolvable: Boolean) : GatewayState
}

/**
 * Single connection to Health Sensor Service. The only implementation that touches the Samsung
 * SDK is [com.heartline.wear.sensor.sdk.SdkSensorGateway]; everything else sees this interface.
 */
interface SensorGateway {
    val state: StateFlow<GatewayState>

    fun connect()

    fun disconnect()

    /** Opens the store to install/update Health Sensor Service when the failure allows it. */
    fun resolve(activity: Activity)

    /**
     * Connects and briefly starts a tracker to find problems before the user picks a feature:
     * most importantly SDK_POLICY (Health Platform developer mode off). null means ready.
     */
    suspend fun probeHealth(): SensorProblem?
}
