package com.heartline.shared.bp

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * One calibration round: the watch's PPG features next to a cuff reading taken at the same time.
 * [ppg] keeps the raw pulse wave so features can be recomputed when the algorithm improves.
 */
@Serializable
data class CalibrationPoint(
    val features: PpgFeatureVector,
    val cuffSystolic: Int,
    val cuffDiastolic: Int,
    val cuffPulse: Int?,
    val ppg: List<Float>? = null
)

/** SHM-style calibration: 3 cuff readings, valid for 28 days (MASTER_PLAN F7). */
@Serializable
data class BpCalibration(val id: String, val createdAtMs: Long, val points: List<CalibrationPoint>) {
    val validUntilMs: Long get() = createdAtMs + VALIDITY_MS

    /** Needs 3 rounds recorded with the current feature set, and not expired. */
    fun isValid(nowMs: Long) = points.size >= REQUIRED_POINTS &&
        nowMs < validUntilMs &&
        points.all { it.features.version >= PpgFeatureVector.VERSION }

    fun daysLeft(nowMs: Long): Int = ((validUntilMs - nowMs) / DAY_MS).toInt().coerceAtLeast(0)

    companion object {
        const val REQUIRED_POINTS = 3
        const val DAY_MS = 24 * 3_600_000L
        const val VALIDITY_MS = 28 * DAY_MS
    }
}

/** An estimate with its ± uncertainty (about one standard deviation), mmHg. */
data class BpEstimate(val systolic: Int, val diastolic: Int, val pulse: Int, val uncertaintySys: Int = 0, val uncertaintyDia: Int = 0)

enum class OutOfRangeReason { FEATURES_FAR_FROM_CALIBRATION, ESTIMATE_FAR_FROM_CALIBRATION }

sealed interface BpOutcome {
    data class Ok(val estimate: BpEstimate) : BpOutcome

    data object NeedsCalibration : BpOutcome

    data object PoorSignal : BpOutcome

    /** Today's pulse wave is outside what the calibration covers: measure again or recalibrate. */
    data class OutOfRange(val reason: OutOfRangeReason) : BpOutcome
}

/**
 * Calibrated pulse-wave-analysis estimate (algorithm 2, see docs/BP_ALGORITHM.md).
 *
 * BP = mean cuff reading + w · (features − mean calibration features), where w is a Bayesian
 * (ridge-to-prior) fit: population sensitivities from the literature act as the prior and the
 * user's 3 cuff points pull them towards their own response. Three points can't teach much, so
 * the prior dominates unless the calibration spans a real pressure range; that's by design.
 *
 * There is no hidden clamp. Instead the estimate comes with an uncertainty, and a reading whose
 * pulse wave is far from anything seen during calibration is refused (measure again or
 * recalibrate) rather than forced into a plausible-looking number.
 */
object BpEstimator {
    const val MIN_QUALITY = 0.55
    const val MIN_BEATS = 10

    /** Order of [PpgFeatureVector.modelArray]: HR, upstroke ms, width50 ms, area ratio, b/a, d/a. */
    internal val priorSys = doubleArrayOf(0.45, -0.12, -0.06, 6.0, 15.0, -5.0)
    internal val priorDia = doubleArrayOf(0.30, -0.06, -0.03, 3.0, 8.0, -3.0)

    /** Typical within-person day-to-day spread of each feature, used to judge "far from calibration". */
    internal val featureScale = doubleArrayOf(15.0, 25.0, 60.0, 0.8, 0.5, 1.0)

    const val MAX_FEATURE_Z = 3.0
    const val MAX_DELTA_SYS = 35.0
    const val MAX_DELTA_DIA = 25.0

    /** Cuff repeatability plus model error, and drift per day since calibration. */
    private const val BASE_SD = 5.0
    private const val DRIFT_SD_PER_DAY = 0.15

    fun estimate(calibration: BpCalibration?, features: PpgFeatureVector?, nowMs: Long): BpOutcome {
        if (calibration == null || !calibration.isValid(nowMs)) return BpOutcome.NeedsCalibration
        if (features == null ||
            features.quality < MIN_QUALITY ||
            features.beats < MIN_BEATS ||
            features.version < PpgFeatureVector.VERSION
        ) {
            return BpOutcome.PoorSignal
        }
        val model = fit(calibration)
        val f = features.modelArray()
        val delta = DoubleArray(f.size) { f[it] - model.refFeatures[it] }
        if (delta.indices.any { abs(delta[it]) / featureScale[it] > MAX_FEATURE_Z }) {
            return BpOutcome.OutOfRange(OutOfRangeReason.FEATURES_FAR_FROM_CALIBRATION)
        }
        val dSys = delta.indices.sumOf { model.sysWeights[it] * delta[it] }
        val dDia = delta.indices.sumOf { model.diaWeights[it] * delta[it] }
        if (abs(dSys) > MAX_DELTA_SYS || abs(dDia) > MAX_DELTA_DIA) {
            return BpOutcome.OutOfRange(OutOfRangeReason.ESTIMATE_FAR_FROM_CALIBRATION)
        }
        val days = (nowMs - calibration.createdAtMs).coerceAtLeast(0) / BpCalibration.DAY_MS.toDouble()
        // Uncertainty grows with how far today's wave is from calibration (weights are uncertain too).
        val extrapolation = 0.5 * sqrt(delta.indices.sumOf { (priorSys[it] * delta[it]).let { v -> v * v } })
        val sdSys = sqrt(
            BASE_SD * BASE_SD + model.residualSys * model.residualSys + (DRIFT_SD_PER_DAY * days).let { it * it } +
                extrapolation * extrapolation
        )
        val sdDia = sdSys * 0.7
        val systolic = (model.refSys + dSys).roundToInt()
        val diastolic = (model.refDia + dDia).roundToInt().coerceAtMost(systolic - 15)
        return BpOutcome.Ok(BpEstimate(systolic, diastolic, features.heartRateBpm.roundToInt(), sdSys.roundToInt(), sdDia.roundToInt()))
    }

