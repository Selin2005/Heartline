package com.heartline.shared.model

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
    data class BloodPressure(val systolic: Int, val diastolic: Int, val pulse: Int?) : RecordSummary

    @Serializable
    data class Spo2(val percent: Int, val heartRate: Int?, val lowConfidence: Boolean) : RecordSummary

    @Serializable
    data class SkinTemperature(val skinCelsius: Float, val ambientCelsius: Float?) : RecordSummary

    @Serializable
    data class Stress(val score: Int, val rmssdMs: Double?, val skinConductanceMicroSiemens: Float? = null) : RecordSummary

    @Serializable
    data class BodyComposition(val bodyFatPercent: Float, val skeletalMuscleKg: Float?, val bodyWaterKg: Float?, val bmrKcal: Int?) :
        RecordSummary
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
    val algorithm: Int = 2
) {
    val usablePercent: Int get() = if (durationSec <= 0f) 0 else (usableSec / durationSec * 100f).toInt()
}
