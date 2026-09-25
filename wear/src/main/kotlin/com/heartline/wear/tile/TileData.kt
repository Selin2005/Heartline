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
) {
    companion object {
        fun from(
            recent: List<RecordMeta>,
            heartRate: Int?,
            bpDaysLeft: Int?,
            heart: HeartToday? = null,
            ecgLabel: (RecordSummary.Ecg) -> String?,
        ): TileData {
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
            )
        }
    }
}
