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
import com.samsung.android.service.health.tracking.data.DataPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    override suspend fun probeHealth(): SensorProblem? {
        connect()
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { state.first { it is GatewayState.Connected || it is GatewayState.Failed } }
        val result = when (connected) {
            null -> SensorProblem.NOT_SUPPORTED
            is GatewayState.Failed -> connected.problem
            is GatewayState.Connected -> {
                // Any tracker surfaces the policy/permission errors; heart rate exists on every Galaxy Watch.
                val kind = PROBE_TRACKERS.firstOrNull { it in connected.trackers }
                val tracker = kind?.let { runCatching { tracker(it) }.getOrNull() }
                if (tracker == null) null else probe(tracker)
            }
            else -> SensorProblem.NOT_SUPPORTED
        }
        Log.i(TAG, "health probe -> ${result ?: "OK"}")
        return result
    }

    /** Listens briefly: an error means a problem; data or silence means the tracker is allowed to run. */
    private suspend fun probe(tracker: HealthTracker): SensorProblem? = withContext(Dispatchers.Main) {
        val outcome = CompletableDeferred<SensorProblem?>()
        tracker.setEventListener(
            object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(points: List<DataPoint>) {
                    outcome.complete(null)
                }

                override fun onFlushCompleted() = Unit

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.w(TAG, "probe tracker error: $error")
                    outcome.complete(mapError(error))
                }
            },
        )
        try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) { outcome.await() }
        } finally {
            runCatching { tracker.unsetEventListener() }
        }
    }

    fun ppgTracker(types: Set<PpgType>): HealthTracker? = service?.getHealthTracker(HealthTrackerType.PPG_ON_DEMAND, types)

    fun trackerWithProfile(kind: TrackerKind, profile: TrackerUserProfile): HealthTracker? = service?.getHealthTracker(kind.toSdk(), profile)

    /** For the tracker sources (P3+); null until connected. */
    fun tracker(kind: TrackerKind): HealthTracker? = service?.getHealthTracker(kind.toSdk())

    companion object {
        const val TAG = "Heartline/Sensor"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 5_000L
        private val PROBE_TRACKERS = listOf(TrackerKind.HEART_RATE_CONTINUOUS, TrackerKind.PPG_CONTINUOUS, TrackerKind.ECG_ON_DEMAND)

        fun HealthTrackerType.toKind(): TrackerKind? = TrackerKind.entries.firstOrNull { it.name == name }

        fun TrackerKind.toSdk(): HealthTrackerType = HealthTrackerType.valueOf(name)

        fun mapError(error: HealthTracker.TrackerError): SensorProblem = when (error) {
            HealthTracker.TrackerError.PERMISSION_ERROR -> SensorProblem.PERMISSION
            HealthTracker.TrackerError.SDK_POLICY_ERROR -> SensorProblem.SDK_POLICY
        }
    }
}
