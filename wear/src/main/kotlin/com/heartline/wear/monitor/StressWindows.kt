// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.hr.HeartBaseline
import com.heartline.shared.hr.BackgroundWindow
import com.heartline.shared.hr.HeartHistory
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.MonitorSettings
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

    /**
     * RMSSD (ms) and heart rate from the window's successive trusted beats
     * ([BackgroundWindow.hrv]), or null (off the wrist, moving, too few trusted beats).
     */
    fun measure(samples: List<HrSample>): Pair<Double, Int>? = BackgroundWindow.hrv(samples)?.let { it.rmssdMs to it.bpm }

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

    /**
     * Scores a finished window, stores the history and sends the reading (and any notice).
     * Returns what happened, for the window's log line.
     */
    suspend fun onWindow(samples: List<HrSample>, settings: MonitorSettings, store: WatchSettingsStore, output: WatchMonitorOutput): String {
        if (!settings.stressActive) return "off"
        val reading = BackgroundWindow.hrv(samples) ?: return "too few trusted beats"
        val rmssd = reading.rmssdMs
        val bpm = reading.bpm
        val ts = reading.startMs
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
        return "score ${sample?.score ?: "-"} from ${reading.pairs} beat pairs ($context)"
    }
}
