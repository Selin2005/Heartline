// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlinx.serialization.Serializable

/**
 * One calibration round: the watch's PPG features next to a cuff reading taken at the same time.
 * [ppg] keeps the raw pulse wave so features can be recomputed when the algorithm improves.
 * [atMs] is set for points added after the calibration (a cuff check); base points use the
 * calibration's time. [gravity] is the watch's mean gravity vector while recording (arm
 * position), [standing] marks the optional standing round (algorithm 5).
 */
@Serializable
data class CalibrationPoint(
    val features: PpgFeatureVector,
    val cuffSystolic: Int,
    val cuffDiastolic: Int,
    val cuffPulse: Int?,
    val ppg: List<Float>? = null,
    val atMs: Long? = null,
    val gravity: List<Double>? = null,
    val standing: Boolean = false
)

/**
 * SHM-style calibration: 3 cuff readings, valid for 28 days. Algorithm 3 adds
 * [extraPoints]: later cuff checks paired with a watch reading. They widen the pressure range the
 * fit has seen and keep its baseline current (see docs/algorithms/BP_ALGORITHM.md). Algorithm 5
 * adds the user's [profile] (conditions and medicines that change the model).
 */
@Serializable
data class BpCalibration(
    val id: String,
    val createdAtMs: Long,
    val points: List<CalibrationPoint>,
    val extraPoints: List<CalibrationPoint> = emptyList(),
    val profile: BpProfile = BpProfile()
) {
    val validUntilMs: Long get() = createdAtMs + if (profile.shortValidity) SHORT_VALIDITY_MS else VALIDITY_MS

    /** Needs 3 rounds recorded with a feature set the estimator accepts, and not expired. */
    fun isValid(nowMs: Long) = points.size >= REQUIRED_POINTS &&
        nowMs < validUntilMs &&
        points.all { it.features.version >= PpgFeatureVector.MIN_MODEL_VERSION }

    fun daysLeft(nowMs: Long): Int = ((validUntilMs - nowMs) / DAY_MS).toInt().coerceAtLeast(0)

    /** Base and extra points with their time. */
    fun timedPoints(): List<Pair<CalibrationPoint, Long>> =
        points.map { it to createdAtMs } + extraPoints.map { it to (it.atMs ?: createdAtMs) }

    /** Lowest and highest cuff systolic the calibration has seen. */
    val systolicSpan: IntRange get() = timedPoints().map { it.first.cuffSystolic }.let { (it.minOrNull() ?: 0)..(it.maxOrNull() ?: 0) }

    /** Pulse rate the calibration was taken at (seated base rounds), bpm. */
    fun referenceHeartRate(): Double = (points.filter { !it.standing }.ifEmpty { points }).map { it.features.heartRateBpm }.average()

    /** Median perfusion index of the seated base rounds, or 0 when none has one. */
    fun referencePerfusionIndex(): Double {
        val values = points.filter { !it.standing }.map { it.features.perfusionIndex }.filter { it > 0 }.sorted()
        return if (values.isEmpty()) 0.0 else values[values.size / 2]
    }

    /** Angle from [gravity] to the nearest calibration arm position, degrees; null when unknown. */
    fun armAngleDeg(gravity: List<Double>): Double? =
        timedPoints().mapNotNull { (p, _) -> p.gravity?.let { HemodynamicStateClassifier.angleDeg(gravity, it) } }.minOrNull()

    /** Adds a cuff check; only the latest [MAX_EXTRA_POINTS] are kept. */
    fun withExtraPoint(point: CalibrationPoint): BpCalibration =
        copy(extraPoints = (extraPoints + point).sortedBy { it.atMs ?: 0L }.takeLast(MAX_EXTRA_POINTS))

    /**
     * Recomputes features from the stored raw PPG with the current extractor, so older
     * calibrations gain the new features instead of having to be redone. Points without PPG (or
     * whose PPG no longer yields features) are kept as they are.
     */
    fun upgraded(fs: Int = PPG_FS): BpCalibration {
        fun CalibrationPoint.up(): CalibrationPoint {
            if (features.version >= PpgFeatureVector.VERSION) return this
            val raw = ppg ?: return this
            val fresh = PpgFeatures.extract(raw.toFloatArray(), fs) ?: return this
            return copy(features = fresh)
        }
        return copy(points = points.map { it.up() }, extraPoints = extraPoints.map { it.up() })
    }

    companion object {
        const val REQUIRED_POINTS = 3

        /** The optional standing round after the 3 seated ones (algorithm 5). */
        const val STANDING_ROUND = 4
        const val MAX_EXTRA_POINTS = 12
        const val DAY_MS = 24 * 3_600_000L
        const val VALIDITY_MS = 28 * DAY_MS
        const val SHORT_VALIDITY_MS = 14 * DAY_MS
        const val PPG_FS = 100
    }
}

