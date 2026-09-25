package com.heartline.wear.sensor.sdk

import android.util.Log
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.Sex
import com.heartline.shared.profile.UserProfile
import com.heartline.shared.sensor.TrackerKind
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.QuickEvent
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.sensor.QuickSource
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.TrackerUserProfile
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Shared plumbing for on-demand SDK trackers: connect, check capability, attach a listener,
 * run a progress ticker, and translate each batch of DataPoints with [onData].
 *
 * While [problem] is set (fingers off the keys, wrist contact…) the ticker pauses and keeps
 * showing that hint, so the screen neither counts down without a signal nor flickers between
 * "Measuring" and the hint. When the tracker reports its own progress ([sdkProgress]), that
 * drives the ring. Without a result after [timeoutSeconds], the measurement ends with the hint.
 */
abstract class SdkQuickSource(
    private val gateway: SdkSensorGateway,
    private val tracker: TrackerKind,
    override val metric: Metric,
    override val kind: RecordKind,
    override val seconds: Int,
    private val timeoutSeconds: Int = seconds * 4,
) : QuickSource {
    @Volatile protected var problem: QuickHint? = null

    @Volatile protected var sdkProgress: Float? = null

    protected abstract fun create(profile: UserProfile?): HealthTracker?

    /** @return true when the measurement has finished (result or failure sent). */
    protected abstract fun ProducerScope<QuickEvent>.onData(points: List<DataPoint>): Boolean

    override fun measure(profile: UserProfile?): Flow<QuickEvent> = callbackFlow {
        problem = null
        sdkProgress = null
        gateway.connect()
        val state = withTimeout(10_000) { gateway.state.first { it is GatewayState.Connected || it is GatewayState.Failed } }
        if (state is GatewayState.Failed) throw SensorException(state.problem)
        if (tracker !in (state as GatewayState.Connected).trackers) throw SensorException(SensorProblem.NOT_SUPPORTED)
        val healthTracker = create(profile) ?: throw SensorException(SensorProblem.NOT_SUPPORTED)
        val ticker = launch {
            var good = 0
            for (tick in 1..timeoutSeconds * 4) {
                delay(250)
                val hint = problem
                if (hint == null) good++
                val fraction = sdkProgress ?: (good / (seconds * 4f))
                trySend(QuickEvent.Progress(fraction.coerceIn(0f, 0.98f), hint))
            }
            Log.w(SdkSensorGateway.TAG, "$tracker: no result after $timeoutSeconds s (hint=$problem)")
            trySend(QuickEvent.Failed(null, problem ?: QuickHint.LOW_SIGNAL))
            close()
        }
        healthTracker.setEventListener(
            object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(points: List<DataPoint>) {
                    if (points.isNotEmpty() && onData(points)) {
                        ticker.cancel()
                        close()
                    }
                }

                override fun onFlushCompleted() = Unit

                override fun onError(error: HealthTracker.TrackerError) {
                    Log.w(SdkSensorGateway.TAG, "$tracker error: $error")
                    close(SensorException(SdkSensorGateway.mapError(error)))
                }
            },
        )
        awaitClose {
            ticker.cancel()
            healthTracker.unsetEventListener()
        }
    }
}

/** SPO2_ON_DEMAND: status 0 calculating, 2 complete, -4 moved, -5 low signal, -6 timeout. */
class SdkSpo2Source(private val gateway: SdkSensorGateway) :
    SdkQuickSource(gateway, TrackerKind.SPO2_ON_DEMAND, Metric.SPO2, RecordKind.SPO2, 30) {
    override fun create(profile: UserProfile?) = gateway.tracker(TrackerKind.SPO2_ON_DEMAND)

    override fun ProducerScope<QuickEvent>.onData(points: List<DataPoint>): Boolean {
        val p = points.last()
        return when (p.getValue(ValueKey.SpO2Set.STATUS)) {
            2 -> {
                trySendBlocking(QuickEvent.Result(RecordSummary.Spo2(p.getValue(ValueKey.SpO2Set.SPO2) ?: 0, p.getValue(ValueKey.SpO2Set.HEART_RATE), false)))
                true
            }
            -4 -> {
                problem = QuickHint.HOLD_STILL
                false
            }
            0 -> {
                problem = null
                // Still calculating: the heart rate is already known, show it live.
                p.getValue(ValueKey.SpO2Set.HEART_RATE)?.takeIf { it > 0 }?.let { trySendBlocking(QuickEvent.Live(it)) }
                false
            }
            -5 -> {
                trySendBlocking(QuickEvent.Failed(null, QuickHint.LOW_SIGNAL))
                true
            }
            -6 -> {
                trySendBlocking(QuickEvent.Failed(null, QuickHint.WRIST_CONTACT))
                true
            }
            else -> false
        }
    }
}

