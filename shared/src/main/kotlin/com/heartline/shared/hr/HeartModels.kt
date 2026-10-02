// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.hr

import kotlinx.serialization.Serializable

/**
 * One reading from HEART_RATE_CONTINUOUS (about 1 Hz).
 * [reliable]: the tracker reported a good reading (status 1); off-wrist, weak-signal and
 * still-searching readings are not, and are never used for rhythm checks or trends.
 * [rejectedIbis]: intervals in this reading that the tracker itself flagged as unreliable.
 */
data class HrSample(
    val tsMs: Long,
    val bpm: Int,
    val ibiMs: List<Int>,
    val onBody: Boolean = true,
    val moving: Boolean = false,
    val reliable: Boolean = true,
    val rejectedIbis: Int = 0
)

/** What the wearer was doing during a minute, so each is judged by its own rules. */
@Serializable
enum class HrContext {
    /** Awake and still. */
    REST,

    /** Walking or moving about without a workout. */
    ACTIVE,
    EXERCISE,
    SLEEP
}

/** Per-minute summary sent to the phone for trends. */
@Serializable
data class HrMinute(
    val minuteStartMs: Long,
    val avgBpm: Int,
    val minBpm: Int,
    val maxBpm: Int,
    val rmssdMs: Double? = null,
    /** Awake and still; kept for older phone versions, which only know this flag. */
    val resting: Boolean = true,
    val activity: HrContext = if (resting) HrContext.REST else HrContext.ACTIVE
)

/** [limits]: the personal limits the watch uses now, so the phone can show them. */
@Serializable
data class HrBatch(val id: String, val minutes: List<HrMinute>, val limits: HeartLimits? = null)

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
    val windowStartsMs: List<Long> = emptyList(),
    /** The limit that was crossed (high/low alerts). */
    val threshold: Int? = null,
    /** What the wearer was doing (high/low alerts). */
    val context: HrContext? = null,
    /** The wearer's usual heart rate in that context when the alert fired (personal limits). */
    val normal: Int? = null,
    /** A notice about the resting heart rate over several days, not a single episode. Sent as a high heart rate to older phones. */
    val trend: HeartTrend? = null
)

@Serializable
enum class HeartTrend {
    /** Resting heart rate in sleep raised over several nights. */
    ELEVATED_RESTING,

    /** The usual resting heart rate itself has been above 100 bpm for a week. */
    HIGH_NORMAL
}

/** Rhythm-check thresholds: standard suits the wrist sensor; high is the earlier, looser profile. */
enum class IrnSensitivity { STANDARD, HIGH }

/** One choice for every heart notification: how far from the wearer's normal counts as unusual. */
@Serializable
enum class AlertSensitivity { LOW, STANDARD, HIGH }

