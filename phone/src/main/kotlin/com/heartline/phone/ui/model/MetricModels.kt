// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.ProfileRepository
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

data class MetricDetailUi(val metric: Metric, val readings: List<MetricReadingUi> = emptyList()) {
    val latest get() = readings.firstOrNull()
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

class MetricDetailViewModel(private val metric: Metric, repository: RecordRepository, formatter: RecordFormatter) : ViewModel() {
    private val kind = RecordKind.entries.first { it.metric == metric }

    val state: StateFlow<MetricDetailUi> = repository.observe(kind)
        .map { MetricDetailUi(metric, MetricFormat.readings(it, formatter)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MetricDetailUi(metric))
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
