// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.AlertEntity
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.HrMinuteEntity
import com.heartline.shared.hr.HrBuckets
import com.heartline.shared.hr.RangeBucket
import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.MaxHr
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.irn.IrregularRhythmDetector
import com.heartline.shared.model.EcgResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

data class HeartRateUi(
    val latestBpm: Int? = null,
    val latestTime: String? = null,
    val restingBpm: Int? = null,
    val minBpm: Int? = null,
    val maxBpm: Int? = null,
    /** 30-minute min–max buckets for today (index 0..47). */
    val day: List<RangeBucket> = emptyList(),
    /** Daily min–max for the last 7 and 30 days (index = day, oldest first). */
    val week: List<RangeBucket> = emptyList(),
    val month: List<RangeBucket> = emptyList(),
    /** Full date labels for week/month bars, shown when a bar is selected. */
    val weekDates: List<String> = emptyList(),
    val monthDates: List<String> = emptyList(),
    val hrvTodayMs: Int? = null,
    val hrvWeek: List<Float?> = emptyList(),
    val weekLabels: List<String> = emptyList(),
    val unreadAlerts: Int = 0,
    /** Today's sleep (average and lowest) and exercise (minutes and highest), from the watch's recognition. */
    val sleepAvgBpm: Int? = null,
    val sleepMinBpm: Int? = null,
    val exerciseMinutes: Int = 0,
    val exercisePeakBpm: Int? = null,
    /** Maximum heart rate the zones are based on (age-based, or the exercise limit chosen in settings). */
    val maxHr: Int = 190,
    /** Minutes today in each heart-rate zone (50–60, 60–70, 70–80, 80–90, 90+ % of [maxHr]). */
    val zoneMinutes: List<Int> = emptyList(),
    /** Resting heart rate for each of the last 7 days (oldest first). */
    val restingWeek: List<Float?> = emptyList(),
    /** Today's chart for each kind of minute (only kinds with data). */
    val dayByActivity: Map<HrContext, List<RangeBucket>> = emptyMap(),
)

data class AlertUi(
    val id: String,
    val kind: AlertKind,
    val date: String,
    val time: String,
    val bpm: Int?,
    val windows: Int,
    val read: Boolean,
    val threshold: Int? = null,
    val context: HrContext? = null,
    /** An ECG taken within two hours after a rhythm notification: true when it looked regular. Null without one. */
    val ecgRegular: Boolean? = null,
)

/** Pure summaries so they can be unit tested. */
object HeartSummaries {
    /** Resting heart rate: a low percentile of minutes awake and still (sleep and exercise left out). */
    fun resting(minutes: List<HrMinuteEntity>): Int? {
        val rest = minutes.filter { it.activity == HrContext.REST }.map { it.avgBpm }.sorted()
        return if (rest.size < 5) null else rest[rest.size / 10]
    }

    /** HRV (RMSSD) median of still minutes, awake or asleep. */
    fun hrv(minutes: List<HrMinuteEntity>): Int? {
        val values = minutes.filter { it.activity == HrContext.REST || it.activity == HrContext.SLEEP }.mapNotNull { it.rmssdMs }.sorted()
        return if (values.size < 5) null else values[values.size / 2].toInt()
    }

    fun sleep(minutes: List<HrMinuteEntity>): Pair<Int, Int>? {
        val sleep = minutes.filter { it.activity == HrContext.SLEEP }
        if (sleep.size < 5) return null
        return sleep.map { it.avgBpm }.average().roundToInt() to sleep.minOf { it.minBpm }
    }

    /**
     * For a rhythm notification: whether the first usable ECG within two hours after it looked
     * regular (true), showed something else (false), or there was none (null).
     */
    fun ecgFollowUp(alert: AlertEntity, ecgs: List<Pair<Long, EcgResult>>): Boolean? {
        if (alert.kind != AlertKind.IRREGULAR_RHYTHM) return null
        val window = alert.atMs..alert.atMs + IrregularRhythmDetector.ECG_FOLLOW_UP_MS
        val result = ecgs.filter { it.first in window && it.second != EcgResult.POOR_RECORDING && it.second != EcgResult.INCONCLUSIVE }
            .minByOrNull { it.first }?.second ?: return null
        return result == EcgResult.SINUS_RHYTHM
    }