/** SKIN_TEMPERATURE_ON_DEMAND: status 0 normal, -1 error. */
class SdkSkinTempSource(private val gateway: SdkSensorGateway) :
    SdkQuickSource(gateway, TrackerKind.SKIN_TEMPERATURE_ON_DEMAND, Metric.SKIN_TEMPERATURE, RecordKind.SKIN_TEMPERATURE, 10) {
    override fun create(profile: UserProfile?) = gateway.tracker(TrackerKind.SKIN_TEMPERATURE_ON_DEMAND)

    override fun ProducerScope<QuickEvent>.onData(points: List<DataPoint>): Boolean {
        val p = points.last()
        if (p.getValue(ValueKey.SkinTemperatureSet.STATUS) != 0) {
            trySendBlocking(QuickEvent.Failed(null, QuickHint.WRIST_CONTACT))
            return true
        }
        val skin = p.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE) ?: return false
        trySendBlocking(QuickEvent.Result(RecordSummary.SkinTemperature(skin, p.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE))))
        return true
    }
}

/**
 * BIA_ON_DEMAND with the user profile (TrackerUserProfile: gender 1 = male, 0 = female).
 * Status 0 success; 4/10 wrist contact; 7/8/9 finger(s) off the keys.
 */
class SdkBiaSource(private val gateway: SdkSensorGateway) :
    SdkQuickSource(gateway, TrackerKind.BIA_ON_DEMAND, Metric.BODY_COMPOSITION, RecordKind.BODY_COMPOSITION, 15) {
    override fun create(profile: UserProfile?): HealthTracker? {
        val p = profile ?: throw SensorException(SensorProblem.NOT_SUPPORTED)
        val sex = p.calcSex ?: throw SensorException(SensorProblem.NOT_SUPPORTED)
        val sdkProfile = TrackerUserProfile.Builder()
            .setAge(p.age() ?: throw SensorException(SensorProblem.NOT_SUPPORTED))
            .setGender(if (sex == Sex.MALE) 1 else 0)
            .setHeight(p.heightCm)
            .setWeight(p.weightKg)
            .build()
        return gateway.trackerWithProfile(TrackerKind.BIA_ON_DEMAND, sdkProfile)
    }

    override fun ProducerScope<QuickEvent>.onData(points: List<DataPoint>): Boolean {
        val p = points.last()
        val status = p.getValue(ValueKey.BiaSet.STATUS)
        val progress = p.getValue(ValueKey.BiaSet.PROGRESS)
        Log.i(SdkSensorGateway.TAG, "BIA status=$status progress=$progress")
        return when (status) {
            0 -> if (progress != null && progress < 100f) {
                problem = null
                sdkProgress = progress / 100f
                false
            } else {
                trySendBlocking(
                    QuickEvent.Result(
                        RecordSummary.BodyComposition(
                            p.getValue(ValueKey.BiaSet.BODY_FAT_RATIO) ?: 0f,
                            p.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS),
                            p.getValue(ValueKey.BiaSet.TOTAL_BODY_WATER),
                            p.getValue(ValueKey.BiaSet.BASAL_METABOLIC_RATE)?.toInt(),
                        ),
                    ),
                )
                true
            }
            7, 8, 9 -> {
                // Fingers off the keys: the watch restarts the measurement once they're back.
                problem = QuickHint.TOUCH_KEYS
                sdkProgress = 0f
                false
            }
            else -> {
                problem = QuickHint.WRIST_CONTACT
                sdkProgress = 0f
                false
            }
        }
    }
}

/** Average skin conductance (µS) over [seconds] from EDA_CONTINUOUS (Watch8+), or null. */
suspend fun SdkSensorGateway.readSkinConductance(seconds: Int = 10): Float? {
    val connected = state.value as? GatewayState.Connected ?: return null
    if (TrackerKind.EDA_CONTINUOUS !in connected.trackers) return null
    val tracker = tracker(TrackerKind.EDA_CONTINUOUS) ?: return null
    val values = mutableListOf<Float>()
    return runCatching {
        withTimeout(seconds * 1000L + 2_000) {
            callbackFlow<Float> {
                tracker.setEventListener(
                    object : HealthTracker.TrackerEventListener {
                        override fun onDataReceived(points: List<DataPoint>) {
                            points.filter { it.getValue(ValueKey.EdaSet.STATUS) == 0 }
                                .mapNotNull { it.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE) }
                                .forEach { trySendBlocking(it) }
                        }

                        override fun onFlushCompleted() = Unit

                        override fun onError(error: HealthTracker.TrackerError) {
                            close()
                        }
                    },
                )
                launch {
                    delay(seconds * 1000L)
                    close()
                }
                awaitClose { tracker.unsetEventListener() }
            }.collect { values += it }
        }
        values.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    }.getOrNull()
}
