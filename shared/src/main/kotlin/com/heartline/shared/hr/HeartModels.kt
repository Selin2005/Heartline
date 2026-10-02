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

/**
 * [limits]: the personal limits the watch uses now, so the phone can show them. [spo2] and
 * [skinTemp]: background readings; [vitals]: their normals and limits (older phones ignore these).
 */
@Serializable
data class HrBatch(
    val id: String,
    val minutes: List<HrMinute>,
    val limits: HeartLimits? = null,
    val spo2: List<com.heartline.shared.vitals.Spo2Sample> = emptyList(),
    val skinTemp: List<com.heartline.shared.vitals.TempSample> = emptyList(),
    val vitals: com.heartline.shared.vitals.VitalsLimits? = null
)

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
    val trend: HeartTrend? = null,
    /**
     * A blood-oxygen, skin-temperature or combined notice. Older phones don't know these, so they
     * travel as a low (oxygen) or high (temperature) heart-rate alert with this field set.
     */
    val vital: VitalAlert? = null,
    /** The value behind a vital notice: SpO2 in %, or the temperature change in °C. */
    val value: Float? = null
)

@Serializable
enum class VitalAlert {
    /** Two readings in a row (the second a re-check) at or below the oxygen limit. */
    SPO2_LOW,

    /** Oxygen in sleep lower than the wearer's usual over several nights. */
    SPO2_NIGHTS,

    /** Skin temperature in sleep well above the wearer's usual, two nights in a row. */
    TEMPERATURE,

    /** A warmer night together with a raised heart rate in sleep. */
    COMBINED
}

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
    /**
     * All-day heart rate: recorded in the background for trends, with no notification. Its own
     * switch: it keeps recording when heart monitoring is off. Monitoring needs it.
     */
    val backgroundHeartRate: Boolean = true,
    /** Unused since the single switch (checks run every 15 minutes). Kept so older versions still decode. */
    val irnIntervalMinutes: Int = 15,
    /**
     * The one switch for heart monitoring: high and low heart rate, irregular rhythm and
     * resting-trend notifications. All-day heart rate ([backgroundHeartRate]) has its own switch
     * and keeps recording when this is off. Settings from older versions start on unless both
     * notification parts were off.
     */
    val heartMonitoring: Boolean = heartRateAlertsEnabled || irregularRhythmEnabled,
    /** How far from the wearer's normal a heart rate must be to notify (see HeartBaseline). */
    val alertSensitivity: AlertSensitivity = AlertSensitivity.STANDARD,
    /**
     * The parts of health monitoring, each with its own switch under [heartMonitoring] (the master):
     * heart (high/low, irregular rhythm, resting trend), blood oxygen (hourly SpO2 and its
     * notifications) and skin temperature (readings in sleep and by day, and its notification).
     * They keep their state while the master is off.
     */
    val heartAlerts: Boolean = true,
    val spo2Monitoring: Boolean = true,
    val skinTempMonitoring: Boolean = true,
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
    /** Passive heart rate: recorded when all-day heart rate is on (monitoring turns it on too). */
    val passiveHeartRate: Boolean get() = backgroundHeartRate || heartMonitoring

    /** Rhythm-check thresholds follow the alert sensitivity: high uses the looser rule. */
    val irnSensitivity: IrnSensitivity get() = if (alertSensitivity ==
        AlertSensitivity.HIGH
    ) {
        IrnSensitivity.HIGH
    } else {
        IrnSensitivity.STANDARD
    }

    /**
     * Health monitoring (the master switch) on or off. The heart part's old switches follow it, so
     * older app versions do too. Turning it on with the heart part on also turns all-day heart rate
     * on, which that part needs; turning it off leaves recording as it is.
     */
    fun withMonitoring(on: Boolean) = copy(
        heartMonitoring = on,
        heartRateAlertsEnabled = on && heartAlerts,
        irregularRhythmEnabled = on && heartAlerts,
        backgroundHeartRate = backgroundHeartRate || (on && heartAlerts)
    )

    /** The heart part on or off (under the master switch). */
    fun withHeartAlerts(on: Boolean) = copy(heartAlerts = on).withMonitoring(heartMonitoring)

    /** All-day heart rate on or off. The heart part needs it, so off turns the heart part off too. */
    fun withAllDayHeartRate(on: Boolean) =
        if (on) copy(backgroundHeartRate = true) else withHeartAlerts(false).copy(backgroundHeartRate = false)

    /** Heart notifications and checks are running: the master and the heart part are on. */
    val heartActive: Boolean get() = heartMonitoring && heartAlerts

    val spo2Active: Boolean get() = heartMonitoring && spo2Monitoring

    val skinTempActive: Boolean get() = heartMonitoring && skinTempMonitoring

    /** The parts follow the switches, whatever older versions left in them. */
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
