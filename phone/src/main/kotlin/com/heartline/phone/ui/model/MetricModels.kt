// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.ProfileRepository
import com.heartline.phone.data.Spo2SampleEntity
import com.heartline.phone.data.TempSampleEntity
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.vitals.Spo2Sample
import com.heartline.shared.vitals.TempSample
import com.heartline.shared.vitals.VitalsBaseline
import com.heartline.shared.vitals.VitalsHistory
import com.heartline.shared.vitals.VitalsLimits
import com.heartline.shared.vitals.VitalsMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.StoredRecord
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.profile.TemperatureBaseline
import com.heartline.shared.profile.UserProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/** A formatted value for one reading of SpO2 / skin temperature / body composition / stress. */
data class MetricReadingUi(
    val id: String,
    val date: String,
    val time: String,
    val value: String,
    val unit: String?,
    /** Plot value for the trend bars. */
    val plot: Float,
    val details: List<Pair<Int, String>> = emptyList(),
)

data class MetricDetailUi(val metric: Metric, val readings: List<MetricReadingUi> = emptyList(), val background: BackgroundVitalsUi? = null) {
    val latest get() = readings.firstOrNull()
}

/**
 * Readings the watch takes by itself (blood oxygen hourly, skin temperature in sleep and by day):
 * the usual values, last night, the last 28 nights (oldest first) and today's latest readings.
 */
data class BackgroundVitalsUi(
    /** SpO2: usual awake and asleep (%); temperature: usual night (°C, null until 2 nights). */
    val usualDay: Float? = null,
    val usualNight: Float? = null,
    /** SpO2: last night's median and lowest; temperature: last night's change from usual (°C). */
    val lastNight: Float? = null,
    val lastNightLow: Float? = null,
    val learning: Boolean = true,
    val nights: List<Float?> = emptyList(),
    val recent: List<Pair<String, String>> = emptyList(),
)

/** Background readings to [BackgroundVitalsUi] (pure, unit tested). */
object BackgroundVitals {
    private val zone get() = java.time.ZoneId.systemDefault()

    fun spo2(samples: List<Spo2SampleEntity>, limits: VitalsLimits?, today: Long, formatter: RecordFormatter, zoneId: java.time.ZoneId = zone): BackgroundVitalsUi? {
        if (samples.isEmpty()) return null
        val history = vitalsHistory(samples, emptyList(), zoneId)
        val lastNight = history.days.firstOrNull { it.day == today }?.let { com.heartline.shared.hr.Histogram(it.spo2Night) }?.takeIf { it.count > 0 }
        val computed = VitalsBaseline.limits(history, today, limits?.sensitivity ?: com.heartline.shared.hr.AlertSensitivity.STANDARD, null)
        val l = limits ?: computed
        return BackgroundVitalsUi(
            usualDay = l.spo2DayNormal.toFloat(),
            usualNight = l.spo2NightNormal.toFloat(),
            lastNight = lastNight?.median()?.toFloat(),
            lastNightLow = lastNight?.percentile(0.0)?.toFloat(),
            learning = l.spo2Confidence < 0.8,
            nights = (0 until 28).map { i -> VitalsBaseline.nightSpo2(history, today - 27 + i)?.toFloat() },
            recent = samples.takeLast(6).reversed().map { formatter.time(it.tsMs) to "${it.percent} %" },
        )
    }

    fun temperature(samples: List<TempSampleEntity>, limits: VitalsLimits?, today: Long, formatter: RecordFormatter, zoneId: java.time.ZoneId = zone): BackgroundVitalsUi? {
        if (samples.isEmpty()) return null
        val history = vitalsHistory(emptyList(), samples, zoneId)
        val base = VitalsBaseline.tempBaseline(history, today)
        return BackgroundVitalsUi(
            usualNight = (limits?.tempBaseline ?: base?.first?.toFloat()),
            lastNight = limits?.lastNightDeviation ?: VitalsBaseline.nightTemp(history, today)?.let { t -> base?.let { (t - it.first).toFloat() } },
            learning = (limits?.tempNights ?: base?.third ?: 0) < VitalsBaseline.TEMP_SHOW_NIGHTS,
            nights = (0 until 28).map { i -> VitalsBaseline.nightTemp(history, today - 27 + i)?.toFloat() },
            recent = samples.takeLast(6).reversed().map { formatter.time(it.tsMs) to String.format(Locale.US, "%.1f °C", it.skinC) },
        )
    }

    /** The same daily history the watch keeps, rebuilt from the phone's samples. */
    fun vitalsHistory(spo2: List<Spo2SampleEntity>, temps: List<TempSampleEntity>, zoneId: java.time.ZoneId): VitalsHistory {
        val monitor = VitalsMonitor(zoneId) { "" }
        val off = MonitorSettings().copy(spo2Monitoring = false)
        var h = VitalsHistory()
        spo2.forEach { h = monitor.onSpo2(h, Spo2Sample(it.tsMs, it.percent, it.context, it.confirmation), emptyList(), off).first }
        temps.forEach { h = monitor.onTemp(h, TempSample(it.tsMs, it.skinC, it.ambientC, it.context, it.counted)) }
        return h
    }
}

