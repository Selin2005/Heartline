// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.measure

import android.content.Context
import com.heartline.datalayer.diag.HLog
import com.heartline.phone.link.WatchRoutes
import com.heartline.shared.measure.RemoteMeasureLink
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.Features
import com.heartline.shared.sync.MeasureHint
import com.heartline.shared.sync.MeasureOutcome
import com.heartline.shared.sync.MeasureProblem
import com.heartline.shared.sync.MeasureResult
import com.heartline.shared.sync.MeasureStage
import com.heartline.shared.sync.MeasureState
import com.heartline.shared.sync.PeerProbe
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a measurement the phone runs is. */
enum class MeasureStep {
    /** Checking the watch link, its app version and (blood pressure) the calibration. */
    CHECKING,

    /** Opening the measurement on the watch. */
    OPENING,

    /** Opened; waiting for the watch to say it started. */
    WAITING_WATCH,
    PREPARING,
    MEASURING,
    RESULT,
    FAILED,
}

/** Why a measurement didn't happen or didn't finish, in words the screen can explain. */
enum class MeasureIssue {
    NO_WATCH,

    /** The watch app is too old to measure for the phone: update it, or measure on the watch. */
    WATCH_OUTDATED,

    /** The watch didn't open the measurement or answer in time. */
    NO_RESPONSE,

    /** The watch stopped answering in the middle. */
    LOST,
    BUSY,
    NEEDS_SETUP,
    PERMISSION,
    SDK_POLICY,
    NEEDS_CALIBRATION,
    NEEDS_PROFILE,
    SENSOR,
    LOW_SIGNAL,
    WRIST_CONTACT,
    MOVING,
    WATCH_LEFT,
    CANCELLED,
    OTHER,
    ;

    companion object {
        fun of(problem: MeasureProblem?): MeasureIssue = when (problem) {
            MeasureProblem.BUSY -> BUSY
            MeasureProblem.NEEDS_SETUP -> NEEDS_SETUP
            MeasureProblem.PERMISSION -> PERMISSION
            MeasureProblem.SDK_POLICY -> SDK_POLICY
            MeasureProblem.NEEDS_CALIBRATION -> NEEDS_CALIBRATION
            MeasureProblem.NEEDS_PROFILE -> NEEDS_PROFILE
            MeasureProblem.UNSUPPORTED, MeasureProblem.SENSOR -> SENSOR
            MeasureProblem.LOW_SIGNAL -> LOW_SIGNAL
            MeasureProblem.WRIST_CONTACT -> WRIST_CONTACT
            MeasureProblem.MOVING -> MOVING
            MeasureProblem.TIMEOUT -> NO_RESPONSE
            MeasureProblem.WATCH_LEFT -> WATCH_LEFT
            MeasureProblem.OTHER, null -> OTHER
        }
    }
}

/** One measurement as the phone shows it. */
data class MeasureUi(
    val metric: Metric,
    val sessionId: String,
    val step: MeasureStep,
    val startedAtMs: Long,
    val progress: Float = 0f,
    val secondsLeft: Int? = null,
    val hint: MeasureHint = MeasureHint.NONE,
    val live: Int? = null,
    val summary: RecordSummary? = null,
    val recordId: String? = null,
    val issue: MeasureIssue? = null,
    /** Blood-pressure calibration: the round. */
    val round: Int? = null,
    /** When [progress] arrived and how fast it has been rising (per ms), to predict it between messages. */
    val progressAtMs: Long = 0L,
    val ratePerMs: Float = 0f,
) {
    val active: Boolean get() = step != MeasureStep.RESULT && step != MeasureStep.FAILED

    /**
     * The progress at [nowMs]: the watch reports about once a second and Bluetooth can hold a
     * message for seconds, so between messages it goes on at the rate seen so far, at most
     * [MAX_LEAD] ahead of the last one and never while a hint is up.
     */
    fun progressAt(nowMs: Long): Float {
        if (step != MeasureStep.MEASURING || hint != MeasureHint.NONE || ratePerMs <= 0f) return progress
        val ahead = (ratePerMs * (nowMs - progressAtMs).coerceAtLeast(0)).coerceAtMost(MAX_LEAD)
        return (progress + ahead).coerceAtMost(0.99f)
    }

    companion object {
        const val MAX_LEAD = 0.08f
    }

    /** The watch screen that does the same the old way (the user starts it on the watch). */
    val watchRoute: String get() = MeasureSessionManager.watchRoute(metric, round)
}

/** What the phone knows of the watch app's abilities (from its last hello). */
class WatchFeatures(context: Context) {
    private val prefs = context.getSharedPreferences("watch_features", Context.MODE_PRIVATE)

