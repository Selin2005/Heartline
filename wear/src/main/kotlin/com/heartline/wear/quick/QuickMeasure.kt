// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.quick

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.datalayer.diag.HLog
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.UserProfile
import com.heartline.shared.sync.Protocol
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.QuickEvent
import com.heartline.wear.sensor.QuickHint
import com.heartline.wear.sensor.QuickSource
import com.heartline.wear.sensor.SensorException
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.sensor.SyncScheduler
import com.heartline.wear.diag.RawCapture
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.UUID

/** The user profile (for body composition), received from the phone. */
class WatchProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { Protocol.json.decodeFromString<UserProfile>(it) }.getOrNull() },
    )
    val profile: StateFlow<UserProfile?> = state.asStateFlow()

    fun update(profile: UserProfile) {
        prefs.edit().putString(KEY, Protocol.json.encodeToString(profile)).apply()
        state.value = profile
    }

    private companion object {
        const val KEY = "profile"
    }
}

/** The on-demand wellness sources available on this build, by metric. */
class QuickSources(sources: List<QuickSource>) {
    private val byMetric = sources.associateBy { it.metric }

    operator fun get(metric: Metric): QuickSource? = byMetric[metric]

    val metrics: Set<Metric> get() = byMetric.keys
}

sealed interface QuickState {
    data object Idle : QuickState

    data object NeedsProfile : QuickState

    data class Measuring(val progress: Float, val secondsLeft: Int, val hint: QuickHint?, val bpm: Int? = null, val hrvMs: Double? = null) : QuickState

    /** Body composition: today's weight is asked when the last one is over a month old. */
    data class ConfirmWeight(val weightKg: Float) : QuickState

    /** [previous]: the last result of the same kind (for changes); [profile]: for reference ranges. */
    data class Done(val summary: RecordSummary, val previous: RecordSummary? = null, val profile: UserProfile? = null, val recordId: String? = null) : QuickState

    data class Failed(val problem: SensorProblem?, val hint: QuickHint?) : QuickState
}

/** This state for a session file: without the user profile (names, birth date, height, weight). */
internal fun QuickState.forLog(): QuickState = if (this is QuickState.Done) copy(profile = null) else this

