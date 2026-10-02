// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import com.heartline.datalayer.diag.HLog
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
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
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.heartline.shared.hr.ActivityChange
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.hr.MonitorState
import com.heartline.shared.hr.StepSpan
import com.heartline.shared.hr.WatchActivity
import com.heartline.shared.profile.Sex
import java.time.ZoneId
import com.heartline.shared.model.Metric
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.wear.sensor.HrSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.util.concurrent.TimeUnit
import com.heartline.wear.diag.RawCapture

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
    private const val IRN_FOLLOW_UP = "heartline-irn-follow-up"
    private const val VITALS_WORK = "heartline-vitals"
    private const val VITALS_RETRY = "heartline-vitals-retry"
    /** Halfway to the next regular check: an irregular window is confirmed or not sooner. */
    private const val FOLLOW_UP_MINUTES = 7L

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

    private fun hasActivityPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** One extra rhythm check soon after an irregular one, instead of waiting for the next interval. */
    fun scheduleFollowUp(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IRN_FOLLOW_UP,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IrnWindowWorker>().setInitialDelay(FOLLOW_UP_MINUTES, TimeUnit.MINUTES).build(),
        )
    }

    /** One more SpO2 try 15 minutes later, when the wearer was moving at the hour. */
    fun scheduleVitalsRetry(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            VITALS_RETRY,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<VitalsWorker>().setInitialDelay(15, TimeUnit.MINUTES).build(),
        )
    }

    fun hasBackgroundPermission(context: Context) =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, backgroundPermission) == PackageManager.PERMISSION_GRANTED

    /** Applies [settings]: registers/unregisters the passive listener and schedules/cancels IRN windows. */
    suspend fun sync(context: Context, settings: MonitorSettings) {
        val allowed = canRun(context)
        val passive = HealthServices.getClient(context).passiveMonitoringClient
        runCatching {
            if (allowed && settings.passiveHeartRate) {
                val activity = hasActivityPermission(context)
                val supported = runCatching { passive.getCapabilitiesAsync().await().supportedDataTypesPassiveMonitoring }.getOrNull().orEmpty()
                val types = buildSet<DataType<*, *>> {
                    add(DataType.HEART_RATE_BPM)
                    if (activity && DataType.STEPS in supported) add(DataType.STEPS)
                }
                val config = PassiveListenerConfig.builder().setDataTypes(types).setShouldUserActivityInfoBeRequested(activity).build()
                val registered = runCatching { passive.setPassiveListenerServiceAsync(PassiveHeartRateService::class.java, config).await() }
                if (registered.isFailure && activity) {
                    // Heart rate matters more than activity: never lose it because activity info was refused.
                    HLog.w(TAG, "passive listener with activity refused, retrying heart rate only", registered.exceptionOrNull())
                    val plain = PassiveListenerConfig.builder().setDataTypes(setOf(DataType.HEART_RATE_BPM)).build()
                    passive.setPassiveListenerServiceAsync(PassiveHeartRateService::class.java, plain).await()
                } else {
                    registered.getOrThrow()
                }
            } else {
                passive.clearPassiveListenerServiceAsync().await()
            }
        }.onFailure { HLog.w(TAG, "passive listener update failed", it) }

        val work = WorkManager.getInstance(context)
        if (allowed && settings.heartActive) {
            val minutes = MonitorSettings.IRN_INTERVAL_MINUTES.toLong()
            work.enqueueUniquePeriodicWork(
                IRN_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<IrnWindowWorker>(minutes, TimeUnit.MINUTES).build(),
            )
        } else {
            work.cancelUniqueWork(IRN_WORK)
            work.cancelUniqueWork(IRN_FOLLOW_UP)
        }
        // Blood oxygen and skin temperature: their own worker, every 30 minutes (they check their own permissions).
        if (settings.spo2Active || settings.skinTempActive) {
            work.enqueueUniquePeriodicWork(VITALS_WORK, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<VitalsWorker>(30, TimeUnit.MINUTES).build())
        } else {
            work.cancelUniqueWork(VITALS_WORK)
            work.cancelUniqueWork(VITALS_RETRY)
        }
        HLog.i(TAG, "sync allowed=$allowed monitoring=${settings.heartMonitoring} spo2=${settings.spo2Active} temp=${settings.skinTempActive} sensitivity=${settings.alertSensitivity} background=${hasBackgroundPermission(context)}")
    }
}

