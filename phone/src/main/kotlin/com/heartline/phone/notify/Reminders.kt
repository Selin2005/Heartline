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

    fun sync(context: Context, settings: MonitorSettings) {
        val work = WorkManager.getInstance(context)
        if (settings.calibrationReminder) {
            work.enqueueUniquePeriodicWork(CALIBRATION, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<CalibrationReminderWorker>(1, TimeUnit.DAYS).build())
        } else {
            work.cancelUniqueWork(CALIBRATION)
        }
        if (settings.dailyReminder) scheduleDaily(context, settings.dailyReminderMinute) else work.cancelUniqueWork(DAILY)
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
