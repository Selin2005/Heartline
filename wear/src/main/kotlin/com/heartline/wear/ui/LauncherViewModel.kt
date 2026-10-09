// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.model.Severity
import com.heartline.shared.profile.DayPart
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.profile.StressLevel
import com.heartline.shared.profile.UserProfile
import com.heartline.shared.sensor.MetricRequirements
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.quick.WatchProfileStore
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.sensor.SensorProblem
import com.heartline.wear.tile.TodayChecks
import com.heartline.wear.ui.screens.LauncherEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The top of the launcher: a greeting (with the name, when shown) and today's check-ins. */
data class LauncherHeader(
    val part: DayPart = DayPart.MORNING,
    val name: String? = null,
    val birthday: Boolean = false,
    val done: Int = 0,
    val total: Int = TodayChecks.DEFAULT.size,
    val next: Metric? = TodayChecks.DEFAULT.first(),
    /** Days in a row with every check-in done. */
    val streak: Int = 0,
)

sealed interface LauncherState {
    data object Loading : LauncherState

    data class Ready(val entries: List<LauncherEntry>, val header: LauncherHeader = LauncherHeader()) : LauncherState

    data class Problem(val problem: SensorProblem, val resolvable: Boolean) : LauncherState
}

/** Which metrics the user pinned to the top of the launcher (watch only). */
class LauncherPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val pinned: StateFlow<List<Metric>> = state.asStateFlow()

    private fun load(): List<Metric> = prefs.getString(KEY, null).orEmpty().split(',').mapNotNull { n -> Metric.entries.firstOrNull { it.name == n } }

    fun toggle(metric: Metric) {
        val next = if (metric in state.value) state.value - metric else state.value + metric
        prefs.edit().putString(KEY, next.joinToString(",") { it.name }).apply()
        state.value = next
    }

    /** True the first time [kind] is claimed on [day] (a celebration plays once a day). */
    fun claim(kind: String, day: Long): Boolean {
        val key = "celebrated_$kind"
        if (prefs.getLong(key, -1) == day) return false
        prefs.edit().putLong(key, day).apply()
        return true
    }

    private companion object {
        const val KEY = "pinned"
    }
}

/**
 * Launcher list: only metrics this watch's capabilities support, pinned ones first, then the ones
 * the user measures most, each with its last value, how long ago, and how it turned out.
 */