/**
 * An estimate with its ± uncertainty (about one standard deviation), mmHg.
 * [beyondCalibration]: today's pulse wave or pressure is outside what the calibration covered,
 * so the number is an extrapolation (shown, with a wider ±, never hidden). [deltaSystolic] is
 * the estimated change from the calibration's reference, mmHg.
 *
 * Algorithm 5: [heartRateDominated] means most of the change comes from a faster or slower
 * pulse rather than the pulse shape, which says little about pressure; [rangeOnly] means the
 * reading is too uncertain for one number or a category, so a range is shown instead;
 * [ectopicBeats] premature beats were left out; [notValidated]: a condition (pregnancy) for which
 * cuffless estimates are not validated.
 */
data class BpEstimate(
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val uncertaintySys: Int = 0,
    val uncertaintyDia: Int = 0,
    val beyondCalibration: Boolean = false,
    val deltaSystolic: Double = 0.0,
    val heartRateDominated: Boolean = false,
    val rangeOnly: Boolean = false,
    val ectopicBeats: Int = 0,
    val notValidated: Boolean = false
) {
    /** A very high reading driven by the pulse rate alone is not presented as very high. */
    val safety: BpSafety get() = BpSafety.of(systolic, diastolic).let {
        if (it == BpSafety.VERY_HIGH &&
            heartRateDominated
        ) {
            BpSafety.NONE
        } else {
            it
        }
    }

    /** Systolic and diastolic range shown for a [rangeOnly] reading (± one uncertainty). */
    val systolicRange: IntRange get() = (systolic - uncertaintySys)..(systolic + uncertaintySys)
    val diastolicRange: IntRange get() = (diastolic - uncertaintyDia)..(diastolic + uncertaintyDia)
}

enum class OutOfRangeReason {
    /** The pulse wave doesn't hang together (a shape no real pulse has): most likely movement or a loose strap. */
    SIGNAL_INCONSISTENT
}

sealed interface BpOutcome {
    data class Ok(val estimate: BpEstimate) : BpOutcome

    data object NeedsCalibration : BpOutcome

    data object PoorSignal : BpOutcome

    /** The recording is not a trustworthy pulse wave: measure again. Never used for a real change in pressure. */
    data class OutOfRange(val reason: OutOfRangeReason) : BpOutcome

    /**
     * Algorithm 5: the body is not in the steady state the calibration holds for (pulse racing or
     * changing, compensating response, irregular rhythm, arm elsewhere). No number is given, since
     * pulse-wave analysis reads these states as falsely high. [lowPressureSuspected]: the pattern
     * fits a drop in pressure.
     */
    data class Unsteady(val reason: UnsteadyReason, val lowPressureSuspected: Boolean = false) : BpOutcome
}

