package com.heartline.phone.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.heartline.phone.MainActivity
import com.heartline.phone.R
import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert

/** Mirrors watch heart alerts as phone notifications. */
class PhoneNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun alert(alert: HealthAlert) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val (title, text) = when (alert.kind) {
            AlertKind.IRREGULAR_RHYTHM -> context.getString(R.string.alert_irn_title) to context.getString(R.string.alert_irn_text)
            AlertKind.HIGH_HEART_RATE -> context.getString(R.string.alert_high_title) to context.getString(R.string.alert_high_text, alert.bpm ?: 0)
            AlertKind.LOW_HEART_RATE -> context.getString(R.string.alert_low_title) to context.getString(R.string.alert_low_text, alert.bpm ?: 0)
        }
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

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        const val EXTRA_OPEN_ALERTS = "open_alerts"
    }
}
