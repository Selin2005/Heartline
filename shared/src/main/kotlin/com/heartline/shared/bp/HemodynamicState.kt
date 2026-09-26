// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * Conditions and medicines that change how the pulse wave relates to pressure (algorithm 5).
 * Entered by the user on the phone and carried with the calibration. All default to "no", so
 * older calibrations decode unchanged.
 */
@Serializable
data class BpProfile(
    /** Beta blockers (and similar rate-limiting drugs) blunt the heart-rate response. */
    val betaBlocker: Boolean = false,
    /** A pacemaker sets the rate, so it says nothing about pressure. */
    val pacemaker: Boolean = false,
    /** Known atrial fibrillation: the rhythm is always irregular. */
    val atrialFibrillation: Boolean = false,
    /** POTS or orthostatic hypotension: the pulse races while pressure stays or falls. */
    val orthostaticIntolerance: Boolean = false,
    /** Cuffless estimates are not validated in pregnancy. */
    val pregnancy: Boolean = false,
    /** Diabetes or kidney disease: arteries stiffen faster, so the calibration ages sooner. */
    val diabetesOrKidney: Boolean = false,
    val ageYears: Int? = null
) {
    /** Share of the heart-rate term the estimator may use (0: the rate is not a pressure signal). */
    val heartRateWeight: Double get() = if (betaBlocker || pacemaker || orthostaticIntolerance) 0.0 else 1.0

    /** Stiffening arteries: the calibration is trusted for [BpCalibration.SHORT_VALIDITY_MS]. */
    val shortValidity: Boolean get() = diabetesOrKidney || (ageYears ?: 0) >= 65

    /** Recording length on the watch: longer in AF so enough similar beats are found. */
    val recordingSeconds: Int get() = if (atrialFibrillation) 45 else 20

    companion object {
        val NONE = BpProfile()
    }
}

/**
 * What the watch knew about the measurement besides the pulse wave: the mean gravity vector in
 * the watch's frame (arm position) while recording. Null values are unknown and not used.
 */
data class MeasurementContext(val gravity: List<Double>? = null) {
    companion object {
        val NONE = MeasurementContext()
    }
}

enum class HemodynamicState {
    /** At rest in a steady state: the calibrated model applies. */
    STEADY,

    /** The pulse rate or amplitude is still changing (just stood up, just moved, recovering). */
    TRANSIENT,

    /** Fast pulse with a much weaker wrist pulse: a sympathetic, compensating response. */
    COMPENSATORY,

    /** Irregular rhythm (atrial-fibrillation-like or frequent premature beats). */
    IRREGULAR
}

enum class UnsteadyReason {
    HEART_RATE_CHANGING,
    PULSE_AMPLITUDE_CHANGING,
    COMPENSATORY_RESPONSE,
    IRREGULAR_RHYTHM,
    ARM_POSITION
}

/**
 * [lowPressureSuspected]: the pattern (racing pulse, weak and changing wrist pulse) fits a drop
 * in pressure that the body is compensating for — the situation where pulse-wave analysis reads
 * falsely *high*. Wellness wording only.
 */
data class StateAssessment(val state: HemodynamicState, val reason: UnsteadyReason? = null, val lowPressureSuspected: Boolean = false) {
    val steady: Boolean get() = state == HemodynamicState.STEADY

    companion object {
        val STEADY = StateAssessment(HemodynamicState.STEADY)
    }
}

/**
 * Decides whether a recording is in a steady state (algorithm 5, see
 * docs/algorithms/BP_ALGORITHM.md). Calibrated pulse-wave analysis only holds in the resting,
 * steady state it was calibrated in. After standing up, a vasovagal episode or with blood loss or
 * dehydration the pulse races, the wrist arteries constrict and the wave narrows: the model reads
 * all of that as *high* pressure while the real pressure is normal or low. Those states are
 * recognised here and no number is given for them.
 */
object HemodynamicStateClassifier {
    /** Rhythm: coefficient of variation of intervals (sinus arrhythmia at rest stays well below). */
    const val IRREGULAR_CV = 0.15
    const val IRREGULAR_ECTOPICS = 3
    const val IRREGULAR_REJECTED = 0.4