/**
 * Calibrated pulse-wave-analysis estimate (algorithm 3, see docs/algorithms/BP_ALGORITHM.md).
 *
 * BP = reference cuff reading + w · (features − reference features), where w is a Bayesian
 * (ridge-to-prior) fit: population sensitivities from the literature act as the prior and the
 * user's cuff points (3 base rounds plus any later cuff checks, recent ones weighted more) pull
 * them towards their own response.
 *
 * A reading far from the calibration is **not refused**: a genuinely high or low pressure is
 * exactly what the user needs to see. It is shown with a wider uncertainty and flagged
 * [BpEstimate.beyondCalibration]. Only a recording whose pulse wave doesn't hang together
 * (movement, loose strap) is refused, and noisy shape features that jump on their own are
 * ignored for that reading instead of blocking it.
 */
object BpEstimator {
    const val MIN_QUALITY = 0.55
    const val MIN_BEATS = 10

    /**
     * Order of [PpgFeatureVector.modelArray]: HR, upstroke ms, width50 ms, area ratio, b/a, d/a,
     * reflection delay ms. The reflection delay prior follows the stiffness-index literature
     * (shorter delay, higher pressure) and is kept small.
     */
    internal val priorSys = doubleArrayOf(0.45, -0.12, -0.06, 6.0, 15.0, -5.0, -0.04)
    internal val priorDia = doubleArrayOf(0.30, -0.06, -0.03, 3.0, 8.0, -3.0, -0.025)

    /** Typical within-person day-to-day spread of each feature, used to judge "far from calibration". */
    internal val featureScale = doubleArrayOf(15.0, 25.0, 60.0, 0.8, 0.5, 1.0, 60.0)

    /** Extractor version each feature first appeared in. */
    internal val featureMinVersion = intArrayOf(2, 2, 2, 2, 2, 2, 3)

    /** Morphology features that are robust on wrist PPG; the rest are noisy (see docs). */
    internal val coreFeatures = setOf(0, 1, 2, 6)
    internal val noisyFeatures = setOf(3, 4, 5)

    /** A noisy feature this far off is ignored for this reading (treated as unchanged). */
    const val NOISY_FEATURE_Z = 3.0

    /** Beyond this, the reading is flagged as an extrapolation. */
    const val BEYOND_FEATURE_Z = 2.5
    const val BEYOND_DELTA_SYS = 20.0
    const val BEYOND_DELTA_DIA = 14.0

    /** A marginal signal whose core shape is also far off is more likely movement than physiology. */
    const val MARGINAL_QUALITY = 0.7

    /** Hard physiological limits on the output; beyond them the model is extrapolating wildly. */
    val SYSTOLIC_LIMITS = 60..250
    val DIASTOLIC_LIMITS = 35..150

    /** Cuff repeatability plus model error, and drift per day since the latest cuff point. */
    private const val BASE_SD = 5.0
    private const val DRIFT_SD_PER_DAY = 0.15

    /**
     * Timing features lengthen as the pulse slows and shorten as it speeds up, whatever the
     * pressure (ejection time: LVET ≈ 413 − 1.7·HR ms, Weissler 1968). Without a correction a fast
     * pulse is counted twice: once through the heart rate and again as a "narrower, stiffer" wave.
     * Each timing feature is moved to [HR_REFERENCE] with these slopes (ms per bpm), kept below the
     * LVET slope because only part of each interval is ejection.
     */
    internal val heartRateSlopeMs = doubleArrayOf(0.0, 0.5, 1.2, 0.0, 0.0, 0.0, 0.9)
    const val HR_REFERENCE = 70.0

    /**
     * The pulse-rate term is bounded (tanh), mmHg: within a person the rate is a weak and
     * state-dependent pressure signal (standing, stress, fever, dehydration or blood loss raise
     * it while pressure stays or falls), so it can nudge the estimate but never drive it.
     */
    const val HR_CAP_SYS = 6.0
    const val HR_CAP_DIA = 4.0

    /** Above this ± (mmHg) a reading is shown as a range without a category. */
    const val RANGE_ONLY_SD = 12

    /** Extra ± for each source of doubt (added in quadrature), mmHg. */
    private const val HR_DOMINATED_SD = 4.0
    private const val PERFUSION_SD = 4.0
    private const val ECTOPIC_SD = 1.5
    private const val AF_SD = 4.0

