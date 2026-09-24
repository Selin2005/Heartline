package com.heartline.wear.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.irn.IrnState
import com.heartline.shared.model.Metric
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.wear.sensor.HrSource
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Background heart monitoring without a permanent notification:
 * - all-day heart rate (trends, high/low alerts) comes from Health Services passive monitoring,
 *   which delivers batches to [PassiveHeartRateService] with no foreground service at all;
 * - irregular rhythm needs beat-to-beat intervals, so [IrnWindowWorker] listens to the Samsung
 *   tracker for about a minute every [MonitorSettings.irnIntervalMinutes].
 */
object BackgroundMonitoring {
    private const val TAG = "Heartline/Monitor"
    private const val IRN_WORK = "heartline-irn-window"

    fun canRun(context: Context): Boolean =
        PermissionPolicy.permissionsFor(Metric.HEART_RATE, Build.VERSION.SDK_INT)
            .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Reading sensors while no Heartline screen is open (asked for separately, after the foreground permission). */
    val backgroundPermission: String
        get() = if (Build.VERSION.SDK_INT >= 36) {
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        } else {
            Manifest.permission.BODY_SENSORS_BACKGROUND
        }

    fun hasBackgroundPermission(context: Context) =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, backgroundPermission) == PackageManager.PERMISSION_GRANTED

    /** Applies [settings]: registers/unregisters the passive listener and schedules/cancels IRN windows. */
    suspend fun sync(context: Context, settings: MonitorSettings) {
        val allowed = canRun(context)
        val passive = HealthServices.getClient(context).passiveMonitoringClient
        runCatching {
            if (allowed && settings.passiveHeartRate) {
                val config = PassiveListenerConfig.builder().setDataTypes(setOf(DataType.HEART_RATE_BPM)).build()
                passive.setPassiveListenerServiceAsync(PassiveHeartRateService::class.java, config).await()
            } else {
                passive.clearPassiveListenerServiceAsync().await()
            }
        }.onFailure { Log.w(TAG, "passive listener update failed", it) }

        val work = WorkManager.getInstance(context)
        if (allowed && settings.irregularRhythmEnabled) {
            val minutes = settings.irnIntervalMinutes.coerceAtLeast(15).toLong()
            work.enqueueUniquePeriodicWork(
                IRN_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<IrnWindowWorker>(minutes, TimeUnit.MINUTES).build(),
            )
        } else {
            work.cancelUniqueWork(IRN_WORK)
        }
        Log.i(TAG, "sync allowed=$allowed passive=${settings.passiveHeartRate} irn=${settings.irregularRhythmEnabled} background=${hasBackgroundPermission(context)}")
    }
}

private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        runCatching { get() }.onSuccess { cont.resume(it) }.onFailure { cont.resumeWithException(it.cause ?: it) }
    }, Runnable::run)
}

/**
 * Heart logic for background data: one [HeartMonitor] for trends and high/low alerts (fed by passive
 * samples), and one for irregular-rhythm windows whose minutes/alerts would duplicate the first.
 */
class BackgroundHeart(output: MonitorOutput, settings: () -> MonitorSettings) {
    private val lock = Mutex()

    val trends = HeartMonitor(output, { false }, { settings().copy(irregularRhythmEnabled = false) })

    private val irnOutput = object : MonitorOutput by output {
        override suspend fun enqueueBatch(batch: HrBatch) = Unit

        override fun latestMinute(bpm: Int) = Unit
    }

    /** Each window is a fresh check: no spacing inside the monitor, the worker does the scheduling. */
    val irn = HeartMonitor(irnOutput, { false }, { settings().copy(heartRateAlertsEnabled = false) }, windowEveryMs = 0)

    suspend fun onPassive(samples: List<HrSample>) = lock.withLock {
        samples.sortedBy { it.tsMs }.forEach { trends.onSample(it) }
        trends.flushBatch()
    }
}

/** Receives Health Services passive heart-rate batches (no foreground service, no notification). */
class PassiveHeartRateService :
    PassiveListenerService(),
    KoinComponent {
    private val heart: BackgroundHeart by inject()

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val samples = dataPoints.getData(DataType.HEART_RATE_BPM).mapNotNull { point ->
            val bpm = point.value.toInt().takeIf { it in 25..240 } ?: return@mapNotNull null
            HrSample(tsMs = point.getTimeInstant(boot).toEpochMilli(), bpm = bpm, ibiMs = emptyList(), onBody = true)
        }
        Log.i("Heartline/Monitor", "passive HR: ${samples.size} samples")
        if (samples.isNotEmpty()) runBlocking { heart.onPassive(samples) }
    }
}

/**
 * One irregular-rhythm window: ~70 s of the Samsung heart-rate tracker (beat-to-beat intervals).
 * With the background sensor permission it runs invisibly; without it Android requires a
 * foreground service, so a silent notification shows for that minute only.
 */
class IrnWindowWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val source: HrSource by inject()
    private val heart: BackgroundHeart by inject()
    private val notifier: WatchNotifier by inject()

    override suspend fun doWork(): Result {
        if (!BackgroundMonitoring.canRun(applicationContext)) return Result.success()
        if (!BackgroundMonitoring.hasBackgroundPermission(applicationContext)) {
            runCatching { setForeground(foregroundInfo()) }.onFailure { Log.w(TAG, "foreground window refused", it) }
        }
        val motion = StepMotionMonitor(applicationContext).also { it.start() }
        heart.irn.resetWindow()
        var count = 0
        try {
            withTimeoutOrNull(WINDOW_MS) {
                source.stream()
                    .catch { Log.w(TAG, "IRN window stream failed", it) }
                    .collect { sample ->
                        count++
                        heart.irn.onSample(sample.copy(moving = sample.moving || motion.movedSince(sample.tsMs - 60_000)))
                    }
            }
        } finally {
            motion.stop()
        }
        Log.i(TAG, "IRN window done: $count samples")
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo() = ForegroundInfo(WatchNotifier.MONITOR_ID, notifier.monitoring(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)

    private companion object {
        const val TAG = "Heartline/Monitor"
        const val WINDOW_MS = 75_000L
    }
}
