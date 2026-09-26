// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.MinuteAggregator
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.irn.HeartRateAlertRules
import com.heartline.shared.irn.IrnState
import com.heartline.shared.irn.IrregularRhythmDetector
import java.util.UUID

/** Where the monitor keeps state between runs and sends its output. */
interface MonitorOutput {
    suspend fun loadIrnState(): IrnState

    suspend fun saveIrnState(state: IrnState)

    suspend fun enqueueBatch(batch: HrBatch)

    suspend fun enqueueAlert(alert: HealthAlert)

    fun notify(alert: HealthAlert)

    /** Latest completed minute, for complications/tiles. */
    fun latestMinute(bpm: Int) {}
}

/**
 * Background heart logic: aggregates 1 Hz samples into minutes, runs the irregular-rhythm check
 * on still one-minute windows every [windowEveryMs], evaluates high/low heart-rate rules, and
 * batches minutes to the phone every [batchEveryMinutes].
 */
class HeartMonitor(
    private val output: MonitorOutput,
    private val motion: MotionMonitor,
    private val settings: () -> MonitorSettings,
    private val detector: IrregularRhythmDetector = IrregularRhythmDetector(),
    private val rules: HeartRateAlertRules = HeartRateAlertRules(),
    private val windowEveryMs: Long = 15 * 60_000L,
    private val batchEveryMinutes: Int = 15,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val minuteSamples = mutableListOf<HrSample>()
    private val window = mutableListOf<HrSample>()
    private val minutes = ArrayDeque<HrMinute>()
    private val unsent = mutableListOf<HrMinute>()
    private val lastAlerts = mutableMapOf<AlertKind, Long>()
    private var lastWindowStart = Long.MIN_VALUE / 2
    private var irn: IrnState? = null

    suspend fun onSample(raw: HrSample) {
        val sample = raw.copy(moving = raw.moving || motion.movedSince(raw.tsMs - 60_000))
        val minuteStart = sample.tsMs / 60_000 * 60_000
        if (minuteSamples.isNotEmpty() && minuteSamples.first().tsMs / 60_000 * 60_000 != minuteStart) closeMinute()
        minuteSamples += sample

        val cfg = settings()
        if (!cfg.irregularRhythmEnabled) return
        if (window.isEmpty() && sample.tsMs - lastWindowStart < windowEveryMs) return
        if (window.isEmpty()) lastWindowStart = sample.tsMs
        window += sample
        if (sample.tsMs - window.first().tsMs >= 60_000) {
            val state = irn ?: output.loadIrnState()
            val (next, alert) = detector.onWindow(state, window.toList(), newId)
            irn = next
            output.saveIrnState(next)
            window.clear()
            // A window spoiled by motion is retried on the next still minute instead of waiting.
            if (next.windows.size == state.windows.size && alert == null) lastWindowStart = Long.MIN_VALUE / 2
            alert?.let { emit(it) }
        }
    }

    private suspend fun closeMinute() {
        MinuteAggregator.aggregate(minuteSamples).forEach { minute ->
            minutes.addLast(minute)
            unsent += minute
            output.latestMinute(minute.avgBpm)
        }
        minuteSamples.clear()
        while (minutes.size > 60) minutes.removeFirst()
        rules.evaluate(minutes.toList(), settings(), lastAlerts, newId).forEach { emit(it) }
        if (unsent.size >= batchEveryMinutes) flushBatch()
    }

    /** Drops a partly collected rhythm window (e.g. when a short background window ends). */
    fun resetWindow() {
        window.clear()
    }

    suspend fun flushBatch() {
        if (unsent.isEmpty()) return
        output.enqueueBatch(HrBatch(newId(), unsent.toList()))
        unsent.clear()
    }

    private suspend fun emit(alert: HealthAlert) {
        lastAlerts[alert.kind] = alert.atMs
        output.enqueueAlert(alert)
        output.notify(alert)
    }
}