    /** The perfusion index outside this ratio to the calibration's: the wrist vessels have changed tone. */
    private val PERFUSION_RANGE = 0.6..1.7

    /** [PpgFeatureVector.modelArray] with the timing features moved to [HR_REFERENCE]. */
    internal fun corrected(f: PpgFeatureVector): DoubleArray {
        val x = f.modelArray()
        val dHr = f.heartRateBpm - HR_REFERENCE
        for (i in x.indices) if (heartRateSlopeMs[i] != 0.0 && x[i] != 0.0) x[i] += heartRateSlopeMs[i] * dHr
        return x
    }

    /**
     * @param history this user's recent in-range readings' features: once there are enough, the
     * "typical spread" of each feature is learned from them instead of the population default.
     * @param context arm position during the recording, if the watch knew it.
     */
    fun estimate(
        calibration: BpCalibration?,
        features: PpgFeatureVector?,
        nowMs: Long,
        history: List<PpgFeatureVector> = emptyList(),
        context: MeasurementContext = MeasurementContext.NONE
    ): BpOutcome {
        if (calibration == null || !calibration.isValid(nowMs)) return BpOutcome.NeedsCalibration
        if (features == null || features.version < PpgFeatureVector.MIN_MODEL_VERSION) return BpOutcome.PoorSignal
        val profile = calibration.profile
        // An irregular rhythm makes the recording look noisy: say so before calling it poor signal.
        val state = HemodynamicStateClassifier.assess(features, calibration, context)
        // (Only when the pulses themselves are clean: noise also gives irregular spacing.)
        if (state.state == HemodynamicState.IRREGULAR &&
            features.quality >= MIN_QUALITY
        ) {
            return BpOutcome.Unsteady(UnsteadyReason.IRREGULAR_RHYTHM)
        }
        if (features.quality < MIN_QUALITY || features.beats < MIN_BEATS) return BpOutcome.PoorSignal
        if (!isPlausible(features)) return BpOutcome.OutOfRange(OutOfRangeReason.SIGNAL_INCONSISTENT)
        if (!state.steady) return BpOutcome.Unsteady(state.reason ?: UnsteadyReason.HEART_RATE_CHANGING, state.lowPressureSuspected)
        val model = fit(calibration, nowMs, features.version)
        val f = corrected(features)
        val scale = personalScale(history)
        val delta = DoubleArray(f.size) { if (model.active[it]) f[it] - model.refFeatures[it] else 0.0 }
        val z = DoubleArray(f.size) { abs(delta[it]) / scale[it] }
        // Noisy shape features that jump on their own are ignored rather than trusted or blocking.
        for (i in noisyFeatures) {
            if (z[i] > NOISY_FEATURE_Z) {
                delta[i] = 0.0
                z[i] = 0.0
            }
        }
        val coreFar = coreFeatures.any { z[it] > NOISY_FEATURE_Z }
        if (coreFar && features.quality < MARGINAL_QUALITY) return BpOutcome.OutOfRange(OutOfRangeReason.SIGNAL_INCONSISTENT)

        val shape = delta.indices.filter { it != HR }
        val hrSys = HR_CAP_SYS * tanh(model.sysWeights[HR] * delta[HR] * profile.heartRateWeight / HR_CAP_SYS)
        val hrDia = HR_CAP_DIA * tanh(model.diaWeights[HR] * delta[HR] * profile.heartRateWeight / HR_CAP_DIA)
        val shapeSys = shape.sumOf { model.sysWeights[it] * delta[it] }
        val dSys = shapeSys + hrSys
        val dDia = shape.sumOf { model.diaWeights[it] * delta[it] } + hrDia
        val hrDominated = abs(hrSys) >= HR_DOMINANT_MMHG && abs(hrSys) > abs(shapeSys)
        val days = (nowMs - model.latestPointMs).coerceAtLeast(0) / BpCalibration.DAY_MS.toDouble()
        // Uncertainty grows with how far today's wave is from calibration (weights are uncertain too).
        val extrapolation = 0.5 * sqrt(shape.sumOf { (priorSys[it] * delta[it]).let { v -> v * v } } + hrSys * hrSys)
        val refPi = calibration.referencePerfusionIndex()
        val vasomotor = refPi > 0 && features.perfusionIndex > 0 && features.perfusionIndex / refPi !in PERFUSION_RANGE
        val doubt = listOf(
            if (hrDominated) HR_DOMINATED_SD else 0.0,
            if (vasomotor) PERFUSION_SD else 0.0,
            ECTOPIC_SD * features.ectopicCount.coerceAtMost(2),
            if (profile.atrialFibrillation) AF_SD else 0.0
        )
        val sdSys = sqrt(
            BASE_SD * BASE_SD + model.residualSys * model.residualSys + (DRIFT_SD_PER_DAY * days).let { it * it } +
                extrapolation * extrapolation + doubt.sumOf { it * it }
        )
        val sdDia = sdSys * 0.7
        val rawSys = model.refSys + dSys
        val rawDia = model.refDia + dDia
        val systolic = rawSys.roundToInt().coerceIn(SYSTOLIC_LIMITS)
        val diastolic = rawDia.roundToInt().coerceIn(DIASTOLIC_LIMITS).coerceAtMost(systolic - 15)
        val beyond = coreFeatures.any { z[it] > BEYOND_FEATURE_Z } ||
            abs(dSys) > BEYOND_DELTA_SYS ||
            abs(dDia) > BEYOND_DELTA_DIA ||
            systolic.toDouble() != rawSys.roundToInt().toDouble() ||
            rawSys < model.minSys - BEYOND_DELTA_SYS ||
            rawSys > model.maxSys + BEYOND_DELTA_SYS ||
            hrDominated
        val sd = sdSys.roundToInt()
        return BpOutcome.Ok(
            BpEstimate(
                systolic,
                diastolic,
                features.heartRateBpm.roundToInt(),
                sd,
                sdDia.roundToInt(),
                beyondCalibration = beyond,
                deltaSystolic = dSys,
                heartRateDominated = hrDominated,
                rangeOnly = hrDominated || sd > RANGE_ONLY_SD,
                ectopicBeats = features.ectopicCount,
                notValidated = profile.pregnancy
            )
        )
    }

