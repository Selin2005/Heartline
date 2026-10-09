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
import com.heartline.shared.hr.HeartBaseline
import com.heartline.shared.hr.HeartHistory
import com.heartline.shared.hr.HeartLimits
import com.heartline.shared.hr.HeartTrend
import com.heartline.shared.hr.Histogram
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.profile.Sex
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
    /** The last heart-rate check (measured from the phone): bpm, its range and when. */
    val lastCheck: com.heartline.shared.model.RecordSummary.HeartRate? = null,
    val lastCheckTime: String? = null,
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
    /** Maximum heart rate the zones are based on (age-based, raised to the wearer's own hardest workouts). */
    val maxHr: Int = 190,
    /** Lower bound of each zone (Karvonen: 50, 60, 70, 80, 90 % of the reserve above the resting rate). */
    val zoneBounds: List<Int> = emptyList(),
    /** The personal limits and the normal they come from. */
    val limits: HeartLimits? = null,
    /** Heart rate in sleep for each of the last 28 nights (oldest first), to see the trend. */
    val nights: List<Float?> = emptyList(),
    /** Minutes today in each heart-rate zone ([zoneBounds]). */
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
    val normal: Int? = null,
    val trend: HeartTrend? = null,
    /** A blood-oxygen, temperature, combined or stress notice, and its value. */
    val vital: com.heartline.shared.hr.VitalAlert? = null,
    val value: Float? = null,
    /** Sent by a watch app version other than the phone's (older algorithms), or null. */
    val otherWatchVersion: String? = null,
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

    /** The phone's minutes as daily histograms (the watch's baseline input), for older watches that send no limits. */
    fun history(minutes: List<HrMinuteEntity>, zone: ZoneId): HeartHistory = minutes.fold(HeartHistory()) { h, m ->
        h.record(HrMinute(m.minuteStartMs, m.avgBpm, m.minBpm, m.maxBpm, m.rmssdMs, m.resting, m.activity), zone)
    }

    /** Median heart rate in sleep of the night ending on [day], if there are enough readings. */
    fun night(history: HeartHistory, day: Long): Int? = history.days.firstOrNull { it.day == day }
        ?.let { Histogram(it.sleep) }?.takeIf { it.count >= HeartBaseline.MIN_NIGHT_READINGS }?.median()?.roundToInt()

    /** Minutes in each zone given its lower [bounds]; minutes below the first are not counted. */
    fun zones(minutes: List<HrMinuteEntity>, bounds: List<Int>): List<Int> {
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
    /** The wearer's age and sex, the settings (sensitivity) and the limits the watch sent, for the personal limits. */
    profile: Flow<Pair<Int?, Sex?>> = flowOf(null to null),
    settings: Flow<MonitorSettings> = flowOf(MonitorSettings()),
    watchLimits: Flow<HeartLimits?> = flowOf(null),
    /** Heart-rate checks as (time, result), newest first. */
    checks: Flow<List<Pair<Long, com.heartline.shared.model.RecordSummary.HeartRate>>> = flowOf(emptyList()),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
    today: LocalDate = LocalDate.now(zone),
) : ViewModel() {
    private val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    private val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
    private val monthStart = today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
    private val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private val todayKey = today.toEpochDay()

    private val base = combine(
        repository.minutes(monthStart, dayEnd),
        repository.alerts,
        profile,
        settings,
        watchLimits,
    ) { month, alerts, (years, sex), monitor, sent ->
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
        // The watch's own limits when it sent them; otherwise the same calculation on this phone's data.
        val history = HeartSummaries.history(month, zone)
        val limits = sent ?: HeartBaseline.limits(history, todayKey, monitor.alertSensitivity, years, sex, monitor.health)
        val maxHr = limits.exerciseMax
        val sleep = HeartSummaries.sleep(todays)
        // Zones start from the resting rate (the lowest you settle to awake, as the weekly chart),
        // not from the usual awake-and-still rate the limits use: that put zone 1 at 136 bpm.
        val zoneRest = HeartSummaries.resting(week) ?: limits.restNormal
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
            zoneBounds = HeartBaseline.zones(maxHr, zoneRest),
            zoneMinutes = HeartSummaries.zones(todays, HeartBaseline.zones(maxHr, zoneRest)),
            limits = limits,
            nights = (0 until 28).map { i -> HeartSummaries.night(history, todayKey - 27 + i)?.toFloat() },
            restingWeek = days.map { day -> HeartSummaries.resting(week.filter { it.minuteStartMs in dayRange(day) })?.toFloat() },
            dayByActivity = todays.groupBy { it.activity }.mapValues { (_, list) -> dayBuckets(list) },
        )
    }

    val state: StateFlow<HeartRateUi> = combine(base, checks) { ui, list ->
        val last = list.firstOrNull()
        ui.copy(lastCheck = last?.second, lastCheckTime = last?.let { "${formatter.date(it.first)} ${formatter.time(it.first)}" })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeartRateUi())
}

class AlertsViewModel(
    private val repository: HeartRepository,
    private val formatter: RecordFormatter,
    /** ECG recordings as (time, result), to show how a rhythm notification was followed up. */
    ecgs: Flow<List<Pair<Long, EcgResult>>> = flowOf(emptyList()),
    private val phoneVersion: String = com.heartline.phone.BuildConfig.VERSION_NAME,
) : ViewModel() {
    val alerts: StateFlow<List<AlertUi>> = combine(repository.alerts, ecgs) { list, recordings ->
        list.map { it.toUi(HeartSummaries.ecgFollowUp(it, recordings)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun markRead() = viewModelScope.launch { repository.markAlertsRead() }

    private fun AlertEntity.toUi(ecgRegular: Boolean?) =
        AlertUi(id, kind, formatter.date(atMs), formatter.time(atMs), bpm, windowCount, read, threshold, context, ecgRegular, normal, trend, vital, value, watchVersion?.takeIf { it != phoneVersion })
}
