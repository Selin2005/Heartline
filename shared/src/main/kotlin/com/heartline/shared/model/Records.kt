// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.model

import com.heartline.shared.bp.BpSafety
import kotlinx.serialization.Serializable

/** Kinds of measurement record the watch produces and the phone stores. */
@Serializable
enum class RecordKind(val metric: Metric) {
    ECG(Metric.ECG),
    BLOOD_PRESSURE(Metric.BLOOD_PRESSURE),
    SPO2(Metric.SPO2),
    SKIN_TEMPERATURE(Metric.SKIN_TEMPERATURE),
    BODY_COMPOSITION(Metric.BODY_COMPOSITION),
    STRESS(Metric.STRESS)
}

/** Per-kind summary values, sent alongside the raw waveform. */
@Serializable
sealed interface RecordSummary {
    @Serializable
    data class Ecg(
        val averageBpm: Int?,
        val result: EcgResult?,
        val leadOffRatio: Float,
        val symptoms: List<Symptom> = emptyList(),
        val metrics: EcgMetrics? = null
    ) : RecordSummary

    @Serializable
    data class BloodPressure(
        val systolic: Int,
        val diastolic: Int,
        val pulse: Int?,
        /** ± mmHg (about one standard deviation) for systolic; null for older readings. */
        val uncertainty: Int? = null,
        val algorithm: Int = 1,
        /** The pulse wave or pressure was outside what the calibration covered (algorithm 3+): an extrapolation. */
        val beyondCalibration: Boolean = false,
        /** A second reading within 10 minutes pointed the same way. */
        val confirmed: Boolean = false,
        /** When the phone's personal model refined the reading (algorithm 4): what the watch showed. */
        val watchSystolic: Int? = null,
        val watchDiastolic: Int? = null,
        /** Algorithm 5 only (no longer written): the reading was shown as a range ± [uncertainty]. */
        val rangeOnly: Boolean = false,
        /** Algorithm 6: the channels fused (BpChannel names, comma-separated), the body's state and the mode. */
        val channels: String? = null,
        val bodyState: String? = null,
        val mode: String? = null,
        /** Raw session log id (BpSessionLog) of this reading. */
        val sessionId: String? = null
    ) : RecordSummary {
        val safety get() = BpSafety.of(systolic, diastolic)
    }

    @Serializable
    data class Spo2(val percent: Int, val heartRate: Int?, val lowConfidence: Boolean) : RecordSummary

    @Serializable
    data class SkinTemperature(val skinCelsius: Float, val ambientCelsius: Float?) : RecordSummary

    @Serializable
    data class Stress(val score: Int, val rmssdMs: Double?, val skinConductanceMicroSiemens: Float? = null) : RecordSummary

    /**
     * Everything the watch's BIA reports (Samsung Health shows weight, skeletal muscle, fat mass,
     * body fat, BMI, body water and BMR). [weightKg]/[heightCm] are the values the measurement was
     * computed with; the newer fields default to null so records from older versions still load.
     */
    @Serializable
    data class BodyComposition(
        val bodyFatPercent: Float,
        val skeletalMuscleKg: Float?,
        val bodyWaterKg: Float?,
        val bmrKcal: Int?,
        val weightKg: Float? = null,
        val heightCm: Float? = null,
        val bodyFatMassKg: Float? = null,
        val skeletalMusclePercent: Float? = null,
        val fatFreeMassKg: Float? = null,
        val fatFreePercent: Float? = null,
        val impedanceOhm: Float? = null,
        val phaseAngleDeg: Float? = null
    ) : RecordSummary
}

/** Metadata for one measurement. [id] is a UUID and the idempotency key across sync. */
@Serializable
data class RecordMeta(
    val id: String,
    val kind: RecordKind,
    val startedAtMs: Long,
    val durationMs: Long,
    val sampleRateHz: Int,
    val sampleCount: Int,
    val summary: RecordSummary
)

/**
 * What else the recording shows, or why a usable one stayed inconclusive (algorithm 3). Wellness
 * wording only: these are observations, not diagnoses.
 */
@Serializable
enum class EcgNote {
    NONE,

    /** Some early (ectopic) beats in an otherwise regular rhythm. */
    EXTRA_BEATS,

    /** Many early beats (≥ 10 % or a repeating pattern): rhythm can't be judged reliably. */
    FREQUENT_EXTRA_BEATS,

    /** Irregular, but in a patterned way that isn't typical of AFib. */
    IRREGULAR_PATTERN,

    /** Regular, but no clear P wave was found. */
    NO_CLEAR_P_WAVE,

    /** Heart rate above 150 bpm: rhythm isn't classified. */
    RATE_ABOVE_150,

    /** Heart rate 100–120 with a regular rhythm. */
    FAST_REGULAR,

    /** One or more pauses longer than 2 s. */
    PAUSES,

    /** Looked irregular, but the recording was too noisy to call AFib. */
    NOISY_RHYTHM
}

/** Why a recording couldn't be classified (NONE for a usable one). */
@Serializable
enum class EcgPoorReason { NONE, TOO_SHORT, LEAD_OFF, MOTION, MUSCLE_NOISE, LOW_AMPLITUDE, TOO_FEW_BEATS }

/**
 * Everything measured about one ECG recording, shown on the watch, the phone and the PDF.
 * Durations are seconds of recorded (contact) signal; heart rates come from beat-to-beat intervals.
 */
@Serializable
data class EcgMetrics(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationSec: Float,
    val usableSec: Float,
    val noiseSec: Float,
    val motionSec: Float,
    val muscleNoiseSec: Float,
    val leadOffSec: Float,
    val averageBpm: Int?,
    val minBpm: Int?,
    val maxBpm: Int?,
    val beats: Int,
    val meanRrMs: Int?,
    val sdnnMs: Int?,
    val rmssdMs: Int?,
    val qualityScore: Int,
    val poorReason: EcgPoorReason,
    /** Seconds (0-based) of the recording marked as noise, for shading on strips. */
    val noisySeconds: List<Int> = emptyList(),
    val sampleRateHz: Float,
    val inverted: Boolean = false,
    val algorithm: Int = 2,
    /**
     * Median pulse arrival time (ECG R peak → wrist PPG upstroke), ms, when the watch delivered a
     * usable PPG channel alongside the ECG. Collected to validate PAT for blood pressure.
     */
    val pulseArrivalMs: Double? = null,
    val pulseArrivalBeats: Int? = null,
    /** Algorithm 3 findings: early beats (and how many had a different, wider shape), pauses, splices. */
    val ectopicBeats: Int = 0,
    val ventricularLikeBeats: Int = 0,
    val pauses: Int = 0,
    val longestPauseMs: Int? = null,
    val segments: Int = 1,
    val note: EcgNote = EcgNote.NONE,
    /** The phone's second opinion (app features + ECGFounder), when that model is installed. */
    val secondOpinion: EcgResult? = null,
    val secondOpinionAfProbability: Double? = null
) {
    val usablePercent: Int get() = if (durationSec <= 0f) 0 else (usableSec / durationSec * 100f).toInt()
}