    /** Minutes in each zone; minutes below 50 % are not counted. */
    fun zones(minutes: List<HrMinuteEntity>, maxHr: Int): List<Int> {
        val bounds = MaxHr.zones(maxHr)
        return bounds.indices.map { i ->
            val lo = bounds[i]
            val hi = bounds.getOrNull(i + 1) ?: Int.MAX_VALUE
            minutes.count { it.avgBpm in lo until hi }
        }
    }
}

class HeartRateViewModel(
    repository: HeartRepository,
    private val formatter: RecordFormatter,
    /** The wearer's age and their exercise limit, for the zones. */
    age: Flow<Int?> = flowOf(null),
    settings: Flow<MonitorSettings> = flowOf(MonitorSettings()),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
    today: LocalDate = LocalDate.now(zone),
) : ViewModel() {
    private val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    private val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
    private val monthStart = today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
    private val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val state: StateFlow<HeartRateUi> = combine(
        repository.minutes(monthStart, dayEnd),
        repository.alerts,
        age,
        settings,
    ) { month, alerts, years, monitor ->
        val week = month.filter { it.minuteStartMs >= weekStart }
        val todays = week.filter { it.minuteStartMs >= dayStart }
        val latest = week.lastOrNull()
        val days = (0..6).map { today.minusDays(6L - it) }
        val monthDays = (0..29).map { today.minusDays(29L - it) }
        fun daily(from: Long, list: List<HrMinuteEntity>) = HrBuckets.of(
            list.map { m ->
                val dayIndex = ((m.minuteStartMs - from) / 86_400_000L).toInt()
                HrBuckets.Slot(dayIndex, m.minBpm, m.maxBpm, m.avgBpm)
            },
            1,
        )
        val dateFormat = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", locale)
        fun dayBuckets(list: List<HrMinuteEntity>) = HrBuckets.of(
            list.map {
                val local = Instant.ofEpochMilli(it.minuteStartMs).atZone(zone)
                HrBuckets.Slot(local.hour * 60 + local.minute, it.minBpm, it.maxBpm, it.avgBpm)
            },
            30,
        )
        fun dayRange(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli() until day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val maxHr = monitor.exerciseLimit(years)
        val sleep = HeartSummaries.sleep(todays)
        val exercise = todays.filter { it.activity == HrContext.EXERCISE }
        HeartRateUi(
            latestBpm = latest?.avgBpm,
            latestTime = latest?.let { "${formatter.date(it.minuteStartMs)} ${formatter.time(it.minuteStartMs)}" },
            restingBpm = HeartSummaries.resting(todays),
            minBpm = todays.minOfOrNull { it.minBpm },
            maxBpm = todays.maxOfOrNull { it.maxBpm },
            day = dayBuckets(todays),
            week = daily(weekStart, week),
            month = daily(monthStart, month),
            weekDates = days.map { it.format(dateFormat) },
            monthDates = monthDays.map { it.format(dateFormat) },
            hrvTodayMs = HeartSummaries.hrv(todays),
            hrvWeek = days.map { day -> HeartSummaries.hrv(week.filter { it.minuteStartMs in dayRange(day) })?.toFloat() },
            weekLabels = days.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, locale) },
            unreadAlerts = alerts.count { !it.read },
            sleepAvgBpm = sleep?.first,
            sleepMinBpm = sleep?.second,
            exerciseMinutes = exercise.size,
            exercisePeakBpm = exercise.maxOfOrNull { it.maxBpm },
            maxHr = maxHr,
            zoneMinutes = HeartSummaries.zones(todays, maxHr),
            restingWeek = days.map { day -> HeartSummaries.resting(week.filter { it.minuteStartMs in dayRange(day) })?.toFloat() },
            dayByActivity = todays.groupBy { it.activity }.mapValues { (_, list) -> dayBuckets(list) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeartRateUi())
}

class AlertsViewModel(
    private val repository: HeartRepository,
    private val formatter: RecordFormatter,
    /** ECG recordings as (time, result), to show how a rhythm notification was followed up. */
    ecgs: Flow<List<Pair<Long, EcgResult>>> = flowOf(emptyList()),
) : ViewModel() {
    val alerts: StateFlow<List<AlertUi>> = combine(repository.alerts, ecgs) { list, recordings ->
        list.map { it.toUi(HeartSummaries.ecgFollowUp(it, recordings)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun markRead() = viewModelScope.launch { repository.markAlertsRead() }

    private fun AlertEntity.toUi(ecgRegular: Boolean?) =
        AlertUi(id, kind, formatter.date(atMs), formatter.time(atMs), bpm, windowCount, read, threshold, context, ecgRegular)
}