    /** Null until the watch said hello once. */
    fun get(): List<String>? = prefs.getString(KEY, null)?.split(',')?.filter { it.isNotBlank() }

    fun set(features: List<String>) {
        prefs.edit().putString(KEY, features.joinToString(",")).apply()
    }

    private companion object {
        const val KEY = "features"
    }
}

/**
 * Runs a measurement from the phone: checks the watch, opens the measurement on it (it starts at
 * once there), follows its [MeasureState]s and ends on its [MeasureResult]. One at a time; it
 * outlives screens, so a widget, a tile and the app all show the same session.
 */
class MeasureSessionManager(
    private val probe: suspend () -> PeerProbe,
    private val features: () -> List<String>?,
    private val calibrated: suspend () -> Boolean,
    private val open: suspend (route: String) -> Boolean,
    private val cancelOnWatch: suspend (sessionId: String) -> Boolean,
    /** The newest stored record of [Metric] started at or after the time given, if any: (id, summary). */
    private val recordSince: suspend (Metric, Long) -> Pair<String, RecordSummary>?,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutable = MutableStateFlow<MeasureUi?>(null)
    val state: StateFlow<MeasureUi?> = mutable.asStateFlow()
    private var lastSeq = -1
    private var job: Job? = null
    private var watchdog: Job? = null

    /** Starts [metric] (blood pressure: calibration round [round]), unless the same one is already running. */
    fun start(metric: Metric, round: Int? = null) {
        val current = mutable.value
        if (current != null && current.active && current.metric == metric && current.round == round) return
        if (current != null && current.active) cancel()
        val ui = MeasureUi(metric, newId(), MeasureStep.CHECKING, now(), round = round)
        mutable.value = ui
        lastSeq = -1
        HLog.i(TAG, "start $metric${round?.let { " round $it" } ?: ""} (${ui.sessionId})")
        job = scope.launch { begin(ui) }
    }

    private suspend fun begin(ui: MeasureUi) {
        if (!ui.metric.measuresOnPhone) return fail(ui.sessionId, MeasureIssue.OTHER)
        when (probe()) {
            PeerProbe.NO_DEVICE, PeerProbe.APP_MISSING -> return fail(ui.sessionId, MeasureIssue.NO_WATCH)
            PeerProbe.REACHABLE -> Unit
        }
        val known = features()
        if (known != null && Features.REMOTE_MEASURE !in known) return fail(ui.sessionId, MeasureIssue.WATCH_OUTDATED)
        if (ui.metric == Metric.BLOOD_PRESSURE && ui.round == null && !calibrated()) return fail(ui.sessionId, MeasureIssue.NEEDS_CALIBRATION)
        update(ui.sessionId) { it.copy(step = MeasureStep.OPENING) }
        val route = RemoteMeasureLink(ui.metric, ui.sessionId, ui.round).route
        if (!open(route)) return fail(ui.sessionId, MeasureIssue.NO_RESPONSE)
        update(ui.sessionId) { it.copy(step = MeasureStep.WAITING_WATCH) }
        arm(ui.sessionId, ACCEPT_TIMEOUT_MS)
        // No answer yet: the request may have been lost on its way (the watch app was being
        // restarted). Asking once more is harmless, the watch ignores a session it already runs.
        delay(REOPEN_AFTER_MS)
        if (mutable.value?.let { it.sessionId == ui.sessionId && it.step == MeasureStep.WAITING_WATCH } == true) {
            HLog.i(TAG, "no answer from the watch after ${REOPEN_AFTER_MS / 1000} s: asking again (${ui.sessionId})")
            open(route)
        }
    }

    /** From the watch: a step of the session (late or foreign ones are ignored). */
    fun onState(state: MeasureState) {
        val ui = mutable.value ?: return
        if (state.sessionId != ui.sessionId || !ui.active || state.seq <= lastSeq) return
        lastSeq = state.seq
        if (state.stage == MeasureStage.REJECTED) return fail(ui.sessionId, MeasureIssue.of(state.reason))
        val step = when (state.stage) {
            MeasureStage.ACCEPTED, MeasureStage.PREPARING -> MeasureStep.PREPARING
            else -> MeasureStep.MEASURING
        }
        val t = now()
        // How fast the progress rises, from the last two messages (smoothed), for [MeasureUi.progressAt].
        val rate = if (ui.step == MeasureStep.MEASURING && step == MeasureStep.MEASURING && state.hint == MeasureHint.NONE && t > ui.progressAtMs + 200) {
            val instant = ((state.progress - ui.progress) / (t - ui.progressAtMs)).coerceIn(0f, 0.001f)
            if (ui.ratePerMs > 0f) ui.ratePerMs * 0.5f + instant * 0.5f else instant
        } else {
            ui.ratePerMs
        }
        mutable.value = ui.copy(
            step = step,
            progress = state.progress,
            secondsLeft = state.secondsLeft,
            hint = state.hint,
            live = state.live ?: ui.live,
            progressAtMs = t,
            ratePerMs = rate,
        )
        arm(ui.sessionId, if (step == MeasureStep.PREPARING) PREPARING_TIMEOUT_MS else SILENCE_TIMEOUT_MS)
    }

    /** From the watch, through its outbox: how the session ended. */
    fun onResult(result: MeasureResult) {
        val ui = mutable.value ?: return
        if (result.sessionId != ui.sessionId || !ui.active) return
        when (result.outcome) {
            MeasureOutcome.OK -> finish(ui.sessionId, result.recordId, result.summary)
            MeasureOutcome.CANCELLED -> fail(ui.sessionId, if (result.problem == MeasureProblem.WATCH_LEFT) MeasureIssue.WATCH_LEFT else MeasureIssue.CANCELLED)
            MeasureOutcome.FAILED -> fail(ui.sessionId, MeasureIssue.of(result.problem))
        }
    }

    /** Stops the session here and on the watch. */
    fun cancel() {
        val ui = mutable.value ?: return
        if (!ui.active) return
        HLog.i(TAG, "cancel ${ui.metric} (${ui.sessionId})")
        job?.cancel()
        scope.launch { cancelOnWatch(ui.sessionId) }
        fail(ui.sessionId, MeasureIssue.CANCELLED)
    }

    fun retry() {
        val ui = mutable.value ?: return
        start(ui.metric, ui.round)
    }

    /** Forgets a finished session (the screen closed). */
    fun dismiss() {
        if (mutable.value?.active == false) mutable.value = null
    }

    private fun arm(sessionId: String, timeoutMs: Long) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(timeoutMs)
            val ui = mutable.value ?: return@launch
            if (ui.sessionId != sessionId || !ui.active) return@launch
            // The outcome may have been delayed while the record itself came through.
            HLog.i(TAG, "no word from the watch for ${timeoutMs / 1000} s (${ui.step}, $sessionId)")
            val record = recordSince(ui.metric, ui.startedAtMs)
            if (record != null && ui.round == null) {
                finish(sessionId, record.first, record.second)
            } else {
                fail(sessionId, if (ui.step == MeasureStep.WAITING_WATCH) MeasureIssue.NO_RESPONSE else MeasureIssue.LOST)
            }
        }
    }

    private fun finish(sessionId: String, recordId: String?, summary: RecordSummary?) {
        update(sessionId) { it.copy(step = MeasureStep.RESULT, progress = 1f, recordId = recordId, summary = summary, hint = MeasureHint.NONE) }
        watchdog?.cancel()
        HLog.i(TAG, "result $sessionId")
    }

    private fun fail(sessionId: String, issue: MeasureIssue) {
        update(sessionId) { it.copy(step = MeasureStep.FAILED, issue = issue) }
        watchdog?.cancel()
        HLog.i(TAG, "failed $sessionId: $issue")
    }

    private inline fun update(sessionId: String, change: (MeasureUi) -> MeasureUi) {
        val ui = mutable.value ?: return
        if (ui.sessionId == sessionId) mutable.value = change(ui)
    }

    companion object {
        const val TAG = "Heartline/Measure"

        /** Opening plus the watch starting its app (a cold start takes a few seconds). */
        const val ACCEPT_TIMEOUT_MS = 15_000L

        /** Without an answer by then, the watch is asked once more. */
        const val REOPEN_AFTER_MS = 6_000L

        /** Blood pressure reads skin temperature and conductance first, without progress. */
        const val PREPARING_TIMEOUT_MS = 30_000L

        /** While measuring the watch reports about once a second. */
        const val SILENCE_TIMEOUT_MS = 15_000L

        fun watchRoute(metric: Metric, round: Int? = null): String = when {
            metric == Metric.BLOOD_PRESSURE && round != null -> WatchRoutes.BP_CALIBRATION
            metric == Metric.BLOOD_PRESSURE -> WatchRoutes.BLOOD_PRESSURE
            metric == Metric.HEART_RATE -> WatchRoutes.HEART_RATE
            metric == Metric.ECG -> WatchRoutes.ECG
            else -> WatchRoutes.quick(metric)
        }
    }
}
