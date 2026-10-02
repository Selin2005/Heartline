// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.heartline.datalayer.DeepLinks
import com.heartline.shared.nav.EntryLinks
import com.heartline.shared.nav.EntrySource
import com.heartline.shared.sync.SetupTarget
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.heartline.phone.MainActivity
import com.heartline.phone.R
import com.heartline.shared.bp.BpSafety
import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HeartTrend
import com.heartline.shared.hr.VitalAlert
import com.heartline.shared.hr.HrContext
import com.heartline.shared.model.RecordSummary

/** Mirrors watch heart alerts as phone notifications. */
class PhoneNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun alert(alert: HealthAlert) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val (title, text) = context.getString(alertTitle(alert)) to alertText(context, alert)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_ALERTS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            alert.id.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    /** A confirmed very high or low blood-pressure reading: check with a cuff (wellness wording, not a diagnosis). */
    fun bpSafety(reading: RecordSummary.BloodPressure) {
        val high = reading.safety == BpSafety.VERY_HIGH
        simple(
            BP_SAFETY_ID,
            context.getString(if (high) R.string.alert_bp_high_title else R.string.alert_bp_low_title, reading.systolic, reading.diastolic),
            context.getString(if (high) R.string.bp_safety_high else R.string.bp_safety_low),
            "blood_pressure",
            CHANNEL_ALERTS,
        )
    }

    /** Fallback for a watch "open on phone" request when the direct launch didn't happen. */
    fun setupRequest(target: SetupTarget) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val (title, text) = when (target) {
            SetupTarget.PROFILE -> R.string.setup_notif_profile_title to R.string.setup_notif_profile_text
            SetupTarget.BP_CALIBRATION -> R.string.setup_notif_calibration_title to R.string.setup_notif_calibration_text
            SetupTarget.DEV_MODE_HELP -> R.string.setup_notif_devmode_title to R.string.setup_notif_devmode_text
            SetupTarget.HOME -> R.string.setup_notif_home_title to R.string.setup_notif_home_text
        }
        val open = PendingIntent.getActivity(
            context,
            target.ordinal + 100,
            Intent(Intent.ACTION_VIEW, Uri.parse(DeepLinks.phone(EntryLinks.tag(target.phoneRoute, EntrySource.NOTIFICATION))), context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            SETUP_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle(context.getString(title))
                .setContentText(context.getString(text))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build(),
        )
    }

    fun calibrationReminder(daysLeft: Int) = simple(
        CALIBRATION_ID,
        context.getString(R.string.reminder_calibration_title),
        context.resources.getQuantityString(R.plurals.reminder_calibration_text, daysLeft, daysLeft),
        SetupTarget.BP_CALIBRATION.phoneRoute,
    )

    /** The Friday summary: [title] and one line of the week in numbers; opens Home. */
    fun updateAvailable(version: String, beta: Boolean) = simple(
        UPDATE_ID,
        context.getString(if (beta) R.string.update_notif_beta_title else R.string.update_notif_title, version),
        context.getString(R.string.update_notif_text),
        UPDATES_ROUTE,
    )

    fun weeklySummary(title: String, text: String) = simple(WEEKLY_ID, title, text, SetupTarget.HOME.phoneRoute)

    fun dailyReminder() = simple(
        DAILY_ID,
        context.getString(R.string.reminder_daily_title),
        context.getString(R.string.reminder_daily_text),
        SetupTarget.HOME.phoneRoute,
    )

    private fun simple(id: Int, title: String, text: String, route: String, channel: String = CHANNEL_REMINDERS) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(Intent.ACTION_VIEW, Uri.parse(DeepLinks.phone(EntryLinks.tag(route, EntrySource.NOTIFICATION))), context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            id,
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    companion object {
        fun alertTitle(alert: HealthAlert) = when (alert.vital) {
            VitalAlert.SPO2_LOW -> R.string.alert_spo2_low_title
            VitalAlert.SPO2_NIGHTS -> R.string.alert_spo2_nights_title
            VitalAlert.TEMPERATURE -> R.string.alert_temp_title
            VitalAlert.COMBINED -> R.string.alert_combined_title
            null -> trendTitle(alert)
        }

        private fun trendTitle(alert: HealthAlert) = when (alert.trend) {
            HeartTrend.ELEVATED_RESTING -> R.string.alert_trend_title
            HeartTrend.HIGH_NORMAL -> R.string.alert_high_normal_title
            null -> alertTitle(alert.kind)
        }

        fun alertTitle(kind: AlertKind) = when (kind) {
            AlertKind.IRREGULAR_RHYTHM -> R.string.alert_irn_title
            AlertKind.HIGH_HEART_RATE -> R.string.alert_high_title
            AlertKind.LOW_HEART_RATE -> R.string.alert_low_title
        }

        /** The alert's text, naming the limit crossed and what the wearer was doing when known. */
        fun alertText(context: Context, alert: HealthAlert): String {
            val bpm = alert.bpm ?: 0
            val limit = alert.threshold
            val normal = alert.normal
            fun percent(n: Int) = if (n <= 0) 0 else kotlin.math.abs(bpm - n) * 100 / n
            val change = alert.value?.let { "%.1f".format(it) } ?: ""
            when (alert.vital) {
                VitalAlert.SPO2_LOW -> return context.getString(R.string.alert_spo2_low_text, bpm)
                VitalAlert.SPO2_NIGHTS -> return context.getString(R.string.alert_spo2_nights_text)
                VitalAlert.TEMPERATURE -> return context.getString(R.string.alert_temp_text, change)
                VitalAlert.COMBINED -> return context.getString(R.string.alert_combined_text, change)
                null -> Unit
            }
            when (alert.trend) {
                HeartTrend.ELEVATED_RESTING -> return context.getString(R.string.alert_trend_text, bpm, normal ?: 0)
                HeartTrend.HIGH_NORMAL -> return context.getString(R.string.alert_high_normal_text, bpm)
                null -> Unit
            }
            return when (alert.kind) {
                AlertKind.IRREGULAR_RHYTHM -> context.getString(R.string.alert_irn_text)
                AlertKind.HIGH_HEART_RATE -> when {
                    limit == null -> context.getString(R.string.alert_high_text, bpm)
                    alert.context == HrContext.EXERCISE || alert.context == HrContext.ACTIVE -> context.getString(R.string.alert_high_exercise_text, limit, bpm)
                    normal != null && alert.context == HrContext.SLEEP -> context.getString(R.string.alert_high_sleep_normal_text, bpm, percent(normal), normal)
                    normal != null -> context.getString(R.string.alert_high_rest_normal_text, bpm, percent(normal), normal)
                    alert.context == HrContext.SLEEP -> context.getString(R.string.alert_high_sleep_text, limit, bpm)
                    else -> context.getString(R.string.alert_high_rest_text, limit, bpm)
                }
                AlertKind.LOW_HEART_RATE -> when {
                    limit == null -> context.getString(R.string.alert_low_text, bpm)
                    normal != null && alert.context == HrContext.SLEEP -> context.getString(R.string.alert_low_sleep_normal_text, bpm, percent(normal), normal)
                    normal != null -> context.getString(R.string.alert_low_rest_normal_text, bpm, percent(normal), normal)
                    alert.context == HrContext.SLEEP -> context.getString(R.string.alert_low_sleep_text, limit, bpm)
                    else -> context.getString(R.string.alert_low_rest_text, limit, bpm)
                }
            }
        }

        const val CALIBRATION_ID = 7_002
        const val DAILY_ID = 7_003
        const val BP_SAFETY_ID = 7_004
        const val WEEKLY_ID = 7_005
        const val UPDATE_ID = 7_006
        const val UPDATES_ROUTE = "updates"
        const val CHANNEL_REMINDERS = "reminders"
        const val SETUP_ID = 7_001
        const val CHANNEL_ALERTS = "alerts"
        const val EXTRA_OPEN_ALERTS = "open_alerts"
    }
}