    /**
     * Extraction plus [estimate] for one raw recording. A recording too irregular to give a
     * feature vector (atrial fibrillation smears every beat) is reported as an irregular rhythm,
     * not as a poor signal.
     */
    fun estimateRecording(
        calibration: BpCalibration?,
        raw: FloatArray,
        fs: Int,
        nowMs: Long,
        history: List<PpgFeatureVector> = emptyList(),
        context: MeasurementContext = MeasurementContext.NONE
    ): Pair<PpgFeatureVector?, BpOutcome> {
        val features = PpgFeatures.extract(raw, fs)
        if (features == null && calibration?.isValid(nowMs) == true && !calibration.profile.atrialFibrillation) {
            val rhythm = PpgFeatures.rhythm(raw, fs)
            if (rhythm != null &&
                rhythm.shapeQuality >= IRREGULAR_SHAPE_QUALITY &&
                HemodynamicStateClassifier.irregular(rhythm.ibiCv, rhythm.ectopicCount)
            ) {
                return null to BpOutcome.Unsteady(UnsteadyReason.IRREGULAR_RHYTHM)
            }
        }
        return features to estimate(calibration, features, nowMs, history, context)
    }

    /** Pulses of an irregular recording must look at least this alike to call it a rhythm, not noise. */
    private const val IRREGULAR_SHAPE_QUALITY = 0.8

    /** Index of the heart rate in [PpgFeatureVector.modelArray]. */
    private const val HR = 0

