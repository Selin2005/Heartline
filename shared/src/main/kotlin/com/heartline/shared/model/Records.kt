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
    data class Ecg(val averageBpm: Int?, val result: EcgResult?, val leadOffRatio: Float, val symptoms: List<Symptom> = emptyList()) :
        RecordSummary

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
