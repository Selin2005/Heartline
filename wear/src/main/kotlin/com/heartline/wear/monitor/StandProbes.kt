// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.content.Context
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.heartline.datalayer.diag.HLog
import com.heartline.shared.diag.LogLine
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrSample
import com.heartline.shared.pots.StandProbe
import com.heartline.shared.pots.StandProbePolicy
import com.heartline.wear.diag.RawCapture
import com.heartline.wear.sensor.HrSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

/**
 * Watches for the wearer getting up (docs/algorithms/POSTURAL_RISE.md, section 2): Android's
 * significant-motion sensor, a low-power one-shot that wakes the watch when the wearer starts
 * walking. It is armed again after each trigger and whenever the app wakes (passive heart rate,
 * rhythm windows, settings), since a new process forgets it. Each trigger is judged by
 * [StandProbePolicy]; a probe that may start runs as [StandProbeWorker].
 *
 * Watches without the sensor fall back to the passive step counts ([onSteps]), which arrive later.
 */
object StandTrigger : KoinComponent {
    private const val TAG = "Heartline/Stand"
    private val store: WatchSettingsStore by inject()
    private const val WORK = "heartline-stand-probe"
    private var listener: TriggerEventListener? = null
    private var missingLogged = false

    /** Whether this watch has the motion sensor (else the step counts are used). */
    fun available(context: Context) = sensor(context) != null

    private fun sensor(context: Context) = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    /** Arms the sensor if it isn't already, or disarms it when the check is off ([active]: the settings say it runs). */
    @Synchronized
    fun sync(context: Context, active: Boolean = store.settings.value.normalized().posturalRiseActive) {
        val app = context.applicationContext
        val manager = app.getSystemService(SensorManager::class.java) ?: return
        if (!active || !BackgroundMonitoring.canRun(app)) {
            listener?.let { manager.cancelTriggerSensor(it, sensor(app)) }
            listener = null
            return
        }
        if (listener != null) return
        val motion = sensor(app) ?: run {
            if (!missingLogged) HLog.i(TAG, "no significant-motion sensor: getting up is seen from the step counts")
            missingLogged = true
            return
        }
        val next = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent) {
                synchronized(StandTrigger) { listener = null }
                // The sensor's own time (elapsed-realtime nanoseconds), as wall-clock time.
                val atMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
                onMotion(app, atMs, store.lastMotionMs)
                store.lastMotionMs = atMs
                sync(app)
            }
        }
        if (manager.requestTriggerSensor(next, motion)) listener = next
    }

    /** Without the motion sensor: the passive step counts, when they show steps after a still stretch. */
    fun onSteps(context: Context, nowMs: Long) {
        if (available(context) || !store.settings.value.normalized().posturalRiseActive) return
        val (startMs, lastMoveMs) = StandProbePolicy.gettingUpFromSteps(store.activity, nowMs) ?: return
        if (store.lastMotionMs?.let { it >= startMs } == true) return
        onMotion(context.applicationContext, startMs, lastMoveMs)
        store.lastMotionMs = startMs
    }

    private fun onMotion(context: Context, atMs: Long, lastMotionMs: Long?) {
        val settings = store.settings.value.normalized()
        val decision = StandProbePolicy.decide(atMs, lastMotionMs, store.standProbes, store.activityAt(atMs / 60_000 * 60_000), settings)
        HLog.i(TAG, "getting up at ${LogLine.at(atMs)}: ${if (decision.start) "probe" else "no probe"} (${decision.reason})")
        if (!decision.start) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<StandProbeWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(androidx.work.workDataOf(StandProbeWorker.START_MS to atMs))
                .build(),
        )
    }
}

/**
 * One stand probe: the heart-rate tracker in three slices of about 40 s, starting about 15 s,
 * 2.5 minutes and 6 minutes after the wearer got up ([StandProbePolicy.SLICE_STARTS_MS]). The
 * sensor is off between slices; the probe ends about 7.5 minutes after the motion, inside
 * WorkManager's 10 minutes. The readings go to the phone ([HrBatch.stands]), which judges them
 * against the still minutes before.
 *
 * As the rhythm windows: never on the charger, off the wrist, while a measurement runs or while
 * another background measurement has the sensors; a wake lock and an alarm keep the timers going
 * while the screen is off (docs/algorithms/HEART_MONITORING.md, "Background windows").
 */
class StandProbeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val source: HrSource by inject()
    private val store: WatchSettingsStore by inject()
    private val output: MonitorOutput by inject()
    private val notifier: WatchNotifier by inject()
    private val measurements: com.heartline.wear.remote.RemoteMeasureCoordinator by inject()

    /** When the slices start after the motion; shorter only in tests. */
    private val slices = inputData.getLongArray(SLICES_MS)?.toList() ?: StandProbePolicy.SLICE_STARTS_MS

    override suspend fun doWork(): Result {
        val startMs = inputData.getLong(START_MS, System.currentTimeMillis())
        val skip = when {
            !BackgroundMonitoring.canRun(applicationContext) -> "no sensor permission"
            !store.settings.value.normalized().posturalRiseActive -> "off"
            measurements.busy -> "a measurement is running"
            Charging.isCharging(applicationContext) -> "charging"
            System.currentTimeMillis() - startMs > slices[1] -> "started too late (${(System.currentTimeMillis() - startMs) / 1_000} s)"
            else -> null
        }
        if (skip != null) {
            HLog.i(TAG, "stand probe skipped: $skip")
            return Result.success()
        }
        if (!BackgroundSensors.lock.tryLock()) {
            HLog.i(TAG, "stand probe skipped: another background measurement has the sensors")
            return Result.success()
        }
        running = true
        try {
            val wrist = WristState(applicationContext).also { it.start() }
            delay(1_500)
            if (wrist.offBody) {
                wrist.stop()
                HLog.i(TAG, "stand probe skipped: watch off the wrist")
                return Result.success()
            }
            if (!BackgroundMonitoring.hasBackgroundPermission(applicationContext)) {
                runCatching { setForeground(foregroundInfo()) }.onFailure { HLog.w(TAG, "foreground probe refused", it) }
            }
            store.recordStandProbe(startMs)
            probe(startMs, wrist)
        } finally {
            running = false
            BackgroundSensors.lock.unlock()
        }
        return Result.success()
    }

    private suspend fun probe(startMs: Long, wrist: WristState) {
        val samples = mutableListOf<HrSample>()
        val raw = RawCapture.begin("stand_probe", background = true)
        val wake = applicationContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heartline:stand-probe")
            ?.apply { acquire(PROBE_MAX_MS) }
        try {
            for (offset in slices) {
                if (wrist.offBody || Charging.isCharging(applicationContext)) break
                waitUntil(startMs + offset)
                samples += slice()
            }
        } finally {
            wrist.stop()
            RawCapture.end(raw, mapOf("samples" to samples.size.toDouble()))
            wake?.takeIf { it.isHeld }?.release()
        }
        val minutes = (startMs / 60_000 * 60_000..System.currentTimeMillis() step 60_000).mapNotNull { store.stepsAt(it) }
        val probe = StandProbe.of(startMs, samples, minutes.takeIf { it.isNotEmpty() }?.average()?.toInt())
        HLog.i(TAG, "stand probe from ${LogLine.at(startMs)}: ${samples.size} readings, slices ${probe.sliceMedians().joinToString { "${(it.tsMs - startMs) / 1_000} s ${it.bpm}" }}, steps/min ${probe.stepsPerMinute}")
        if (probe.readings.isNotEmpty()) output.enqueueBatch(HrBatch(UUID.randomUUID().toString(), emptyList(), stands = listOf(probe)))
    }

    /** Waits until [wallMs], woken by an alarm if the watch sleeps through the timer. */
    private suspend fun waitUntil(wallMs: Long) {
        val ms = wallMs - System.currentTimeMillis()
        if (ms <= 0) return
        val woke = CompletableDeferred<String>()
        WindowAlarm.begin(woke)
        WindowAlarm.schedule(applicationContext, ms)
        try {
            withTimeoutOrNull(ms) { woke.await() }
        } finally {
            WindowAlarm.end(woke)
            WindowAlarm.cancel(applicationContext)
        }
    }

    /** One slice: listens until its readings cover [StandProbePolicy.SLICE_MS], then asks the tracker for what it still holds. */
    private suspend fun slice(): List<HrSample> {
        val samples = mutableListOf<HrSample>()
        val stop = CompletableDeferred<String>()
        WindowAlarm.begin(stop)
        WindowAlarm.schedule(applicationContext, StandProbePolicy.SLICE_MAX_MS)
        try {
            withTimeoutOrNull(StandProbePolicy.SLICE_MAX_MS + FLUSH_MS) {
                coroutineScope {
                    val listening = launch {
                        source.stream()
                            .catch { HLog.w(TAG, "stand probe stream failed", it) }
                            .collect { sample ->
                                samples += sample
                                val good = samples.filter { it.reliable && it.onBody }
                                if (good.size > 1 && good.last().tsMs - good.first().tsMs >= StandProbePolicy.SLICE_MS) stop.complete("covered")
                            }
                    }
                    withTimeoutOrNull(StandProbePolicy.SLICE_MAX_MS) { stop.await() }
                    source.flush()
                    // Readings sent with the flush are still being handed over.
                    delay(1_000)
                    listening.cancel()
                }
            }
        } finally {
            WindowAlarm.end(stop)
            WindowAlarm.cancel(applicationContext)
        }
        return samples
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo() = ForegroundInfo(WatchNotifier.MONITOR_ID, notifier.monitoring(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)

    companion object {
        private const val TAG = "Heartline/Stand"
        const val START_MS = "start_ms"
        const val SLICES_MS = "slices_ms"
        private const val FLUSH_MS = 20_000L
        private const val PROBE_MAX_MS = 9 * 60_000L

        /** True while a probe runs: the rhythm windows wait for the next one instead of sharing the tracker. */
        @Volatile var running = false
            private set
    }
}