    /** A pulse-rate term at least this large (mmHg) that outweighs the shape change dominates the reading. */
    private const val HR_DOMINANT_MMHG = 4.0

    /** Shapes no real arterial pulse has: a detection error (movement, poor contact), not physiology. */
    internal fun isPlausible(f: PpgFeatureVector): Boolean {
        if (f.heartRateBpm !in 30.0..200.0) return false
        val beat = f.beatMs
        if (f.upstrokeMs < 30.0 || f.upstrokeMs > beat * 0.6) return false
        if (f.width50Ms <= 0.0 || f.width50Ms >= beat) return false
        return true
    }

    /**
     * Per-feature spread: the population default, widened to this user's own robust spread
     * (1.4826 × MAD) once there are [MIN_HISTORY] readings. Never narrower than the default, so a
     * user with very steady readings isn't flagged for ordinary variation.
     */
    internal fun personalScale(history: List<PpgFeatureVector>): DoubleArray {
        if (history.size < MIN_HISTORY) return featureScale
        val rows = history.map { corrected(it) }
        return DoubleArray(featureScale.size) { j ->
            val values = rows.map { it[j] }.sorted()
            val median = values[values.size / 2]
            val mad = rows.map { abs(it[j] - median) }.sorted()[values.size / 2]
            maxOf(featureScale[j], 1.4826 * mad)
        }
    }

    const val MIN_HISTORY = 5

    internal class Model(
        val refFeatures: DoubleArray,
        val refSys: Double,
        val refDia: Double,
        val sysWeights: DoubleArray,
        val diaWeights: DoubleArray,
        val residualSys: Double,
        val active: BooleanArray,
        val latestPointMs: Long,
        val minSys: Double,
        val maxSys: Double
    )

    /** How fast older cuff points lose weight (the baseline drifts). Base points never go below [MIN_POINT_WEIGHT]. */
    private const val HALF_LIFE_DAYS = 14.0
    private const val MIN_POINT_WEIGHT = 0.25

    /** How fast the baseline moves to newer cuff readings. */
    private const val ANCHOR_HALF_LIFE_DAYS = 5.0
    private const val MIN_ANCHOR_WEIGHT = 0.05

