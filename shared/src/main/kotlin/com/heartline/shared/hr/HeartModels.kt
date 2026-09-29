// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

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
    val irnIntervalMinutes: Int = 15,
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
    val passiveHeartRate: Boolean get() = backgroundHeartRate || heartRateAlertsEnabled

    /** Last writer wins: a copy changed later on either device replaces an older one. */
    fun isNewerThan(other: MonitorSettings) = updatedAtMs > other.updatedAtMs

    companion object {
        val IRN_INTERVALS = listOf(15, 30, 60)
        val HIGH_BPM_RANGE = 100..150
        val LOW_BPM_RANGE = 35..50
    }
}