/** Monitoring preferences edited on the phone and applied on the watch. */
@Serializable
data class MonitorSettings(
    val irregularRhythmEnabled: Boolean = true,
    val heartRateAlertsEnabled: Boolean = true,
    /** Fixed limits from older versions; the personal limits replace them (HeartBaseline). */
    val highBpm: Int = 120,
    val lowBpm: Int = 40,
    /** All-day heart rate for trends (passive, no notification). */
    val backgroundHeartRate: Boolean = true,
    /** Unused since the single switch (checks run every 15 minutes). Kept so older versions still decode. */
    val irnIntervalMinutes: Int = 15,
    /**
     * The one switch for heart monitoring: all-day heart rate, sleep and exercise awareness, high
     * and low heart rate, irregular rhythm and resting-trend notifications. Settings from older
     * versions start on unless every part was off.
     */
    val heartMonitoring: Boolean = backgroundHeartRate || heartRateAlertsEnabled || irregularRhythmEnabled,
    /** How far from the wearer's normal a heart rate must be to notify (see HeartBaseline). */
    val alertSensitivity: AlertSensitivity = AlertSensitivity.STANDARD,
    /** Remind 3 days before the BP calibration expires. */
    val calibrationReminder: Boolean = true,
    /** Daily reminder to take a measurement, at [dailyReminderMinute] (minutes after midnight). */
    val dailyReminder: Boolean = false,
    val dailyReminderMinute: Int = 9 * 60,
    /** Vibrate on results and contact loss on the watch. */
    val haptics: Boolean = true,
    /** Show the live wave while measuring on the watch (off: countdown only). */
    val liveWave: Boolean = true,
    val temperatureFahrenheit: Boolean = false,
    /** Greet the user by name in the watch app and on its tiles. */
    val showNameOnWatch: Boolean = true,
    /** Show the name on the phone's home-screen widgets too (off: they can be seen by anyone). */
    val showNameOnWidgets: Boolean = false,
    /** Confetti and light effects for birthdays, goals and good results on the watch. */
    val celebrations: Boolean = true,
    /** The daily check-ins (Today tile, launcher card, streak). */
    val dailyGoal: List<com.heartline.shared.model.Metric> = com.heartline.shared.profile.DailyGoal.DEFAULT,
    /** Accent colour of the watch app and tiles. */
    val accent: com.heartline.shared.design.Accent = com.heartline.shared.design.Accent.BLUE,
    /** A summary of the week on Friday evening (phone notification). */
    val weeklySummary: Boolean = true,
    /** Keep a diagnostic log file on each device (decided on the phone, see DiagnosticsPolicy). */
    val diagnosticLogs: Boolean = false,
    /** Unused: raw sensor values are always in the log now. Kept so older app versions still decode. */
    val detailedLogsUntilMs: Long = 0,
    /** When these settings were last changed (either device); the newer copy wins. */
    val updatedAtMs: Long = 0
) {
    /** Passive heart rate feeds trends and the high/low alerts. */
    val passiveHeartRate: Boolean get() = heartMonitoring

    /** Rhythm-check thresholds follow the alert sensitivity: high uses the looser rule. */
    val irnSensitivity: IrnSensitivity get() = if (alertSensitivity ==
        AlertSensitivity.HIGH
    ) {
        IrnSensitivity.HIGH
    } else {
        IrnSensitivity.STANDARD
    }

    /** Turns every part of heart monitoring on or off together (older app versions read the parts). */
    fun withMonitoring(on: Boolean) =
        copy(heartMonitoring = on, backgroundHeartRate = on, heartRateAlertsEnabled = on, irregularRhythmEnabled = on)

    /** The parts follow the single switch, whatever older versions left in them. */
    fun normalized() = withMonitoring(heartMonitoring)

    /** Last writer wins: a copy changed later on either device replaces an older one. */
    fun isNewerThan(other: MonitorSettings) = updatedAtMs > other.updatedAtMs

    companion object {
        /** Minutes between irregular-rhythm checks. */
        const val IRN_INTERVAL_MINUTES = 15
    }
}

/** Age-predicted maximum heart rate (Tanaka et al., 2001: 208 − 0.7 × age). */
object MaxHr {
    const val UNKNOWN_AGE_MAX = 190

    fun predicted(age: Int?): Int = age?.takeIf { it in 10..110 }?.let { (208 - 0.7 * it).toInt() } ?: UNKNOWN_AGE_MAX

    /** Heart-rate zones as lower bounds in bpm: 50, 60, 70, 80 and 90 % of [max]. */
    fun zones(max: Int): List<Int> = listOf(50, 60, 70, 80, 90).map { max * it / 100 }
}

/**
 * What the background heart monitor remembers between wake-ups (the process may be new each time):
 * the recent minutes the alert rules look back over, and when each alert last fired.
 */
@Serializable
data class MonitorState(
    val minutes: List<HrMinute> = emptyList(),
    val lastAlerts: Map<String, Long> = emptyMap(),
    /** Daily histograms for the personal baseline. */
    val history: HeartHistory = HeartHistory()
)
