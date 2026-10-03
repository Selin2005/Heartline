// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.heartline.datalayer.diag.HLog
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrContext
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.shared.vitals.Spo2Sample
import com.heartline.shared.vitals.TempSample
import com.heartline.shared.vitals.VitalsBaseline
import com.heartline.shared.vitals.VitalsMonitor
import com.heartline.shared.vitals.VitalsQuality
import com.heartline.wear.quick.QuickSources
import com.heartline.wear.quick.WatchProfileStore
import com.heartline.wear.sensor.QuickEvent
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.sensor.QuickSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

/** One background measurement at a time: the sensors are shared by the rhythm and vitals workers. */
object BackgroundSensors {
    val lock = Mutex()
}

/** Runs one on-demand measurement to its end: the result, and whether the wearer moved meanwhile. */
object VitalsMeasurer {
    data class Reading(val summary: RecordSummary?, val moved: Boolean)

    suspend fun measure(source: QuickSource, timeoutMs: Long = (source.seconds + 25) * 1_000L): Reading {
        var moved = false
        val end = withTimeoutOrNull(timeoutMs) {
            source.measure(null)
                .onEach { if (it is QuickEvent.Progress && it.hint == QuickHint.HOLD_STILL) moved = true }
                .first { it is QuickEvent.Result || it is QuickEvent.Failed }
        }
        return Reading((end as? QuickEvent.Result)?.summary, moved)
    }
}

/**
 * Background blood oxygen and skin temperature (docs/algorithms/VITALS_MONITORING.md). Every 30
 * minutes: skin temperature in sleep (hourly by day), SpO2 every hour awake and asleep, only while
 * the watch is worn and the wearer still (a moving wearer is tried again 15 minutes later, twice).
 * A low SpO2 reading is checked again 2 minutes later, twice at most; three low in a row notify.
 */
class VitalsWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val sources: QuickSources by inject()
    private val store: WatchSettingsStore by inject()
    private val output: WatchMonitorOutput by inject()
    private val profile: WatchProfileStore by inject()
    private val notifier: WatchNotifier by inject()

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo() = ForegroundInfo(WatchNotifier.MONITOR_ID, notifier.monitoring(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)

    override suspend fun doWork(): Result {
        val settings = store.settings.value.normalized()
        if (!settings.spo2Active && !settings.skinTempActive) return Result.success()
        val now = System.currentTimeMillis()
        val lastWorn = store.lastPassiveHeartRateMs
        // Unknown right after an update or install: then the off-body sensor below decides.
        if (settings.passiveHeartRate && lastWorn != null && now - lastWorn > NOT_WORN_MS) {
            HLog.i(TAG, "vitals skipped: not worn")
            return Result.success()
        }
        val minute = now / MINUTE * MINUTE
        // Without activity recognition, the usual sleep hours (from the monitoring setup) stand in.
        val context = store.activityAt(minute) ?: java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
            .let { if (settings.isUsualSleep(it.hour * 60 + it.minute)) HrContext.SLEEP else HrContext.REST }
        if (context == HrContext.EXERCISE) return Result.success()
        val moving = context == HrContext.ACTIVE || (0..2).any { store.activity.stepsPerMinute(minute - it * MINUTE) >= 20.0 }
        val asleep = context == HrContext.SLEEP
        val monitor = VitalsMonitor { UUID.randomUUID().toString() }
        val spo2Samples = mutableListOf<Spo2Sample>()
        val temps = mutableListOf<TempSample>()
        val alerts = mutableListOf<HealthAlert>()
        var history = store.vitals

        // Without the background sensor permission Android needs a (silent) foreground service.
        if (!BackgroundMonitoring.hasBackgroundPermission(applicationContext)) {
            runCatching { setForeground(foregroundInfo()) }.onFailure { HLog.w(TAG, "foreground refused", it) }
        }
        BackgroundSensors.lock.withLock {
            val wrist = WristState(applicationContext).also { it.start() }
            try {
                delay(1_500)
                if (wrist.offBody) {
                    HLog.i(TAG, "vitals skipped: off the wrist")
                    return@withLock
                }
                // Skin temperature: every 30 minutes asleep, hourly by day.
                val tempDue = now - store.lastTempMs >= if (asleep) 25 * MINUTE else 55 * MINUTE
                if (settings.skinTempActive && tempDue && allowed(Metric.SKIN_TEMPERATURE)) {
                    sources[Metric.SKIN_TEMPERATURE]?.let { source ->
                        val r = (VitalsMeasurer.measure(source).summary as? RecordSummary.SkinTemperature)
                        if (r != null && VitalsQuality.temperature(r.skinCelsius, r.ambientCelsius, store.lastAmbientC)) {
                            val since = store.asleepSince(now)
                            val counted = asleep && since != null && now - since >= HOUR
                            val sample = TempSample(now, r.skinCelsius, r.ambientCelsius, context, counted)
                            temps += sample
                            history = monitor.onTemp(history, sample)
                        }
                        store.lastAmbientC = r?.ambientCelsius
                        store.lastTempMs = now
                    }
                }
                // Blood oxygen: every hour, awake and asleep; by day not on a low battery.
                val spo2Due = now - store.lastSpo2Ms >= 55 * MINUTE
                val spo2Now = if (asleep) settings.spo2InSleep else battery() >= LOW_BATTERY
        if (settings.spo2Active && spo2Due && spo2Now && allowed(Metric.SPO2)) {
                    if (moving) {
                        if (store.spo2Retries < MAX_RETRIES) {
                            store.spo2Retries += 1
                            BackgroundMonitoring.scheduleVitalsRetry(applicationContext)
                        }
                        HLog.i(TAG, "SpO2 put off: moving (try ${store.spo2Retries})")
                    } else {
                        sources[Metric.SPO2]?.let { source ->
                            val first = spo2(source, context, now, wrist, confirmation = false)
                            store.lastSpo2Ms = now
                            store.spo2Retries = 0
                            if (first != null) {
                                spo2Samples += first
                                // Low: check again (twice at most, while it stays low).
                                val rechecks = mutableListOf<Spo2Sample>()
                                var last: Spo2Sample = first
                                while (monitor.needsRecheck(last.percent, settings, history, last.tsMs) && rechecks.size < VitalsMonitor.RECHECKS) {
                                    delay(RECHECK_DELAY_MS)
                                    last = spo2(source, context, System.currentTimeMillis(), wrist, confirmation = true) ?: break
                                    rechecks += last
                                    spo2Samples += last
                                }
                                val (next, alert) = monitor.onSpo2(history, first, rechecks, settings)
                                history = next
                                alert?.let { alerts += it }
                            }
                        }
                    }
                }
            } finally {
                wrist.stop()
            }
        }

        val (afterNight, nightAlerts) = monitor.afterNight(history, store.monitorState.history, settings, System.currentTimeMillis())
        history = afterNight
        alerts += nightAlerts
        store.vitals = history
        val today = VitalsBaseline.dayOf(now, HrContext.REST, java.time.ZoneId.systemDefault())
        val limits = VitalsBaseline.limits(history, today, settings.alertSensitivity, profile.profile.value?.age(), settings.health.lung)
        output.enqueueBatch(HrBatch(UUID.randomUUID().toString(), emptyList(), spo2 = spo2Samples, skinTemp = temps, vitals = limits))
        alerts.forEach {
            output.enqueueAlert(it)
            output.notify(it)
        }
        HLog.i(TAG, "vitals: context=$context spo2=${spo2Samples.map { it.percent }} temp=${temps.map { it.skinC }} alerts=${alerts.map { it.vital }}")
        return Result.success()
    }

    private suspend fun spo2(source: QuickSource, context: HrContext, at: Long, wrist: WristState, confirmation: Boolean): Spo2Sample? {
        val start = System.currentTimeMillis()
        val reading = VitalsMeasurer.measure(source)
        val r = reading.summary as? RecordSummary.Spo2 ?: return null
        val moved = reading.moved || wrist.movedSince(start)
        if (!VitalsQuality.spo2(r.percent, r.heartRate, store.latestHeartRate, moved)) {
            HLog.i(TAG, "SpO2 ${r.percent} rejected (moved=$moved hr=${r.heartRate}/${store.latestHeartRate})")
            return null
        }
        return Spo2Sample(at, r.percent, context, confirmation)
    }

    private fun allowed(metric: Metric) = PermissionPolicy.permissionsFor(metric, Build.VERSION.SDK_INT)
        .all { ContextCompat.checkSelfPermission(applicationContext, it) == PackageManager.PERMISSION_GRANTED }

    private fun battery(): Int =
        applicationContext.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100

    private companion object {
        const val TAG = "Heartline/Vitals"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val NOT_WORN_MS = 60 * MINUTE
        const val LOW_BATTERY = 15
        const val MAX_RETRIES = 2
        const val RECHECK_DELAY_MS = 2 * MINUTE
    }
}
