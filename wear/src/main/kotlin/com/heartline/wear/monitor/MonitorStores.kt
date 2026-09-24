package com.heartline.wear.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.irn.IrnState
import com.heartline.shared.sync.Protocol
import com.heartline.wear.MainActivity
import com.heartline.wear.R
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/** Monitoring settings received from the phone, persisted on the watch. */
class WatchSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val settings: StateFlow<MonitorSettings> = mutable.asStateFlow()

    private fun load(): MonitorSettings =
        prefs.getString(KEY, null)?.let { runCatching { Protocol.json.decodeFromString<MonitorSettings>(it) }.getOrNull() } ?: MonitorSettings()

    fun update(settings: MonitorSettings) {
        prefs.edit().putString(KEY, Protocol.json.encodeToString(settings)).apply()
        mutable.value = settings
    }

    /** Latest background heart rate, for the complication and tile. */
    var latestHeartRate: Int?
        get() = prefs.getInt(KEY_HR, -1).takeIf { it > 0 }
        set(value) = prefs.edit().putInt(KEY_HR, value ?: -1).apply()

    var irnState: IrnState
        get() = prefs.getString(KEY_IRN, null)?.let { runCatching { Protocol.json.decodeFromString<IrnState>(it) }.getOrNull() } ?: IrnState()
        set(value) = prefs.edit().putString(KEY_IRN, Protocol.json.encodeToString(value)).apply()

    private companion object {
        const val KEY = "settings"
        const val KEY_IRN = "irn_state"
        const val KEY_HR = "latest_hr"
    }
}

/** Watch notifications: the ongoing monitoring notice and heart alerts. */
class WatchNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, context.getString(R.string.channel_monitor), NotificationManager.IMPORTANCE_MIN),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun openApp() = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun monitoring() = NotificationCompat.Builder(context, CHANNEL_MONITOR)
        .setSmallIcon(R.drawable.ic_heart)
        .setContentTitle(context.getString(R.string.monitor_notification_title))
        .setContentText(context.getString(R.string.monitor_notification_text))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(openApp())
        .build()

    fun alert(alert: HealthAlert) {
        val (title, text) = when (alert.kind) {
            AlertKind.IRREGULAR_RHYTHM -> R.string.alert_irn_title to context.getString(R.string.alert_irn_text)
            AlertKind.HIGH_HEART_RATE -> R.string.alert_high_title to context.getString(R.string.alert_high_text, alert.bpm ?: 0)
            AlertKind.LOW_HEART_RATE -> R.string.alert_low_title to context.getString(R.string.alert_low_text, alert.bpm ?: 0)
        }
        manager.notify(
            alert.id.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle(context.getString(title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build(),
        )
    }

    /** The phone asked for a calibration round; the watch can't open UI from the background. */
    fun calibrationRequest(round: Int) {
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_BP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            CALIBRATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle(context.getString(R.string.bp_calibration_round, round))
                .setContentText(context.getString(R.string.bp_calibrate_notification, round))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    /** The phone asked to start a measurement here ("Open on watch"). */
    fun openRequest(route: String) {
        val open = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            OPEN_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle(context.getString(R.string.open_request_title))
                .setContentText(context.getString(R.string.open_request_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    companion object {
        const val OPEN_ID = 3
        const val CALIBRATION_ID = 2
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_ALERTS = "alerts"
        const val MONITOR_ID = 1
    }
}

class WatchMonitorOutput(
    private val store: WatchRecordStore,
    private val settings: WatchSettingsStore,
    private val notifier: WatchNotifier,
    private val sync: SyncScheduler,
) : MonitorOutput {
    override suspend fun loadIrnState() = settings.irnState

    override suspend fun saveIrnState(state: IrnState) {
        settings.irnState = state
    }

    override suspend fun enqueueBatch(batch: HrBatch) {
        store.enqueueMessage(batch.id, Protocol.HR_BATCH, Protocol.json.encodeToString(batch).encodeToByteArray())
        sync.schedule()
    }

    override suspend fun enqueueAlert(alert: HealthAlert) {
        store.enqueueMessage(alert.id, Protocol.ALERT, Protocol.json.encodeToString(alert).encodeToByteArray())
        sync.schedule()
    }

    override fun notify(alert: HealthAlert) = notifier.alert(alert)

    override fun latestMinute(bpm: Int) {
        settings.latestHeartRate = bpm
    }
}
