package com.heartline.wear.tile

import com.heartline.shared.bp.BpCategory
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.profile.StressLevel
import com.heartline.shared.profile.TemperatureBaseline
import com.heartline.wear.monitor.HeartToday
import com.heartline.wear.ui.LauncherViewModel
import com.heartline.shared.model.Metric
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** What the tiles and complications show; built from local history so they work without the phone. */
data class TileData(
    val heartRate: Int?,
    val lastEcg: String?,
    val lastBp: String?,
    val bpDaysLeft: Int?,
    val heartMin: Int? = null,
    val heartMax: Int? = null,
    val ecgResult: EcgResult? = null,
    val ecgAtMs: Long? = null,
    val bpCategory: BpCategory? = null,
    val spo2: Int? = null,
    val stressScore: Int? = null,
    val stressLevel: StressLevel? = null,
    val hrvMs: Int? = null,
    /** Skin temperature: change from the user's baseline when known, else the reading (°C). */
    val temperature: String? = null,
    /** Today's hourly heart-rate ranges up to now, oldest first (up to 12 hours; null = no reading). */
    val heartHours: List<IntRange?> = emptyList(),
    /** The last few readings, oldest first, for the small charts. */
    val spo2History: List<Int> = emptyList(),
    val stressHistory: List<Int> = emptyList(),
    val body: Body? = null,
    /** Measurements taken today (for the Today tile). */
    val doneToday: Set<Metric> = emptySet(),
    /** The name the watch greets the user with, when they chose to show it. */
    val name: String? = null,
    val bpSystolic: Int? = null,
    val bpDiastolic: Int? = null,
) {
    /** Latest body composition, with the body-fat change since the one before. */
    data class Body(val fatPercent: Float, val muscleKg: Float?, val weightKg: Float?, val fatChange: Float?)


    companion object {
        fun from(
            recent: List<RecordMeta>,
            heartRate: Int?,
            bpDaysLeft: Int?,
            heart: HeartToday? = null,
            nowMs: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
            name: String? = null,
            ecgLabel: (RecordSummary.Ecg) -> String?,
        ): TileData {
            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            val today = now.toLocalDate()
            val bodies = recent.mapNotNull { it.summary as? RecordSummary.BodyComposition }
            val latestBody = bodies.firstOrNull()
            val ecg = recent.firstOrNull { it.summary is RecordSummary.Ecg }
            val bp = recent.firstOrNull { it.summary is RecordSummary.BloodPressure }
            val bpSummary = bp?.summary as? RecordSummary.BloodPressure
            val spo2 = recent.firstNotNullOfOrNull { it.summary as? RecordSummary.Spo2 }
            val stress = recent.firstNotNullOfOrNull { it.summary as? RecordSummary.Stress }
            val temps = recent.mapNotNull { (it.summary as? RecordSummary.SkinTemperature)?.skinCelsius }
            val temperature = temps.firstOrNull()?.let { latest ->
                TemperatureBaseline.deviation(latest, temps.drop(1))?.let { String.format(Locale.US, "%+.1f°", it) }
                    ?: String.format(Locale.US, "%.1f°", latest)
            }
            return TileData(
                heartRate = heart?.bpm ?: heartRate,
                lastEcg = ecg?.let { ecgLabel(it.summary as RecordSummary.Ecg) },
                lastBp = bp?.let(LauncherViewModel::lastValue),
                bpDaysLeft = bpDaysLeft,
                heartMin = heart?.min,
                heartMax = heart?.max,
                ecgResult = (ecg?.summary as? RecordSummary.Ecg)?.result,
                ecgAtMs = ecg?.startedAtMs,
                bpCategory = bpSummary?.let { BpCategory.of(it.systolic, it.diastolic) },
                spo2 = spo2?.percent,
                stressScore = stress?.score,
                stressLevel = stress?.let { StressIndex.level(it.score) },
                hrvMs = stress?.rmssdMs?.toInt(),
                temperature = temperature,
                heartHours = heart?.hours.orEmpty().let { h -> if (h.isEmpty()) h else h.subList((now.hour - 11).coerceAtLeast(0), now.hour + 1) },
                spo2History = recent.mapNotNull { (it.summary as? RecordSummary.Spo2)?.percent }.take(7).reversed(),
                stressHistory = recent.mapNotNull { (it.summary as? RecordSummary.Stress)?.score }.take(7).reversed(),
                body = latestBody?.let { b -> Body(b.bodyFatPercent, b.skeletalMuscleKg, b.weightKg, bodies.getOrNull(1)?.let { b.bodyFatPercent - it.bodyFatPercent }) },
                doneToday = recent.filter { Instant.ofEpochMilli(it.startedAtMs).atZone(zone).toLocalDate() == today }.map { it.kind.metric }.toSet(),
                name = name?.takeIf { it.isNotBlank() },
                bpSystolic = bpSummary?.systolic,
                bpDiastolic = bpSummary?.diastolic,
            )
        }
    }
}
