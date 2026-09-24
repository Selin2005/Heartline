package com.heartline.shared.hr

import kotlinx.serialization.Serializable

/** One reading from HEART_RATE_CONTINUOUS (about 1 Hz). */
data class HrSample(val tsMs: Long, val bpm: Int, val ibiMs: List<Int>, val onBody: Boolean = true, val moving: Boolean = false)

/** Per-minute summary sent to the phone for trends. */
@Serializable
data class HrMinute(
    val minuteStartMs: Long,
    val avgBpm: Int,
    val minBpm: Int,
    val maxBpm: Int,
    val rmssdMs: Double? = null,
    val resting: Boolean = true
)

@Serializable
data class HrBatch(val id: String, val minutes: List<HrMinute>)

@Serializable
enum class AlertKind { IRREGULAR_RHYTHM, HIGH_HEART_RATE, LOW_HEART_RATE }

@Serializable
data class HealthAlert(
    val id: String,
    val kind: AlertKind,
    val atMs: Long,
    /** Heart rate that triggered a high/low alert, or mean rate during the irregular windows. */
    val bpm: Int?,
    /** Start times of the irregular tachogram windows that led to an IRN alert. */
    val windowStartsMs: List<Long> = emptyList()
)

/** Monitoring preferences edited on the phone and applied on the watch. */
@Serializable
data class MonitorSettings(
    val irregularRhythmEnabled: Boolean = true,
    val heartRateAlertsEnabled: Boolean = true,
    val highBpm: Int = 120,
    val lowBpm: Int = 40,
    /** All-day heart rate for trends (passive, no notification). */
    val backgroundHeartRate: Boolean = true,
    /** Minutes between irregular-rhythm checks (each check listens for about a minute). */
    val irnIntervalMinutes: Int = 15
) {
    /** Passive heart rate feeds trends and the high/low alerts. */
    val passiveHeartRate: Boolean get() = backgroundHeartRate || heartRateAlertsEnabled
}