private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        runCatching { get() }.onSuccess { cont.resume(it) }.onFailure { cont.resumeWithException(it.cause ?: it) }
    }, Runnable::run)
}

/**
 * Heart logic for background data: one [HeartMonitor] for trends and high/low alerts (fed by passive
 * samples), and one for irregular-rhythm windows. Both label minutes with [activity]; the rhythm
 * windows' minutes go to the phone too, since only they carry beat-to-beat intervals (HRV).
 */
class BackgroundHeart(
    output: MonitorOutput,
    activity: (minuteStartMs: Long) -> HrContext? = { null },
    age: () -> Int? = { null },
    sex: () -> Sex? = { null },
    zone: ZoneId = ZoneId.systemDefault(),
    settings: () -> MonitorSettings,
) {
    private val lock = Mutex()

    val trends = HeartMonitor(output, { false }, { settings().normalized().copy(irregularRhythmEnabled = false) }, activity, age, sex, zone)

    /** Rhythm windows keep no alert history of their own (their heart-rate alerts are off). */
    private val irnOutput = object : MonitorOutput by output {
        override suspend fun loadMonitorState() = MonitorState()

        override suspend fun saveMonitorState(state: MonitorState) = Unit

        override fun latestMinute(bpm: Int) = Unit
    }

    /** Each window is a fresh check: no spacing inside the monitor, the worker does the scheduling. */
    val irn = HeartMonitor(irnOutput, { false }, { settings().normalized().copy(heartRateAlertsEnabled = false) }, activity, age, sex, zone, windowEveryMs = 0)

    suspend fun onPassive(samples: List<HrSample>) = lock.withLock {
        samples.sortedBy { it.tsMs }.forEach { trends.onSample(it) }
        // Close the last minute too: the next delivery may be minutes away, or in a new process.
        trends.closeOpenMinutes()
        trends.flushBatch()
    }

    /** Ends a rhythm window: its minutes (with HRV) go to the phone. A partial window is dropped when the next one starts. */
    suspend fun endIrnWindow() = lock.withLock {
        irn.closeOpenMinutes()
        irn.flushBatch()
    }
}

