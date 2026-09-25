package com.heartline.phone.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.phone.data.ProfileRepository
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.StoredRecord
import com.heartline.shared.body.BodyComposition
import com.heartline.shared.body.BodyReport
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.UserProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One body composition measurement as the phone shows it. */
data class BodyEntryUi(val id: String, val atMs: Long, val date: String, val time: String, val report: BodyReport)

/** What the trend chart can plot. */
enum class BodyTrend { WEIGHT, BODY_FAT, MUSCLE, FAT_MASS }

/** Time span of the trend chart. */
enum class BodySpan(val days: Int?) { MONTH(30), QUARTER(91), YEAR(365), ALL(null) }

/** Newest first. [profileWeightKg]: the latest weight the profile knows (also entered on the watch). */
data class BodyDetailUi(val entries: List<BodyEntryUi> = emptyList(), val profileWeightKg: Float? = null) {
    val latest: BodyEntryUi? get() = entries.firstOrNull()
    val previous: BodyEntryUi? get() = entries.getOrNull(1)

    /** Oldest-first points of [trend] within [span] of the latest measurement. */
    fun points(trend: BodyTrend, span: BodySpan): List<Pair<Long, Float>> {
        val end = latest?.atMs ?: return emptyList()
        val from = span.days?.let { end - it * 86_400_000L } ?: Long.MIN_VALUE
        return entries.filter { it.atMs >= from }.mapNotNull { e -> trend.of(e.report)?.let { e.atMs to it } }.reversed()
    }

    /** Change of [trend] over [span]: last minus first point, or null with fewer than two points. */
    fun change(trend: BodyTrend, span: BodySpan): Float? = points(trend, span).takeIf { it.size >= 2 }?.let { it.last().second - it.first().second }
}

fun BodyTrend.of(r: BodyReport): Float? = when (this) {
    BodyTrend.WEIGHT -> r.weightKg
    BodyTrend.BODY_FAT -> r.bodyFatPercent
    BodyTrend.MUSCLE -> r.skeletalMuscleKg
    BodyTrend.FAT_MASS -> r.fatMassKg
}

object BodyFormat {
    fun entries(records: List<StoredRecord>, profile: UserProfile?, formatter: RecordFormatter): List<BodyEntryUi> = records.mapNotNull { r ->
        val s = r.summary as? RecordSummary.BodyComposition ?: return@mapNotNull null
        val report = BodyComposition.report(s, profile?.calcSex, profile?.age(), profile?.weightKg, profile?.heightCm)
        BodyEntryUi(r.id, r.entity.startedAtMs, formatter.date(r.entity.startedAtMs), formatter.time(r.entity.startedAtMs), report)
    }
}

class BodyCompositionViewModel(repository: RecordRepository, profiles: ProfileRepository, formatter: RecordFormatter) : ViewModel() {
    val state: StateFlow<BodyDetailUi> = combine(repository.observe(RecordKind.BODY_COMPOSITION), profiles.profile) { records, profile ->
        BodyDetailUi(BodyFormat.entries(records, profile, formatter), profile?.weightKg?.takeIf { it > 0 })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BodyDetailUi())
}
