package com.heartline.phone.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.AlertEntity
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.HrMinuteEntity
import com.heartline.shared.hr.HrBuckets
import com.heartline.shared.hr.RangeBucket
import com.heartline.shared.hr.AlertKind
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
)

data class AlertUi(val id: String, val kind: AlertKind, val date: String, val time: String, val bpm: Int?, val windows: Int, val read: Boolean)

/** Pure summaries so they can be unit tested. */
object HeartSummaries {
    fun resting(minutes: List<HrMinuteEntity>): Int? {
        val rest = minutes.filter { it.resting }.map { it.avgBpm }.sorted()
        return if (rest.size < 5) null else rest[rest.size / 10]
    }

    fun hrv(minutes: List<HrMinuteEntity>): Int? {
        val values = minutes.filter { it.resting }.mapNotNull { it.rmssdMs }.sorted()
        return if (values.size < 5) null else values[values.size / 2].toInt()
    }
}

class HeartRateViewModel(
    repository: HeartRepository,
    private val formatter: RecordFormatter,
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
    ) { month, alerts ->
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
        HeartRateUi(
            latestBpm = latest?.avgBpm,
            latestTime = latest?.let { "${formatter.date(it.minuteStartMs)} ${formatter.time(it.minuteStartMs)}" },
            restingBpm = HeartSummaries.resting(todays),
            minBpm = todays.minOfOrNull { it.minBpm },
            maxBpm = todays.maxOfOrNull { it.maxBpm },
            day = HrBuckets.of(
                todays.map {
                    val local = Instant.ofEpochMilli(it.minuteStartMs).atZone(zone)
                    HrBuckets.Slot(local.hour * 60 + local.minute, it.minBpm, it.maxBpm, it.avgBpm)
                },
                30,
            ),
            week = daily(weekStart, week),
            month = daily(monthStart, month),
            weekDates = days.map { it.format(dateFormat) },
            monthDates = monthDays.map { it.format(dateFormat) },
            hrvTodayMs = HeartSummaries.hrv(todays),
            hrvWeek = days.map { day ->
                val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                HeartSummaries.hrv(week.filter { it.minuteStartMs in start until end })?.toFloat()
            },
            weekLabels = days.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, locale) },
            unreadAlerts = alerts.count { !it.read },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeartRateUi())
}

class AlertsViewModel(private val repository: HeartRepository, private val formatter: RecordFormatter) : ViewModel() {
    val alerts: StateFlow<List<AlertUi>> = repository.alerts
        .map { list -> list.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun markRead() = viewModelScope.launch { repository.markAlertsRead() }

    private fun AlertEntity.toUi() = AlertUi(id, kind, formatter.date(atMs), formatter.time(atMs), bpm, windowCount, read)
}