/** Runs one SpO2 / skin temperature / body composition / stress measurement and stores it. */
class QuickMeasureViewModel(
    private val source: QuickSource,
    private val profiles: WatchProfileStore,
    private val store: WatchRecordStore,
    private val sync: SyncScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutable = MutableStateFlow<QuickState>(QuickState.Idle)
    val state: StateFlow<QuickState> = mutable.asStateFlow()
    private var job: Job? = null

    val metric get() = source.metric

    /** How long the measurement takes, for the countdown before it starts. */
    val seconds: Int get() = source.seconds

    fun start() {
        if (job?.isActive == true) return
        val profile = profiles.profile.value
        if (source.metric == Metric.BODY_COMPOSITION && profile?.isComplete != true) {
            mutable.value = QuickState.NeedsProfile
            return
        }
        if (source.metric == Metric.BODY_COMPOSITION && profile != null && profile.weightIsStale(now())) {
            mutable.value = QuickState.ConfirmWeight(profile.weightKg)
            return
        }
        measure(profile)
    }

    /** Saves today's weight (it reaches the phone with the result) and starts the measurement. */
    fun confirmWeight(weightKg: Float) {
        val profile = profiles.profile.value ?: return
        val updated = profile.copy(
            weightKg = weightKg.coerceIn(UserProfile.WEIGHT_KG.start, UserProfile.WEIGHT_KG.endInclusive),
            weightUpdatedAtMs = now(),
        )
        profiles.update(updated)
        measure(updated)
    }

    private fun measure(profile: UserProfile?) {
        val startedAt = now()
        mutable.value = QuickState.Measuring(0f, source.seconds, null)
        val raw = RawCapture.begin(source.kind.name.lowercase(), mapOf("metric" to source.metric.name))
        job = viewModelScope.launch {
            try {
                collect(profile, startedAt)
            } finally {
                // Every value of every sensor in this measurement, whatever the outcome.
                RawCapture.end(raw, notes = mapOf("result" to mutable.value.forLog().toString().take(4000)))
            }
        }
    }

    private suspend fun collect(profile: UserProfile?, startedAt: Long) {
        // SpO2: the sensor gives up near 30 s after an early movement (weak signal, out of time); one
        // more go usually works, as the watch's own app does, so it starts again once by itself.
        var tries = 0
        while (true) {
            tries++
            val failed = measureOnce(profile, startedAt) ?: return
            val again = source.metric == Metric.SPO2 && tries < SPO2_TRIES && failed.problem == null &&
                failed.hint in RETRY_HINTS
            if (!again) {
                mutable.value = QuickState.Failed(failed.problem, failed.hint)
                return
            }
            HLog.i(TAG, "SpO2 ended without a result (${failed.hint}): measuring again")
            val current = mutable.value as? QuickState.Measuring
            // The ring starts over: this is a new run of the sensor.
            mutable.value = QuickState.Measuring(0f, source.seconds, QuickHint.MEASURING_AGAIN, current?.bpm, current?.hrvMs)
        }
    }

    /** One run of the sensor; the failure it ended with, or null when it gave a result. */
    private suspend fun measureOnce(profile: UserProfile?, startedAt: Long): QuickEvent.Failed? {
        var failure: QuickEvent.Failed? = null
        run {
            source.measure(profile)
                .catch { e -> emit(QuickEvent.Failed((e as? SensorException)?.problem ?: SensorProblem.NOT_SUPPORTED)) }
                // A result or a failure ends this run, whether or not the source closes.
                .transformWhile { event ->
                    emit(event)
                    event !is QuickEvent.Result && event !is QuickEvent.Failed
                }
                .collect { event ->
                    when (event) {
                        is QuickEvent.Progress -> {
                            val current = mutable.value as? QuickState.Measuring
                            // Only a real restart moves the ring back (body composition starts over when
                            // a finger is lifted); a hint holds it, it never jumps to the start.
                            val previous = current?.progress ?: 0f
                            val restart = source.metric == Metric.BODY_COMPOSITION && event.hint != null
                            val fraction = if (restart) event.fraction else maxOf(event.fraction, previous)
                            // "Measuring again" stays up for the start of the second run, not one tick.
                            val keepAgain = current?.hint == QuickHint.MEASURING_AGAIN && event.hint == null && fraction < AGAIN_SHOWN_UNTIL
                            mutable.value = QuickState.Measuring(
                                fraction,
                                ((1 - fraction) * source.seconds).toInt().coerceAtLeast(1),
                                if (keepAgain) QuickHint.MEASURING_AGAIN else event.hint,
                                current?.bpm,
                                current?.hrvMs,
                            )
                        }
                        is QuickEvent.Live -> (mutable.value as? QuickState.Measuring)?.let { m ->
                            mutable.value = m.copy(bpm = event.bpm ?: m.bpm, hrvMs = event.hrvMs ?: m.hrvMs)
                        }
                        is QuickEvent.Failed -> failure = event
                        is QuickEvent.Result -> {
                            val previous = store.recent.first().firstOrNull { it.kind == source.kind }?.summary
                            val meta = RecordMeta(UUID.randomUUID().toString(), source.kind, startedAt, now() - startedAt, 0, 0, event.summary)
                            store.add(meta, null)
                            sync.schedule()
                            mutable.value = QuickState.Done(event.summary, previous, profile, meta.id)
                        }
                    }
                }
        }
        return failure
    }

    fun cancel() {
        job?.cancel()
        mutable.value = QuickState.Idle
    }

    fun reset() {
        mutable.value = QuickState.Idle
    }

    private companion object {
        const val TAG = "Heartline/Measure"

        /** SpO2 runs at most this often in one measurement (the second after a give-up). */
        const val SPO2_TRIES = 2

        /** Give-ups worth one more go: the sensor's weak signal and out of time. */
        val RETRY_HINTS = setOf(QuickHint.LOW_SIGNAL, QuickHint.TIMED_OUT)

        /** Progress up to which the second run keeps saying "measuring again". */
        const val AGAIN_SHOWN_UNTIL = 0.15f
    }
}