    /**
     * Weighted posterior mean of w for y − ȳ = w·(f − f̄) + noise, with prior w ~ N(w0, (w0·priorRel)²):
     * w = w0 + (XᵀWX/σ² + P)⁻¹ XᵀW(y − X w0)/σ². Recent points weigh more, so the reference
     * follows a drifting baseline. A feature is used only when every point (and the current
     * reading, [currentVersion]) has it.
     */
    internal fun fit(
        calibration: BpCalibration,
        nowMs: Long = calibration.createdAtMs,
        currentVersion: Int = PpgFeatureVector.VERSION
    ): Model {
        val timed = calibration.timedPoints()
        val points = timed.map { it.first }
        val weights = timed.map { (_, at) ->
            val age = (nowMs - at).coerceAtLeast(0) / BpCalibration.DAY_MS.toDouble()
            maxOf(MIN_POINT_WEIGHT, 0.5.pow(age / HALF_LIFE_DAYS))
        }
        val minVersion = minOf(points.minOf { it.features.version }, currentVersion)
        // With a rate-setting drug, pacemaker or POTS the rate says nothing about pressure: left out of the fit.
        val active = BooleanArray(featureMinVersion.size) {
            featureMinVersion[it] <= minVersion && (it != HR || calibration.profile.heartRateWeight > 0)
        }
        val x = points.map { corrected(it.features) }
        val n = x.first().size
        val wSum = weights.sum()
        fun wMean(v: List<Double>) = v.indices.sumOf { weights[it] * v[it] } / wSum
        val ref = DoubleArray(n) { j -> wMean(x.map { it[j] }) }
        val refSys = wMean(points.map { it.cuffSystolic.toDouble() })
        val refDia = wMean(points.map { it.cuffDiastolic.toDouble() })
        val centred = x.map { row -> DoubleArray(n) { if (active[it]) row[it] - ref[it] else 0.0 } }
        // The baseline wanders (random walk): a cuff point taken long after the others is partly
        // drift, so it teaches the slopes less.
        val slopeWeights = timed.mapIndexed { i, (_, at) ->
            val gapDays = abs(at - calibration.createdAtMs) / BpCalibration.DAY_MS.toDouble()
            weights[i] * CUFF_SD * CUFF_SD / (CUFF_SD * CUFF_SD + DRIFT_VAR_PER_DAY * gapDays)
        }
        // Stiffening arteries (diabetes, kidney disease, age): the user's slopes may be further from the population's.
        val priorRel = if (calibration.profile.shortValidity) PRIOR_REL_STIFF else PRIOR_REL
        val sys = posterior(centred, points.map { it.cuffSystolic - refSys }, slopeWeights, priorSys, active, priorRel)
        val dia = posterior(centred, points.map { it.cuffDiastolic - refDia }, slopeWeights, priorDia, active, priorRel)
        val residual = sqrt(
            centred.indices.sumOf { i ->
                val predicted = centred[i].indices.sumOf { sys[it] * centred[i][it] }
                val r = (points[i].cuffSystolic - refSys) - predicted
                weights[i] * r * r
            } / wSum
        )
        // The slopes use every point; the baseline follows the most recent cuff readings, each
        // moved to the reference features along those slopes. Without later cuff checks this is
        // the same weighted mean as above.
        val anchor = timed.map { (_, at) ->
            val age = (nowMs - at).coerceAtLeast(0) / BpCalibration.DAY_MS.toDouble()
            maxOf(MIN_ANCHOR_WEIGHT, 0.5.pow(age / ANCHOR_HALF_LIFE_DAYS))
        }
        fun anchored(cuff: (CalibrationPoint) -> Int, w: DoubleArray) =
            points.indices.sumOf { i -> anchor[i] * (cuff(points[i]) - centred[i].indices.sumOf { w[it] * centred[i][it] }) } / anchor.sum()
        return Model(
            ref, anchored({ it.cuffSystolic }, sys), anchored({ it.cuffDiastolic }, dia), sys, dia, residual, active,
            timed.maxOf { it.second },
            points.minOf { it.cuffSystolic }.toDouble(),
            points.maxOf { it.cuffSystolic }.toDouble()
        )
    }

    /**
     * Cuff reading noise, and how far (as a multiple of the prior weight) a user's own sensitivity
     * may plausibly be from the population value. Wide enough that a calibration spanning a real
     * pressure range is followed.
     */
    private const val CUFF_SD = 4.0

    /** Baseline random-walk variance per day, mmHg² (≈ 2 mmHg a day). */
    private const val DRIFT_VAR_PER_DAY = 4.0
    private const val PRIOR_REL = 2.5
    private const val PRIOR_REL_STIFF = 3.5

    private fun posterior(
        x: List<DoubleArray>,
        y: List<Double>,
        w: List<Double>,
        prior: DoubleArray,
        active: BooleanArray,
        priorRel: Double
    ): DoubleArray {
        val n = prior.size
        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        val noise = CUFF_SD * CUFF_SD
        for (i in x.indices) {
            val r = y[i] - x[i].indices.sumOf { if (active[it]) prior[it] * x[i][it] else 0.0 }
            for (j in 0 until n) {
                b[j] += w[i] * x[i][j] * r / noise
                for (k in 0 until n) a[j][k] += w[i] * x[i][j] * x[i][k] / noise
            }
        }
        for (j in 0 until n) {
            val sd = abs(prior[j]) * priorRel
            a[j][j] += 1.0 / (sd * sd)
        }
        val correction = solve(a, b) ?: return DoubleArray(n) { if (active[it]) prior[it] else 0.0 }
        return DoubleArray(n) { if (active[it]) prior[it] + correction[it] else 0.0 }
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