class LauncherViewModel(
    private val gateway: SensorGateway,
    store: WatchRecordStore,
    profiles: WatchProfileStore? = null,
    settings: WatchSettingsStore? = null,
    private val prefs: LauncherPrefs? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val personal = combine(
        profiles?.profile ?: MutableStateFlow(null),
        settings?.settings ?: MutableStateFlow(com.heartline.shared.hr.MonitorSettings()),
        prefs?.pinned ?: MutableStateFlow(emptyList()),
    ) { profile, monitor, pinned -> Triple(profile.takeIf { monitor.showNameOnWatch }, pinned, monitor) }

    val state: StateFlow<LauncherState> = combine(gateway.state, store.history, personal) { gatewayState, recent, (profile, pinned, monitor) ->
        when (gatewayState) {
            is GatewayState.Connected -> LauncherState.Ready(
                order(MetricRequirements.supportedMetrics(gatewayState.trackers), recent, pinned, now()).map { metric ->
                    val last = recent.firstOrNull { it.kind.metric == metric }
                    LauncherEntry(metric, last?.let(::lastValue), last?.startedAtMs, last?.let(::severity), metric in pinned)
                },
                header(recent, profile, now(), zone(), monitor.dailyGoal),
            )
            is GatewayState.Failed -> LauncherState.Problem(gatewayState.problem, gatewayState.resolvable)
            else -> LauncherState.Loading
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherState.Loading)

    fun connect() = gateway.connect()

    fun togglePin(metric: Metric) = prefs?.toggle(metric)

    /**
     * Whether to throw confetti now: on the user's birthday, and when today's check-ins are all
     * done; each at most once a day.
     */
    fun celebrate(header: LauncherHeader): Boolean {
        val p = prefs ?: return false
        val day = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toEpochDay()
        val birthday = header.birthday && p.claim("birthday", day)
        val allDone = header.next == null && header.total > 0 && p.claim("checkins", day)
        return birthday || allDone
    }

    companion object {
        private const val DAY_MS = 86_400_000L

        /**
         * Pinned metrics first (in pin order), then by how often each was measured in the last
         * 30 days (ties: most recent first); never-measured ones keep the default order.
         */
        fun order(supported: List<Metric>, recent: List<RecordMeta>, pinned: List<Metric>, nowMs: Long): List<Metric> {
            val month = recent.filter { nowMs - it.startedAtMs < 30 * DAY_MS }
            val uses = month.groupingBy { it.kind.metric }.eachCount()
            val lastAt = recent.groupBy { it.kind.metric }.mapValues { (_, l) -> l.maxOf { it.startedAtMs } }
            val rest = supported.filterNot { it in pinned }
                .sortedWith(compareByDescending<Metric> { uses[it] ?: 0 }.thenByDescending { lastAt[it] ?: Long.MIN_VALUE }.thenBy { supported.indexOf(it) })
            return pinned.filter { it in supported } + rest
        }

        fun header(recent: List<RecordMeta>, profile: UserProfile?, nowMs: Long, zone: ZoneId, goal: List<Metric> = TodayChecks.DEFAULT): LauncherHeader {
            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            val today: LocalDate = now.toLocalDate()
            val byDay = com.heartline.shared.profile.DailyGoal.byDay(recent, zone)
            val doneToday = byDay[today].orEmpty()
            val checks = goal.ifEmpty { TodayChecks.DEFAULT }.map { it to (it in doneToday) }
            return LauncherHeader(
                part = DayPart.of(now.hour),
                name = profile?.displayName?.takeIf { it.isNotBlank() },
                birthday = profile?.isBirthday(today) == true,
                done = checks.count { it.second },
                total = checks.size,
                next = checks.firstOrNull { !it.second }?.first,
                streak = com.heartline.shared.profile.Streak.of(byDay, checks.map { it.first }, today),
            )
        }

        fun lastValue(meta: RecordMeta): String? = when (val s = meta.summary) {
            is RecordSummary.Ecg -> s.averageBpm?.let { "$it bpm" }
            is RecordSummary.BloodPressure -> "${s.systolic}/${s.diastolic}"
            is RecordSummary.Spo2 -> "${s.percent}%"
            is RecordSummary.SkinTemperature -> "%.1f °C".format(s.skinCelsius)
            is RecordSummary.BodyComposition -> "%.1f%%".format(s.bodyFatPercent)
            is RecordSummary.Stress -> "${s.score}/100"
            is RecordSummary.HeartRate -> "${s.bpm} bpm"
        }

        /** How the last reading turned out, for the row's dot (null: nothing to judge). */
        fun severity(meta: RecordMeta): Severity? = when (val s = meta.summary) {
            is RecordSummary.Ecg -> s.result?.severity
            is RecordSummary.BloodPressure -> when (BpCategory.of(s.systolic, s.diastolic)) {
                BpCategory.NORMAL -> Severity.NORMAL
                BpCategory.ELEVATED, BpCategory.HIGH_STAGE_1 -> Severity.WARN
                BpCategory.HIGH_STAGE_2, BpCategory.CRISIS -> Severity.ALERT
            }
            is RecordSummary.Spo2 -> if (s.percent >= 95) Severity.NORMAL else if (s.percent >= 90) Severity.WARN else Severity.ALERT
            is RecordSummary.Stress -> when (StressIndex.level(s.score)) {
                StressLevel.LOW -> Severity.NORMAL
                StressLevel.MEDIUM -> Severity.WARN
                StressLevel.HIGH -> Severity.ALERT
            }
            else -> null
        }
    }
}