/** Receives Health Services passive heart rate, steps and activity (no foreground service, no notification). */
class PassiveHeartRateService :
    PassiveListenerService(),
    KoinComponent {
    private val heart: BackgroundHeart by inject()
    private val store: WatchSettingsStore by inject()

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val steps = dataPoints.getData(DataType.STEPS).map {
            StepSpan(it.getStartInstant(boot).toEpochMilli(), it.getEndInstant(boot).toEpochMilli(), it.value)
        }
        if (steps.isNotEmpty()) store.recordSteps(steps)
        val samples = dataPoints.getData(DataType.HEART_RATE_BPM).mapNotNull { point ->
            val bpm = point.value.toInt().takeIf { it in 25..240 } ?: return@mapNotNull null
            HrSample(tsMs = point.getTimeInstant(boot).toEpochMilli(), bpm = bpm, ibiMs = emptyList(), onBody = true)
        }
        HLog.i("Heartline/Monitor", "passive HR: ${samples.size} samples, steps: ${steps.sumOf { it.steps }}")
        RawCapture.values("healthServices.HEART_RATE_BPM", listOf("bpm"), samples.map { it.tsMs to floatArrayOf(it.bpm.toFloat()) })
        if (samples.isNotEmpty()) {
            store.lastPassiveHeartRateMs = samples.maxOf { it.tsMs }
            runBlocking { heart.onPassive(samples) }
        }
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val activity = when (info.userActivityState) {
            UserActivityState.USER_ACTIVITY_EXERCISE -> WatchActivity.EXERCISE
            UserActivityState.USER_ACTIVITY_ASLEEP -> WatchActivity.ASLEEP
            UserActivityState.USER_ACTIVITY_PASSIVE -> WatchActivity.PASSIVE
            else -> WatchActivity.UNKNOWN
        }
        HLog.i("Heartline/Monitor", "activity: $activity since ${info.stateChangeTime}")
        store.recordActivity(ActivityChange(info.stateChangeTime.toEpochMilli(), activity))
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

    private val store: WatchSettingsStore by inject()

    override suspend fun doWork(): Result {
        if (!BackgroundMonitoring.canRun(applicationContext)) return Result.success()
        val settings = store.settings.value
        if (!settings.heartActive) return Result.success()
        // No recent background heart rate means the watch is very likely not being worn: don't listen
        // to a sensor on a table or charger, whose noise looks like an irregular rhythm.
        val lastWorn = store.lastPassiveHeartRateMs
        if (settings.passiveHeartRate && (lastWorn == null || System.currentTimeMillis() - lastWorn > NOT_WORN_MS)) {
            HLog.i(TAG, "IRN window skipped: no background heart rate since ${lastWorn ?: "install"}")
            return Result.success()
        }
        val wrist = WristState(applicationContext).also { it.start() }
        // The off-body sensor reports its current state shortly after it is switched on.
        delay(1_500)
        if (wrist.offBody) {
            wrist.stop()
            HLog.i(TAG, "IRN window skipped: watch off the wrist")
            return Result.success()
        }
        if (!BackgroundMonitoring.hasBackgroundPermission(applicationContext)) {
            runCatching { setForeground(foregroundInfo()) }.onFailure { HLog.w(TAG, "foreground window refused", it) }
        }
        // One background measurement at a time (blood oxygen and temperature use the sensors too).
        return BackgroundSensors.lock.withLock { window(settings, wrist) }
    }

    private suspend fun window(settings: com.heartline.shared.hr.MonitorSettings, wrist: WristState): Result {
        val motion = StepMotionMonitor(applicationContext).also { it.start() }
        heart.irn.resetWindow()
        var count = 0
        // A background session of its own, unless a measurement is running (then it records there).
        val raw = RawCapture.begin("irn_window", background = true)
        try {
            withTimeoutOrNull(WINDOW_MS) {
                source.stream()
                    .catch { HLog.w(TAG, "IRN window stream failed", it) }
                    .collect { sample ->
                        count++
                        heart.irn.onSample(
                            sample.copy(
                                moving = sample.moving || motion.movedSince(sample.tsMs - 60_000) || wrist.movedSince(sample.tsMs - 60_000),
                                onBody = sample.onBody && !wrist.offBody,
                            ),
                        )
                    }
            }
        } finally {
            motion.stop()
            wrist.stop()
            RawCapture.end(raw, mapOf("samples" to count.toDouble()))
        }
        heart.endIrnWindow()
        val irregular = heart.irn.lastWindowIrregular
        HLog.i(TAG, "IRN window done: $count samples, irregular=$irregular skipped=${heart.irn.lastSkipReason}")
        if (irregular == true) BackgroundMonitoring.scheduleFollowUp(applicationContext)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo() = ForegroundInfo(WatchNotifier.MONITOR_ID, notifier.monitoring(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)

    private companion object {
        const val TAG = "Heartline/Monitor"
        const val WINDOW_MS = 75_000L

        /** Passive heart rate arrives every few minutes while the watch is worn; an hour without any means it isn't. */
        const val NOT_WORN_MS = 60 * 60_000L
    }
}
