// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.hr.HeartBaseline
import com.heartline.shared.hr.HeartHistory
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.Hrv
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.irn.IbiWindowQuality
import com.heartline.shared.stress.StressBaseline
import com.heartline.shared.stress.StressMonitor
import com.heartline.shared.vitals.VitalsBaseline
import com.heartline.shared.vitals.VitalsHistory
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Background stress from the rhythm windows (docs/algorithms/STRESS_MONITORING.md): a readable,
 * still window gives one RMSSD and heart rate, scored against the wearer's own normal.
 */
object StressWindows {
    private const val TAG = "Heartline/Stress"
    private const val MINUTE = 60_000L

    /** RMSSD (ms) and heart rate of a readable window, or null (off the wrist, moving, weak signal). */
    fun measure(samples: List<HrSample>): Pair<Double, Int>? {
        val readable = IbiWindowQuality.assess(samples) as? IbiWindowQuality.Result.Readable ?: return null
        val ibis = IbiWindowQuality.dropIsolatedEctopics(readable.ibisMs)
        val hrv = Hrv.compute(ibis) ?: return null
        return hrv.rmssdMs to (60_000.0 / ibis.average()).toInt()
    }

    /**
     * What the window counts as: exercise (also for the hour after it, while HRV recovers), asleep
     * (or the usual sleep hours when the watch can't tell), moving, or awake and still.
     */
    fun contextAt(tsMs: Long, settings: MonitorSettings, activityAt: (Long) -> HrContext?, zone: ZoneId = ZoneId.systemDefault()): HrContext {
        val minute = tsMs / MINUTE * MINUTE
        if ((0..60).any { activityAt(minute - it * MINUTE) == HrContext.EXERCISE }) return HrContext.EXERCISE
        val now = activityAt(minute)
        val local = Instant.ofEpochMilli(tsMs).atZone(zone)
        return when {
            now != null -> now
            settings.isUsualSleep(local.hour * 60 + local.minute) -> HrContext.SLEEP
            else -> HrContext.REST
        }
    }

    /** Illness explains a low HRV today: a warmer night (+0.5 °C) or a raised heart rate in sleep. */
    fun illness(vitals: VitalsHistory, heart: HeartHistory, settings: MonitorSettings, today: Long): Boolean {
        val warmer = VitalsBaseline.limits(vitals, today, settings.alertSensitivity, null).lastNightDeviation?.let { it >= VitalsBaseline.COMBINED_RISE } == true
        return warmer || HeartBaseline.nightRaised(heart, today)
    }

    /** Scores a finished window, stores the history and sends the reading (and any notice). */
    suspend fun onWindow(samples: List<HrSample>, settings: MonitorSettings, store: WatchSettingsStore, output: WatchMonitorOutput) {
        if (!settings.stressActive || samples.isEmpty()) return
        val (rmssd, bpm) = measure(samples) ?: return
        val ts = samples.last().tsMs
        val zone = ZoneId.systemDefault()
        val context = contextAt(ts, settings, store::activityAt, zone)
        val today = StressBaseline.dayOf(ts, HrContext.REST, zone)
        val ill = illness(store.vitals, store.monitorState.history, settings, today)
        val (history, sample, alert) = StressMonitor(zone) { UUID.randomUUID().toString() }.onWindow(store.stress, ts, rmssd, bpm, context, settings, ill)
        store.stress = history
        HLog.i(TAG, "stress window: context=$context rmssd=${"%.1f".format(rmssd)} bpm=$bpm score=${sample?.score} illness=$ill alert=${alert != null}")
        if (sample != null) {
            output.enqueueBatch(HrBatch(UUID.randomUUID().toString(), emptyList(), stress = listOf(sample), stressLimits = StressBaseline.limits(history, today)))
        }
        alert?.let {
            output.enqueueAlert(it)
            output.notify(it)
        }
    }
}

/**
 * Times a background window from its first reliable reading, not from switching the sensor on:
 * right after another measurement (blood oxygen) the heart-rate tracker can report "initial" (status
 * 0, rate 0) for up to a minute, and a window timed from the start was all warm-up ("weak signal" in
 * a real Galaxy Watch8 log). Warm-up readings are dropped; without a reliable one within [warmupMs]
 * the window ends.
 */
class WindowGate(private val windowMs: Long, private val warmupMs: Long, private val openedAtMs: Long) {
    enum class Decision { SKIP, TAKE, STOP }

    var startedAtMs: Long? = null
        private set
    var warmupSamples = 0
        private set

    fun decide(sample: HrSample, nowMs: Long): Decision {
        val start = startedAtMs
        if (start == null) {
            if (nowMs - openedAtMs > warmupMs) return Decision.STOP
            if (!(sample.reliable && sample.onBody && sample.bpm > 0)) {
                warmupSamples++
                return Decision.SKIP
            }
            startedAtMs = sample.tsMs
            return Decision.TAKE
        }
        return if (sample.tsMs - start >= windowMs) Decision.STOP else Decision.TAKE
    }
}
