// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.HrSample
import com.heartline.shared.hr.MinuteAggregator
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.hr.MonitorState
import com.heartline.shared.irn.HeartRateAlertRules
import com.heartline.shared.irn.IrnState
import com.heartline.shared.irn.IrregularRhythmDetector
import java.util.UUID

/** Where the monitor keeps state between runs and sends its output. */
interface MonitorOutput {
    suspend fun loadIrnState(): IrnState

    suspend fun saveIrnState(state: IrnState)

    /** Recent minutes and alert times; kept in memory only unless overridden. */
    suspend fun loadMonitorState(): MonitorState = MonitorState()

    suspend fun saveMonitorState(state: MonitorState) {}

    suspend fun enqueueBatch(batch: HrBatch)

    suspend fun enqueueAlert(alert: HealthAlert)

    fun notify(alert: HealthAlert)

    /** Latest completed minute, for complications/tiles. */
    fun latestMinute(bpm: Int) {}
}

/**
 * Background heart logic: aggregates samples into minutes (each labelled rest, sleep, moving or
 * exercise via [activity]), runs the irregular-rhythm check on still one-minute windows every
 * [windowEveryMs], evaluates the high/low heart-rate rules, and batches minutes to the phone every
 * [batchEveryMinutes]. Recent minutes and alert times are persisted through [output], so a new
 * process neither forgets a sustained rate nor repeats an alert.
 */
class HeartMonitor(
    private val output: MonitorOutput,
    private val motion: MotionMonitor,
    private val settings: () -> MonitorSettings,
    private val activity: (minuteStartMs: Long) -> HrContext? = { null },
    private val age: () -> Int? = { null },
    private val detector: IrregularRhythmDetector = IrregularRhythmDetector(),
    private val rules: HeartRateAlertRules = HeartRateAlertRules(),
    private val windowEveryMs: Long = 15 * 60_000L,
    private val batchEveryMinutes: Int = 15,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val open = sortedMapOf<Long, MutableList<HrSample>>()
    private val window = mutableListOf<HrSample>()
    private val unsent = sortedMapOf<Long, HrMinute>()
    private var lastWindowStart = Long.MIN_VALUE / 2
    private var irn: IrnState? = null
    private var state: MonitorState? = null

    /** Result of the last rhythm window: true irregular, false regular, null not readable (or none yet). */
    var lastWindowIrregular: Boolean? = null
        private set

    /** Why the last rhythm window could not be read, for the log. */
    val lastSkipReason: String? get() = detector.lastSkipReason

    suspend fun onSample(raw: HrSample) {
        val sample = raw.copy(moving = raw.moving || motion.movedSince(raw.tsMs - 60_000))
        val minuteStart = sample.tsMs / MINUTE * MINUTE
        if (open.keys.any { it < minuteStart }) closeMinutes(before = minuteStart)
        open.getOrPut(minuteStart) { mutableListOf() } += sample

        val cfg = settings()
        if (!cfg.irregularRhythmEnabled) return
        if (window.isEmpty() && sample.tsMs - lastWindowStart < windowEveryMs) return
        // A window only starts on a good reading: the tracker's first seconds are often still searching.
        if (window.isEmpty() && !(sample.reliable && sample.onBody)) return
        if (window.isEmpty()) lastWindowStart = sample.tsMs
        window += sample
        if (sample.tsMs - window.first().tsMs >= 60_000) {
            val before = irn ?: output.loadIrnState()
            val (next, alert) = detector.onWindow(before, window.toList(), newId, cfg.irnSensitivity)
            irn = next
            output.saveIrnState(next)
            val read = alert != null || next.windows.lastOrNull()?.startMs == window.first().tsMs
            lastWindowIrregular = if (alert != null) true else next.windows.lastOrNull()?.takeIf { read }?.irregular
            window.clear()
            // A window spoiled by motion or a poor signal is retried on the next minute instead of waiting.
            if (!read) lastWindowStart = Long.MIN_VALUE / 2
            alert?.let { emit(it) }
        }
    }

    /** Closes every minute still collecting samples (the end of a passive delivery or a short window). */
    suspend fun closeOpenMinutes() = closeMinutes(before = Long.MAX_VALUE)

    private suspend fun closeMinutes(before: Long) {
        val closing = open.headMap(before).values.flatten()
        open.headMap(before).clear()
        val closed = MinuteAggregator.aggregate(closing, activity)
        if (closed.isEmpty()) return
        val current = loadState()
        val byStart = current.minutes.associateBy { it.minuteStartMs }.toMutableMap()
        closed.forEach { minute ->
            val merged = byStart[minute.minuteStartMs]?.let { MinuteAggregator.merge(it, minute) } ?: minute
            byStart[minute.minuteStartMs] = merged
            unsent[minute.minuteStartMs] = merged
        }
        val newest = byStart.keys.max()
        val kept = byStart.values.filter { newest - it.minuteStartMs <= KEEP_MS }.sortedBy { it.minuteStartMs }
        if (closed.any { it.minuteStartMs == newest }) output.latestMinute(byStart.getValue(newest).avgBpm)

        val alerts = rules.evaluate(kept, settings(), age(), current.lastAlerts, newId)
        val lastAlerts = current.lastAlerts + alerts.associate { HeartRateAlertRules.key(it) to it.atMs }
        save(current.copy(minutes = kept, lastAlerts = lastAlerts))
        alerts.forEach { emit(it) }
        if (unsent.size >= batchEveryMinutes) flushBatch()
    }

    /** Drops a partly collected rhythm window (e.g. when a short background window ends). */
    fun resetWindow() {
        window.clear()
        lastWindowIrregular = null
    }

    suspend fun flushBatch() {
        if (unsent.isEmpty()) return
        output.enqueueBatch(HrBatch(newId(), unsent.values.toList()))
        unsent.clear()
    }

    private suspend fun loadState(): MonitorState = state ?: output.loadMonitorState().also { state = it }

    private suspend fun save(next: MonitorState) {
        state = next
        output.saveMonitorState(next)
    }

    private suspend fun emit(alert: HealthAlert) {
        output.enqueueAlert(alert)
        output.notify(alert)
    }

    private companion object {
        const val MINUTE = 60_000L

        /** Enough history for the rules: the sustain spans plus the recovery time after exercise. */
        const val KEEP_MS = 90 * MINUTE
    }
}
