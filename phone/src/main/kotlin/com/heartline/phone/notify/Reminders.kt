// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.SettingsRepository
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.hr.MonitorSettings
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Phone reminders driven by the settings: calibration expiry (daily check) and a daily measurement nudge. */
object Reminders {
    private const val CALIBRATION = "heartline-calibration-reminder"
    private const val DAILY = "heartline-daily-reminder"
    private const val WEEKLY = "heartline-weekly-summary"

    /** Friday at 19:00, local time. */
    val WEEKLY_AT: LocalTime = LocalTime.of(19, 0)

    fun sync(context: Context, settings: MonitorSettings) {
        val work = WorkManager.getInstance(context)
        if (settings.calibrationReminder) {
            work.enqueueUniquePeriodicWork(CALIBRATION, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<CalibrationReminderWorker>(1, TimeUnit.DAYS).build())
        } else {
            work.cancelUniqueWork(CALIBRATION)
        }
        if (settings.dailyReminder) scheduleDaily(context, settings.dailyReminderMinute) else work.cancelUniqueWork(DAILY)
        if (settings.weeklySummary) scheduleWeekly(context) else work.cancelUniqueWork(WEEKLY)
    }

    /** One-shot at the next Friday evening; the worker re-arms itself for the week after. */
    fun scheduleWeekly(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WEEKLY,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WeeklySummaryWorker>().setInitialDelay(delayUntilWeekly(now).toMillis(), TimeUnit.MILLISECONDS).build(),
        )
    }

    fun delayUntilWeekly(now: LocalDateTime): Duration {
        var next = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.FRIDAY)).atTime(WEEKLY_AT)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        return Duration.between(now, next)
    }

    /** "Your week, Sara" and "12 measurements · check-ins done on 5 of 7 days · blood pressure 119/77 (−2 vs last week)". */
    fun weeklyText(context: Context, week: com.heartline.shared.profile.WeekSummary, name: String?): Pair<String, String> {
        val r = context.resources
        val title = name?.let { r.getString(com.heartline.phone.R.string.weekly_title, it) } ?: r.getString(com.heartline.phone.R.string.weekly_title_plain)
        if (week.measurements == 0) return title to r.getString(com.heartline.phone.R.string.weekly_nothing)
        val parts = listOfNotNull(
            r.getString(com.heartline.phone.R.string.weekly_measurements, week.measurements),
            week.goalDays.takeIf { it > 0 }?.let { r.getString(com.heartline.phone.R.string.weekly_goal_days, it) },
            week.streak.takeIf { it >= 2 }?.let { r.getString(com.heartline.phone.R.string.weekly_streak, it) },
            week.bpAverage?.let { (s, d) ->
                r.getString(com.heartline.phone.R.string.weekly_bp, s, d) + (week.bpChange?.takeIf { it != 0 }?.let { " " + r.getString(com.heartline.phone.R.string.weekly_bp_change, it) } ?: "")
            },
        )
        return title to parts.joinToString(" · ")
    }

    /** One-shot at the next occurrence of [minuteOfDay]; the worker re-arms itself for the next day. */
    fun scheduleDaily(context: Context, minuteOfDay: Int, now: LocalDateTime = LocalDateTime.now()) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            DAILY,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DailyReminderWorker>().setInitialDelay(delayUntil(minuteOfDay, now).toMillis(), TimeUnit.MILLISECONDS).build(),
        )
    }

    fun delayUntil(minuteOfDay: Int, now: LocalDateTime): Duration {
        var next = now.toLocalDate().atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    /** Remind when 3 or fewer days are left (not after expiry: then the watch asks directly). */
    fun calibrationDue(calibration: BpCalibration?, nowMs: Long): Boolean =
        calibration != null && calibration.isValid(nowMs) && calibration.daysLeft(nowMs) <= 3
}

class CalibrationReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val bp: BpRepository by inject()
    private val notifier: PhoneNotifier by inject()

    override suspend fun doWork(): Result {
        val calibration = bp.calibration.first()
        val now = System.currentTimeMillis()
        if (Reminders.calibrationDue(calibration, now)) notifier.calibrationReminder(calibration!!.daysLeft(now))
        return Result.success()
    }
}

class DailyReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val settings: SettingsRepository by inject()
    private val notifier: PhoneNotifier by inject()

    override suspend fun doWork(): Result {
        val current = settings.current()
        if (current.dailyReminder) {
            notifier.dailyReminder()
            Reminders.scheduleDaily(applicationContext, current.dailyReminderMinute)
        }
        Log.i("Heartline/Reminder", "daily reminder fired (enabled=${current.dailyReminder})")
        return Result.success()
    }
}

class WeeklySummaryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val settings: SettingsRepository by inject()
    private val records: com.heartline.phone.data.RecordRepository by inject()
    private val profiles: com.heartline.phone.data.ProfileRepository by inject()
    private val notifier: PhoneNotifier by inject()

    override suspend fun doWork(): Result {
        val current = settings.current()
        if (!current.weeklySummary) return Result.success()
        val metas = com.heartline.shared.model.RecordKind.entries.flatMap { kind ->
            records.observe(kind).first().map { com.heartline.shared.model.RecordMeta(it.id, kind, it.entity.startedAtMs, 0, 0, 0, it.summary) }
        }
        val zone = java.time.ZoneId.systemDefault()
        val week = com.heartline.shared.profile.WeekSummary.of(metas, current.dailyGoal, java.time.LocalDate.now(zone), zone)
        val name = profiles.profile.first()?.displayName?.takeIf { it.isNotBlank() }
        val (title, text) = Reminders.weeklyText(applicationContext, week, name)
        notifier.weeklySummary(title, text)
        Reminders.scheduleWeekly(applicationContext, LocalDateTime.now().plusMinutes(1))
        Log.i("Heartline/Reminder", "weekly summary: ${week.measurements} measurements")
        return Result.success()
    }
}
