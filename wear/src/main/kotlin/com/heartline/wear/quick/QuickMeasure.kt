package com.heartline.wear.quick

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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

    data class Done(val summary: RecordSummary) : QuickState

    data class Failed(val problem: SensorProblem?, val hint: QuickHint?) : QuickState
}

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

    fun start() {
        if (job?.isActive == true) return
        val profile = profiles.profile.value
        if (source.metric == Metric.BODY_COMPOSITION && profile?.isComplete != true) {
            mutable.value = QuickState.NeedsProfile
            return
        }
        val startedAt = now()
        mutable.value = QuickState.Measuring(0f, source.seconds, null)
        job = viewModelScope.launch {
            source.measure(profile)
                .catch { e -> emit(QuickEvent.Failed((e as? SensorException)?.problem ?: SensorProblem.NOT_SUPPORTED)) }
                .collect { event ->
                    when (event) {
                        is QuickEvent.Progress -> {
                            val current = mutable.value as? QuickState.Measuring
                            val fraction = maxOf(event.fraction, current?.progress ?: 0f)
                            mutable.value = QuickState.Measuring(
                                fraction,
                                ((1 - fraction) * source.seconds).toInt().coerceAtLeast(1),
                                event.hint,
                                current?.bpm,
                                current?.hrvMs,
                            )
                        }
                        is QuickEvent.Live -> (mutable.value as? QuickState.Measuring)?.let { m ->
                            mutable.value = m.copy(bpm = event.bpm ?: m.bpm, hrvMs = event.hrvMs ?: m.hrvMs)
                        }
                        is QuickEvent.Failed -> mutable.value = QuickState.Failed(event.problem, event.hint)
                        is QuickEvent.Result -> {
                            val meta = RecordMeta(UUID.randomUUID().toString(), source.kind, startedAt, now() - startedAt, 0, 0, event.summary)
                            store.add(meta, null)
                            sync.schedule()
                            mutable.value = QuickState.Done(event.summary)
                        }
                    }
                }
        }
    }

    fun cancel() {
        job?.cancel()
        mutable.value = QuickState.Idle
    }

    fun reset() {
        mutable.value = QuickState.Idle
    }
}
