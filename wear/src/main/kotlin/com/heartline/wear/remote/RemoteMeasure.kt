// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.remote

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.measure.RemoteMeasureLink
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.MeasureHint
import com.heartline.shared.sync.MeasureOutcome
import com.heartline.shared.sync.MeasureProblem
import com.heartline.shared.sync.MeasureResult
import com.heartline.shared.sync.MeasureStage
import com.heartline.shared.sync.MeasureState
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.sensor.SensorProblem
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Measurements started from the phone: knows whether the watch is already measuring (then the
 * phone is told [MeasureProblem.BUSY]), passes the phone's cancels on, and gives each session a
 * [RemoteMeasureReporter].
 */
class RemoteMeasureCoordinator(
    private val sendState: suspend (MeasureState) -> Boolean,
    private val sendResult: suspend (MeasureResult) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val measuring = AtomicInteger(0)
    private val cancelled = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Session ids the phone cancelled. */
    val cancels: SharedFlow<String> = cancelled.asSharedFlow()

    /** True while any measurement screen on the watch is recording. */
    val busy: Boolean get() = measuring.get() > 0

    /** A measurement screen started (true) or stopped (false) recording. */
    fun recording(on: Boolean) {
        if (on) measuring.incrementAndGet() else measuring.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun onCancel(sessionId: String) {
        HLog.i(TAG, "phone cancelled $sessionId")
        cancelled.tryEmit(sessionId)
    }

    fun reporter(link: RemoteMeasureLink): RemoteMeasureReporter = RemoteMeasureReporter(link, sendState, sendResult, scope, now)

    /** Tells the phone at once that [link] cannot start (the watch app isn't ready or is busy). */
    fun reject(link: RemoteMeasureLink, problem: MeasureProblem) {
        HLog.i(TAG, "rejected ${link.metric} ${link.sessionId}: $problem")
        reporter(link).reject(problem)
    }

    companion object {
        const val TAG = "Heartline/Measure"
    }
}

/**
 * Reports one session to the phone: progress at most once a second (any change of stage, hint or
 * live value goes at once) and the outcome exactly once, through the outbox.
 */
class RemoteMeasureReporter(
    val link: RemoteMeasureLink,
    private val sendState: suspend (MeasureState) -> Boolean,
    private val sendResult: suspend (MeasureResult) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private var seq = 0
    private var last: MeasureState? = null
    private var lastSentAt = Long.MIN_VALUE
    private val startedAt = now()

    @Volatile
    var finished = false
        private set

    fun report(
        stage: MeasureStage,
        progress: Float = 0f,
        secondsLeft: Int? = null,
        hint: MeasureHint = MeasureHint.NONE,
        live: Int? = null,
        reason: MeasureProblem? = null,
    ) {
        if (finished) return
        val previous = last
        val t = now()
        val changed = previous == null || previous.stage != stage || previous.hint != hint || previous.reason != reason
        if (!changed && t - lastSentAt < INTERVAL_MS) return
        val state = MeasureState(link.sessionId, link.metric, stage, ++seq, progress.coerceIn(0f, 1f), secondsLeft, hint, live, reason, link.round)
        last = state
        lastSentAt = t
        if (changed) HLog.i(RemoteMeasureCoordinator.TAG, "${link.metric} ${link.sessionId}: $stage hint=$hint${reason?.let { " reason=$it" } ?: ""}")
        scope.launch { sendState(state) }
    }

    fun reject(problem: MeasureProblem) {
        report(MeasureStage.REJECTED, reason = problem)
        finish(MeasureOutcome.FAILED, problem)
    }

    fun finish(outcome: MeasureOutcome, problem: MeasureProblem? = null, recordId: String? = null, summary: RecordSummary? = null) {
        if (finished) return
        finished = true
        HLog.i(RemoteMeasureCoordinator.TAG, "${link.metric} ${link.sessionId} ended: $outcome${problem?.let { " ($it)" } ?: ""}")
        val result = MeasureResult(UUID.randomUUID().toString(), link.sessionId, link.metric, outcome, problem, recordId, summary, startedAt, link.round)
        scope.launch { sendResult(result) }
    }

    companion object {
        const val INTERVAL_MS = 1_000L

        fun hint(hint: QuickHint?): MeasureHint = when (hint) {
            null -> MeasureHint.NONE
            QuickHint.HOLD_STILL -> MeasureHint.HOLD_STILL
            QuickHint.WRIST_CONTACT -> MeasureHint.WRIST_CONTACT
            else -> MeasureHint.LOW_SIGNAL
        }

        fun problem(problem: SensorProblem?, hint: QuickHint? = null): MeasureProblem = when (problem) {
            SensorProblem.PERMISSION -> MeasureProblem.PERMISSION
            SensorProblem.SDK_POLICY -> MeasureProblem.SDK_POLICY
            SensorProblem.SERVICE_MISSING, SensorProblem.SERVICE_OUTDATED, SensorProblem.NOT_SUPPORTED -> MeasureProblem.SENSOR
            SensorProblem.OFF_BODY -> MeasureProblem.WRIST_CONTACT
            null -> when (hint) {
                QuickHint.HOLD_STILL -> MeasureProblem.MOVING
                QuickHint.WRIST_CONTACT -> MeasureProblem.WRIST_CONTACT
                null -> MeasureProblem.OTHER
                else -> MeasureProblem.LOW_SIGNAL
            }
        }

        fun newSessionId(): String = UUID.randomUUID().toString()
    }
}
