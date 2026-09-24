package com.heartline.wear.sensor.sdk

import android.app.Activity
import android.content.Context
import android.util.Log
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.sensor.SensorProblem
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.TrackerUserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Health Sensor Service connection via Samsung Health Sensor SDK 1.4.1. */
class SdkSensorGateway(private val context: Context) : SensorGateway {
    private val mutable = MutableStateFlow<GatewayState>(GatewayState.Disconnected)
    override val state: StateFlow<GatewayState> = mutable.asStateFlow()

    private var service: HealthTrackingService? = null
    private var lastException: HealthTrackerException? = null

    private val listener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            val capability = service?.trackingCapability
            val trackers = capability?.supportHealthTrackerTypes.orEmpty().mapNotNull { it.toKind() }.toSet()
            Log.i(TAG, "Connected; service=${capability?.version} trackers=$trackers")
            mutable.value = GatewayState.Connected(trackers, capability?.version)
        }

        override fun onConnectionEnded() {
            mutable.value = GatewayState.Disconnected
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            lastException = e
            val problem = when (e.errorCode) {
                HealthTrackerException.PACKAGE_NOT_INSTALLED -> SensorProblem.SERVICE_MISSING
                HealthTrackerException.OLD_PLATFORM_VERSION -> SensorProblem.SERVICE_OUTDATED
                else -> SensorProblem.NOT_SUPPORTED
            }
            Log.w(TAG, "Connection failed: code=${e.errorCode} resolvable=${e.hasResolution()}", e)
            mutable.value = GatewayState.Failed(problem, e.hasResolution())
        }
    }

    override fun connect() {
        if (mutable.value is GatewayState.Connected || mutable.value == GatewayState.Connecting) return
        mutable.value = GatewayState.Connecting
        service = HealthTrackingService(listener, context.applicationContext).also { it.connectService() }
    }

    override fun disconnect() {
        service?.disconnectService()
        service = null
        mutable.value = GatewayState.Disconnected
    }

    override fun resolve(activity: Activity) {
        lastException?.takeIf { it.hasResolution() }?.resolve(activity)
    }

    fun ppgTracker(types: Set<PpgType>): HealthTracker? = service?.getHealthTracker(HealthTrackerType.PPG_ON_DEMAND, types)

    fun trackerWithProfile(kind: TrackerKind, profile: TrackerUserProfile): HealthTracker? = service?.getHealthTracker(kind.toSdk(), profile)

    /** For the tracker sources (P3+); null until connected. */
    fun tracker(kind: TrackerKind): HealthTracker? = service?.getHealthTracker(kind.toSdk())

    companion object {
        const val TAG = "SensorSdk"

        fun HealthTrackerType.toKind(): TrackerKind? = TrackerKind.entries.firstOrNull { it.name == name }

        fun TrackerKind.toSdk(): HealthTrackerType = HealthTrackerType.valueOf(name)

        fun mapError(error: HealthTracker.TrackerError): SensorProblem = when (error) {
            HealthTracker.TrackerError.PERMISSION_ERROR -> SensorProblem.PERMISSION
            HealthTracker.TrackerError.SDK_POLICY_ERROR -> SensorProblem.SDK_POLICY
        }
    }
}