    /** Pulse rate changing faster than this over the recording, bpm/s (≈ 10 bpm in 20 s). */
    const val TRANSIENT_HR_SLOPE = 0.5

    /** Pulse amplitude changing by more than this share between the first and last third. */
    const val TRANSIENT_AMPLITUDE = 0.35

    /** Pulse this much faster than at calibration, with the perfusion index below [COMPENSATORY_PI] of it. */
    const val COMPENSATORY_HR_RISE = 25.0
    const val COMPENSATORY_PI = 0.6

    /** More sensitive with POTS / orthostatic hypotension. */
    const val ORTHOSTATIC_HR_RISE = 15.0
    const val ORTHOSTATIC_PI = 0.75

    /** The forearm pointing more than this far from every calibration position, degrees. */
    const val ARM_ANGLE_DEG = 35.0

    fun assess(
        features: PpgFeatureVector,
        calibration: BpCalibration,
        context: MeasurementContext = MeasurementContext.NONE
    ): StateAssessment {
        val profile = calibration.profile
        if (features.version < PpgFeatureVector.STATE_VERSION) return StateAssessment.STEADY
        if (!profile.atrialFibrillation && irregular(features.ibiCv, features.ectopicCount, features.rejectedFraction)) {
            return StateAssessment(HemodynamicState.IRREGULAR, UnsteadyReason.IRREGULAR_RHYTHM)
        }
        val refHr = calibration.referenceHeartRate()
        val refPi = calibration.referencePerfusionIndex()
        val hrRise = features.heartRateBpm - refHr
        val piRatio = if (refPi > 0 && features.perfusionIndex > 0) features.perfusionIndex / refPi else null
        val changing = abs(features.hrSlopeBpmPerS) > TRANSIENT_HR_SLOPE
        val amplitudeChanging = abs(features.amplitudeTrend) > TRANSIENT_AMPLITUDE

        val (riseLimit, piLimit) = if (profile.orthostaticIntolerance) {
            ORTHOSTATIC_HR_RISE to ORTHOSTATIC_PI
        } else {
            COMPENSATORY_HR_RISE to
                COMPENSATORY_PI
        }
        val weakPulse = piRatio != null && piRatio < piLimit
        if (hrRise > riseLimit && weakPulse) {
            // A falling rate or a weak, changing pulse after a sudden rise is the compensating phase of a drop.
            val low = (changing || amplitudeChanging || features.hrSlopeBpmPerS < 0)
            return StateAssessment(HemodynamicState.COMPENSATORY, UnsteadyReason.COMPENSATORY_RESPONSE, low)
        }
        if (changing) {
            return StateAssessment(HemodynamicState.TRANSIENT, UnsteadyReason.HEART_RATE_CHANGING, weakPulse && hrRise > riseLimit / 2)
        }
        if (amplitudeChanging) return StateAssessment(HemodynamicState.TRANSIENT, UnsteadyReason.PULSE_AMPLITUDE_CHANGING)
        val angle = context.gravity?.let { calibration.armAngleDeg(it) }
        if (angle != null && angle > ARM_ANGLE_DEG) return StateAssessment(HemodynamicState.TRANSIENT, UnsteadyReason.ARM_POSITION)
        return StateAssessment.STEADY
    }

    /** Also used on a recording too irregular to give a feature vector at all. */
    fun irregular(ibiCv: Double, ectopicCount: Int, rejectedFraction: Double = 0.0) =
        ibiCv > IRREGULAR_CV || ectopicCount >= IRREGULAR_ECTOPICS || rejectedFraction > IRREGULAR_REJECTED

    /** Angle between two gravity vectors, degrees; null when either is degenerate. */
    fun angleDeg(a: List<Double>, b: List<Double>): Double? {
        if (a.size != 3 || b.size != 3) return null
        val na = sqrt(a.sumOf { it * it })
        val nb = sqrt(b.sumOf { it * it })
        if (na < 1.0 || nb < 1.0) return null
        val cos = (a.indices.sumOf { a[it] * b[it] } / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }
}