/** Pure mapping from stored records to display values, shared with Home tiles. */
object MetricFormat {
    fun reading(record: StoredRecord, previous: List<StoredRecord>, formatter: RecordFormatter): MetricReadingUi? {
        val e = record.entity
        val date = formatter.date(e.startedAtMs)
        val time = formatter.time(e.startedAtMs)
        return when (val s = record.summary) {
            is RecordSummary.Spo2 -> MetricReadingUi(
                e.id,
                date,
                time,
                "${s.percent}",
                "%",
                s.percent.toFloat(),
                listOfNotNull(s.heartRate?.let { com.heartline.phone.R.string.detail_heart_rate to "$it bpm" }),
            )
            is RecordSummary.SkinTemperature -> {
                val history = previous.mapNotNull { (it.summary as? RecordSummary.SkinTemperature)?.skinCelsius }
                val deviation = TemperatureBaseline.deviation(s.skinCelsius, history)
                MetricReadingUi(
                    e.id,
                    date,
                    time,
                    deviation?.let { String.format(Locale.US, "%+.1f", it) } ?: String.format(Locale.US, "%.1f", s.skinCelsius),
                    "°C",
                    s.skinCelsius,
                    listOfNotNull(
                        com.heartline.phone.R.string.detail_skin to String.format(Locale.US, "%.1f °C", s.skinCelsius),
                        s.ambientCelsius?.let { com.heartline.phone.R.string.detail_ambient to String.format(Locale.US, "%.1f °C", it) },
                    ),
                )
            }
            is RecordSummary.BodyComposition -> MetricReadingUi(
                e.id,
                date,
                time,
                String.format(Locale.US, "%.1f", s.bodyFatPercent),
                "%",
                s.bodyFatPercent,
                listOfNotNull(
                    s.skeletalMuscleKg?.let { com.heartline.phone.R.string.detail_muscle to String.format(Locale.US, "%.1f kg", it) },
                    s.bodyWaterKg?.let { com.heartline.phone.R.string.detail_water to String.format(Locale.US, "%.1f kg", it) },
                    s.bmrKcal?.let { com.heartline.phone.R.string.detail_bmr to "$it kcal" },
                ),
            )
            is RecordSummary.Stress -> MetricReadingUi(
                e.id,
                date,
                time,
                "${s.score}",
                null,
                s.score.toFloat(),
                listOfNotNull(
                    com.heartline.phone.R.string.detail_level to StressIndex.level(s.score).name.lowercase().replaceFirstChar { it.uppercase() },
                    s.rmssdMs?.let { com.heartline.phone.R.string.detail_hrv to "${it.toInt()} ms" },
                    s.skinConductanceMicroSiemens?.let { com.heartline.phone.R.string.detail_eda to String.format(Locale.US, "%.1f µS", it) },
                ),
            )
            else -> null
        }
    }

    fun readings(records: List<StoredRecord>, formatter: RecordFormatter) =
        records.mapIndexedNotNull { i, r -> reading(r, records.drop(i + 1), formatter) }
}

class MetricDetailViewModel(
    private val metric: Metric,
    repository: RecordRepository,
    formatter: RecordFormatter,
    /** Background readings from the watch (blood oxygen and skin temperature only). */
    heart: HeartRepository? = null,
    vitalsLimits: Flow<VitalsLimits?> = flowOf(null),
    today: java.time.LocalDate = java.time.LocalDate.now(),
) : ViewModel() {
    private val kind = RecordKind.entries.first { it.metric == metric }
    private val since = today.minusDays(29).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val background: Flow<BackgroundVitalsUi?> = when {
        heart == null -> flowOf(null)
        metric == Metric.SPO2 -> combine(heart.spo2Since(since), vitalsLimits) { s, l -> BackgroundVitals.spo2(s, l, today.toEpochDay(), formatter) }
        metric == Metric.SKIN_TEMPERATURE -> combine(heart.tempsSince(since), vitalsLimits) { s, l -> BackgroundVitals.temperature(s, l, today.toEpochDay(), formatter) }
        else -> flowOf(null)
    }

    val state: StateFlow<MetricDetailUi> = combine(repository.observe(kind), background) { records, bg ->
        MetricDetailUi(metric, MetricFormat.readings(records, formatter), bg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MetricDetailUi(metric))
}

class ProfileViewModel(private val repository: ProfileRepository) : ViewModel() {
    val profile: StateFlow<UserProfile?> = repository.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A changed weight is stamped with now, so the watch won't ask for it again for a month. */
    fun save(profile: UserProfile, onSaved: () -> Unit) = viewModelScope.launch {
        val old = this@ProfileViewModel.profile.value ?: repository.profile.first()
        val stamped = if (old != null && old.weightKg == profile.weightKg) {
            profile.copy(weightUpdatedAtMs = old.weightUpdatedAtMs)
        } else {
            profile.copy(weightUpdatedAtMs = System.currentTimeMillis())
        }
        repository.save(stamped)
        onSaved()
    }
}
