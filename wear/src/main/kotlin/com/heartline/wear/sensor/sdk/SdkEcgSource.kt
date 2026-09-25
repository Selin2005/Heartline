package com.heartline.wear.sensor.sdk

import android.os.SystemClock
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

        Log.i(EcgRawLog.TAG, "ECG tracker started")
        var missing = 0
        val raw = EcgRawLog()
        tracker.setEventListener(
            object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(points: List<DataPoint>) {
                    if (points.isEmpty()) return
                    // A point without a value is skipped: a substituted 0 mV would be a spike after filtering.
                    val values = points.mapNotNull { it.getValue(ValueKey.EcgSet.ECG_MV)?.takeIf { v -> v.isFinite() } }
                    if (values.size < points.size) missing += points.size - values.size
                    val flags = points.map { it.getValue(ValueKey.EcgSet.LEAD_OFF) }
                    val leadOff = !batchContact(flags)
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
                    raw.batch(points, flags, values, min, max, leadOff, saturated, ppg != null)
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
            raw.end()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}

/**
 * Raw ECG values for device debugging (tag [TAG]): the first [FULL_BATCHES] batches and every batch
 * where contact changes in full (LEAD_OFF of every point, mV range, SDK thresholds, sequence), a
 * summary every second, and totals at the end.
 */
private class EcgRawLog {
    private var batches = 0
    private var lastLeadOff: Boolean? = null
    private var secondStart = 0L
    private val secondFlags = mutableMapOf<Int?, Int>()
    private var secondPoints = 0
    private var secondMissing = 0
    private var secondMin = Float.MAX_VALUE
    private var secondMax = -Float.MAX_VALUE
    private var secondLeadOffBatches = 0
    private var secondBatches = 0
    private val firstFlags = mutableMapOf<Int?, Int>()
    private val otherFlags = mutableMapOf<Int?, Int>()
    private var contactBatches = 0
    private var saturatedBatches = 0

    fun batch(
        points: List<DataPoint>,
        flags: List<Int?>,
        values: List<Float>,
        min: Float?,
        max: Float?,
        leadOff: Boolean,
        saturated: Boolean,
        ppg: Boolean,
    ) {
        batches++
        firstFlags.merge(flags.first(), 1, Int::plus)
        flags.drop(1).forEach { otherFlags.merge(it, 1, Int::plus) }
        if (!leadOff) contactBatches++
        if (saturated) saturatedBatches++
        if (batches <= FULL_BATCHES || leadOff != lastLeadOff) {
            Log.i(
                TAG,
                "batch#$batches n=${points.size} LEAD_OFF=$flags leadOff=$leadOff saturated=$saturated " +
                    "mV=[${values.minOrNull()}..${values.maxOrNull()}] thresholds=[$min..$max] ppg=$ppg " +
                    "seq=${points.first().getValue(ValueKey.EcgSet.SEQUENCE)}..${points.last().getValue(ValueKey.EcgSet.SEQUENCE)} " +
                    "t=${points.first().timestamp}..${points.last().timestamp}",
            )
        }
        lastLeadOff = leadOff
        val now = SystemClock.elapsedRealtime()
        if (secondStart == 0L) secondStart = now
        flags.forEach { secondFlags.merge(it, 1, Int::plus) }
        secondPoints += points.size
        secondMissing += points.size - values.size
        values.minOrNull()?.let { secondMin = minOf(secondMin, it) }
        values.maxOrNull()?.let { secondMax = maxOf(secondMax, it) }
        secondBatches++
        if (leadOff) secondLeadOffBatches++
        if (now - secondStart >= 1_000) {
            Log.i(
                TAG,
                "1s: points=$secondPoints missingMv=$secondMissing LEAD_OFF=$secondFlags " +
                    "leadOffBatches=$secondLeadOffBatches/$secondBatches mV=[$secondMin..$secondMax]",
            )
            secondStart = now
            secondFlags.clear()
            secondPoints = 0
            secondMissing = 0
            secondMin = Float.MAX_VALUE
            secondMax = -Float.MAX_VALUE
            secondLeadOffBatches = 0
            secondBatches = 0
        }
    }

    fun end() {
        Log.i(
            TAG,
            "end: batches=$batches contact=$contactBatches saturated=$saturatedBatches " +
                "LEAD_OFF first point=$firstFlags other points=$otherFlags",
        )
    }

    companion object {
        const val TAG = "Heartline/EcgRaw"
        const val FULL_BATCHES = 20
    }
}
