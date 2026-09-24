package com.heartline.shared.bp

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** One calibration round: the watch's PPG features next to a cuff reading taken at the same time. */
@Serializable
data class CalibrationPoint(val features: PpgFeatureVector, val cuffSystolic: Int, val cuffDiastolic: Int, val cuffPulse: Int?)

/** SHM-style calibration: 3 cuff readings, valid for 28 days (MASTER_PLAN F7). */
@Serializable
data class BpCalibration(val id: String, val createdAtMs: Long, val points: List<CalibrationPoint>) {
    val validUntilMs: Long get() = createdAtMs + VALIDITY_MS

    fun isValid(nowMs: Long) = points.size >= REQUIRED_POINTS && nowMs < validUntilMs

    fun daysLeft(nowMs: Long): Int = ((validUntilMs - nowMs) / DAY_MS).toInt().coerceAtLeast(0)

    companion object {
        const val REQUIRED_POINTS = 3
        const val DAY_MS = 24 * 3_600_000L
        const val VALIDITY_MS = 28 * DAY_MS
    }
}

data class BpEstimate(val systolic: Int, val diastolic: Int, val pulse: Int)

sealed interface BpOutcome {
    data class Ok(val estimate: BpEstimate) : BpOutcome

    data object NeedsCalibration : BpOutcome

    data object PoorSignal : BpOutcome
}

/**
 * Personal BP estimate anchored to the user's calibration: the mean cuff values plus a linear
 * correction from how today's pulse-wave features differ from the calibration features.
 * Sensitivities follow PPG-morphology trends: a faster upstroke and an earlier, larger reflected
 * wave (which broadens the pulse and raises the late-systolic area) go with higher pressure, and
 * heart rate couples positively. Output is clamped to ±25/±15 mmHg of
 * the calibration mean: this is a wellness estimate, not a measurement.
 */
object BpEstimator {
    /** mmHg per unit change of [PpgFeatureVector.asArray] entries (systolic, diastolic). */
    private val sysWeights = doubleArrayOf(0.35, -180.0, 80.0, 22.0)
    private val diaWeights = doubleArrayOf(0.22, -90.0, 45.0, 12.0)
    const val MIN_QUALITY = 0.5

    fun estimate(calibration: BpCalibration?, features: PpgFeatureVector?, nowMs: Long): BpOutcome {
        if (calibration == null || !calibration.isValid(nowMs)) return BpOutcome.NeedsCalibration
        if (features == null || features.quality < MIN_QUALITY || features.beats < 8) return BpOutcome.PoorSignal
        val points = calibration.points
        val refFeatures = DoubleArray(4) { i -> points.map { it.features.asArray()[i] }.average() }
        val refSys = points.map { it.cuffSystolic }.average()
        val refDia = points.map { it.cuffDiastolic }.average()
        val delta = features.asArray().mapIndexed { i, v -> v - refFeatures[i] }
        val sys = refSys + delta.indices.sumOf { sysWeights[it] * delta[it] }
        val dia = refDia + delta.indices.sumOf { diaWeights[it] * delta[it] }
        val systolic = sys.coerceIn(refSys - 25, refSys + 25).roundToInt()
        val diastolic = dia.coerceIn(refDia - 15, refDia + 15).roundToInt().coerceAtMost(systolic - 20)
        return BpOutcome.Ok(BpEstimate(systolic, diastolic, features.heartRateBpm.roundToInt()))
    }
}

/** American Heart Association categories, used as wellness labels only. */
enum class BpCategory {
    NORMAL,
    ELEVATED,
    HIGH_STAGE_1,
    HIGH_STAGE_2,
    CRISIS
    ;

    companion object {
        fun of(systolic: Int, diastolic: Int): BpCategory = when {
            systolic > 180 || diastolic > 120 -> CRISIS
            systolic >= 140 || diastolic >= 90 -> HIGH_STAGE_2
            systolic >= 130 || diastolic >= 80 -> HIGH_STAGE_1
            systolic >= 120 -> ELEVATED
            else -> NORMAL
        }
    }
}