    internal class Model(
        val refFeatures: DoubleArray,
        val refSys: Double,
        val refDia: Double,
        val sysWeights: DoubleArray,
        val diaWeights: DoubleArray,
        val residualSys: Double
    )

    /**
     * Posterior mean of w for y − ȳ = w·(f − f̄) + noise, with prior w ~ N(w0, (w0·priorRel)²):
     * w = w0 + (XᵀX/σ² + P)⁻¹ Xᵀ(y − X w0)/σ², solved with a small Gauss–Jordan elimination.
     */
    internal fun fit(calibration: BpCalibration): Model {
        val points = calibration.points
        val x = points.map { it.features.modelArray() }
        val n = x.first().size
        val ref = DoubleArray(n) { j -> x.map { it[j] }.average() }
        val refSys = points.map { it.cuffSystolic.toDouble() }.average()
        val refDia = points.map { it.cuffDiastolic.toDouble() }.average()
        val centred = x.map { row -> DoubleArray(n) { row[it] - ref[it] } }
        val sys = posterior(centred, points.map { it.cuffSystolic - refSys }, priorSys)
        val dia = posterior(centred, points.map { it.cuffDiastolic - refDia }, priorDia)
        val residual = sqrt(
            centred.indices.sumOf { i ->
                val predicted = centred[i].indices.sumOf { sys[it] * centred[i][it] }
                val r = (points[i].cuffSystolic - refSys) - predicted
                r * r
            } / points.size
        )
        return Model(ref, refSys, refDia, sys, dia, residual)
    }

    /**
     * Cuff reading noise, and how far (as a multiple of the prior weight) a user's own sensitivity
     * may plausibly be from the population value. Wide enough that a calibration spanning a real
     * pressure range is followed; the out-of-range checks bound any extrapolation.
     */
    private const val CUFF_SD = 4.0
    private const val PRIOR_REL = 2.5

    private fun posterior(x: List<DoubleArray>, y: List<Double>, prior: DoubleArray): DoubleArray {
        val n = prior.size
        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        val noise = CUFF_SD * CUFF_SD
        for (i in x.indices) {
            val r = y[i] - x[i].indices.sumOf { prior[it] * x[i][it] }
            for (j in 0 until n) {
                b[j] += x[i][j] * r / noise
                for (k in 0 until n) a[j][k] += x[i][j] * x[i][k] / noise
            }
        }
        for (j in 0 until n) {
            val sd = abs(prior[j]) * PRIOR_REL
            a[j][j] += 1.0 / (sd * sd)
        }
        val correction = solve(a, b) ?: return prior.copyOf()
        return DoubleArray(n) { prior[it] + correction[it] }
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { abs(m[it][col]) }
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]
            m[col] = m[pivot]
            m[pivot] = tmp
            for (r in 0 until n) {
                if (r == col) continue
                val factor = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= factor * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
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

/** One watch reading next to a cuff reading taken right after it (validation mode). */
data class BpPair(val watchSystolic: Int, val watchDiastolic: Int, val cuffSystolic: Int, val cuffDiastolic: Int)

/**
 * Agreement between watch and cuff (Bland–Altman style): mean difference (watch − cuff), its
 * standard deviation, and the share of readings within 10 mmHg. Shown to the user as is.
 */
data class BpAccuracy(
    val count: Int,
    val meanDiffSys: Double,
    val sdSys: Double,
    val meanDiffDia: Double,
    val sdDia: Double,
    val within10Percent: Int
) {
    companion object {
        fun of(pairs: List<BpPair>): BpAccuracy? {
            if (pairs.isEmpty()) return null
            val ds = pairs.map { (it.watchSystolic - it.cuffSystolic).toDouble() }
            val dd = pairs.map { (it.watchDiastolic - it.cuffDiastolic).toDouble() }
            fun sd(v: List<Double>): Double {
                if (v.size < 2) return 0.0
                val m = v.average()
                return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
            }
            val within = pairs.count { abs(it.watchSystolic - it.cuffSystolic) <= 10 && abs(it.watchDiastolic - it.cuffDiastolic) <= 10 }
            return BpAccuracy(pairs.size, ds.average(), sd(ds), dd.average(), sd(dd), (within * 100.0 / pairs.size).roundToInt())
        }
    }
}
