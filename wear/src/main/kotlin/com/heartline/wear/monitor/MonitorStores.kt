// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.heartline.datalayer.DeepLinks
import com.heartline.shared.nav.EntryLinks
import com.heartline.shared.nav.EntrySource
import androidx.core.app.NotificationCompat
import com.heartline.shared.hr.AlertKind
import com.heartline.datalayer.diag.HLog
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.describe
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.ActivityChange
import com.heartline.shared.hr.ActivityTimeline
import com.heartline.shared.hr.HeartLimits
import com.heartline.shared.hr.HeartTrend
import com.heartline.shared.hr.VitalAlert
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.hr.MonitorState
import com.heartline.shared.hr.StepSpan
import com.heartline.shared.hr.WatchActivity
import com.heartline.shared.irn.IrnState
import com.heartline.shared.stress.StressHistory
import com.heartline.shared.vitals.VitalsHistory
import com.heartline.shared.model.EcgResult
import com.heartline.shared.sync.Protocol
import com.heartline.wear.MainActivity
import com.heartline.wear.R
import com.heartline.wear.data.WatchRecordStore
import com.heartline.wear.sensor.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/** Settings shared with the phone (newer copy wins), persisted on the watch. */
class WatchSettingsStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val settings: StateFlow<MonitorSettings> = mutable.asStateFlow()

    private fun load(): MonitorSettings =
        prefs.getString(KEY, null)?.let { runCatching { Protocol.json.decodeFromString<MonitorSettings>(it) }.getOrNull() } ?: MonitorSettings()

    fun update(settings: MonitorSettings) {
        prefs.edit().putString(KEY, Protocol.json.encodeToString(settings)).apply()
        mutable.value = settings
    }

    /** A copy from the phone: kept only if newer. @return true when it replaced ours. */
    fun offer(incoming: MonitorSettings): Boolean {
        if (!incoming.isNewerThan(mutable.value)) return false
        update(incoming)
        return true
    }

    /** A change made on the watch, stamped now so it wins over the phone's older copy. */
    fun change(transform: (MonitorSettings) -> MonitorSettings): MonitorSettings =
        transform(mutable.value).copy(updatedAtMs = now()).also(::update)

    /** Latest background heart rate, for the complication and tile. */
    var latestHeartRate: Int?
        get() = prefs.getInt(KEY_HR, -1).takeIf { it > 0 }
        set(value) = prefs.edit().putInt(KEY_HR, value ?: -1).apply()

    private val heartState = MutableStateFlow(heartToday())

    /** Today's heart rate (latest, lowest, highest) for tiles and complications; changes once a minute. */
    val heart: StateFlow<HeartToday?> = heartState.asStateFlow()

    fun recordHeartRate(bpm: Int, day: Long = localDay(), hour: Int = localHour()) {
        val sameDay = prefs.getLong(KEY_HR_DAY, -1) == day
        val today = heartToday()?.takeIf { sameDay }
        val hours = HeartHours.record(if (sameDay) HeartHours.decode(prefs.getString(KEY_HR_HOURS, null)) else HeartHours.empty(), hour, bpm)
        prefs.edit()
            .putInt(KEY_HR, bpm)
            .putLong(KEY_HR_DAY, day)
            .putInt(KEY_HR_MIN, minOf(today?.min ?: bpm, bpm))
            .putInt(KEY_HR_MAX, maxOf(today?.max ?: bpm, bpm))
            .putString(KEY_HR_HOURS, HeartHours.encode(hours))
            .apply()
        heartState.value = heartToday()
    }

    private fun localHour() = java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.systemDefault()).hour

    private fun localDay() = java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()

    fun heartToday(): HeartToday? {
        val bpm = latestHeartRate ?: return null
        val sameDay = prefs.getLong(KEY_HR_DAY, -1) == localDay()
        return HeartToday(
            bpm,
            prefs.getInt(KEY_HR_MIN, -1).takeIf { sameDay && it > 0 },
            prefs.getInt(KEY_HR_MAX, -1).takeIf { sameDay && it > 0 },
            if (sameDay) HeartHours.decode(prefs.getString(KEY_HR_HOURS, null)) else HeartHours.empty(),
        )
    }

    var irnState: IrnState
        get() = prefs.getString(KEY_IRN, null)?.let { runCatching { Protocol.json.decodeFromString<IrnState>(it) }.getOrNull() } ?: IrnState()
        set(value) = prefs.edit().putString(KEY_IRN, Protocol.json.encodeToString(value)).apply()

    /** Recent minutes and alert times of the background monitor (survives a new process). */
    var monitorState: MonitorState
        get() = prefs.getString(KEY_MONITOR, null)?.let { runCatching { Protocol.json.decodeFromString<MonitorState>(it) }.getOrNull() } ?: MonitorState()
        set(value) = prefs.edit().putString(KEY_MONITOR, Protocol.json.encodeToString(value)).apply()

    /** The personal limits in use (from the latest batch), for the settings screen. */
    var limits: HeartLimits?
        get() = prefs.getString(KEY_LIMITS, null)?.let { runCatching { Protocol.json.decodeFromString<HeartLimits>(it) }.getOrNull() }
        set(value) = prefs.edit().putString(KEY_LIMITS, value?.let { Protocol.json.encodeToString(it) }).apply()

    /** Sleep, workouts and steps as the watch recognised them. */
    var activity: ActivityTimeline
        get() = prefs.getString(KEY_ACTIVITY, null)?.let { runCatching { Protocol.json.decodeFromString<ActivityTimeline>(it) }.getOrNull() } ?: ActivityTimeline()
        private set(value) = prefs.edit().putString(KEY_ACTIVITY, Protocol.json.encodeToString(value)).apply()

    @Synchronized
    fun recordActivity(change: ActivityChange) {
        activity = activity.withChange(change, now())
    }

    @Synchronized
    fun recordSteps(spans: List<StepSpan>) {
        activity = activity.withSteps(spans, now())
    }

    /** What the wearer was doing in a minute, if the watch knows. */
    fun activityAt(minuteStartMs: Long): HrContext? = activity.contextAt(minuteStartMs)

    /** Blood-oxygen and skin-temperature history (daily values, notices). */
    var vitals: VitalsHistory
        get() = prefs.getString(KEY_VITALS, null)?.let { runCatching { Protocol.json.decodeFromString<VitalsHistory>(it) }.getOrNull() } ?: VitalsHistory()
        set(value) = prefs.edit().putString(KEY_VITALS, Protocol.json.encodeToString(value)).apply()

    /** Stress history from the rhythm windows (daily values, recent windows, notices). */
    var stress: StressHistory
        get() = prefs.getString(KEY_STRESS, null)?.let { runCatching { Protocol.json.decodeFromString<StressHistory>(it) }.getOrNull() } ?: StressHistory()
        set(value) = prefs.edit().putString(KEY_STRESS, Protocol.json.encodeToString(value)).apply()

    /** The latest background SpO2 and temperature readings, and the air temperature then. */
    var lastSpo2Ms: Long
        get() = prefs.getLong(KEY_LAST_SPO2, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_SPO2, value).apply()
    var lastTempMs: Long
        get() = prefs.getLong(KEY_LAST_TEMP, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_TEMP, value).apply()
    var lastAmbientC: Float?
        get() = prefs.getFloat(KEY_LAST_AMBIENT, Float.NaN).takeIf { !it.isNaN() }
        set(value) = prefs.edit().putFloat(KEY_LAST_AMBIENT, value ?: Float.NaN).apply()

    /** SpO2 tries put off this hour because the wearer was moving. */
    var spo2Retries: Int
        get() = prefs.getInt(KEY_SPO2_RETRIES, 0)
        set(value) = prefs.edit().putInt(KEY_SPO2_RETRIES, value).apply()

    /** Since when the wearer has been asleep (null when not asleep). */
    fun asleepSince(nowMs: Long): Long? = activity.changes.lastOrNull { it.atMs <= nowMs }?.takeIf { it.activity == WatchActivity.ASLEEP }?.atMs

    /** Time of the latest background heart rate (the watch was worn then). */
    var lastPassiveHeartRateMs: Long?
        get() = prefs.getLong(KEY_LAST_PASSIVE, -1).takeIf { it > 0 }
        set(value) = prefs.edit().putLong(KEY_LAST_PASSIVE, value ?: -1).apply()

    /** An ECG result: a regular one soon after a rhythm notification makes the next one more cautious. */
    @Synchronized
    fun noteEcg(result: EcgResult, atMs: Long) {
        val state = irnState
        val next = state.withEcg(result == EcgResult.SINUS_RHYTHM, atMs)
        if (next != state) irnState = next
    }

    private companion object {
        const val KEY = "settings"
        const val KEY_IRN = "irn_state"
        const val KEY_MONITOR = "monitor_state"
        const val KEY_ACTIVITY = "activity"
        const val KEY_LIMITS = "limits"
        const val KEY_VITALS = "vitals"
        const val KEY_STRESS = "stress"
        const val KEY_LAST_SPO2 = "last_spo2"
        const val KEY_LAST_TEMP = "last_temp"
        const val KEY_LAST_AMBIENT = "last_ambient"
        const val KEY_SPO2_RETRIES = "spo2_retries"
        const val KEY_LAST_PASSIVE = "last_passive_hr"
        const val KEY_HR = "latest_hr"
        const val KEY_HR_DAY = "hr_day"
        const val KEY_HR_MIN = "hr_min"
        const val KEY_HR_MAX = "hr_max"
        const val KEY_HR_HOURS = "hr_hours"
    }
}

/**
 * Latest background heart rate with today's range (null before the first reading today) and each
 * hour's lowest–highest ([hours], 24 entries, null for hours without readings).
 */
data class HeartToday(val bpm: Int, val min: Int?, val max: Int?, val hours: List<IntRange?> = HeartHours.empty())

/** Today's hourly heart-rate ranges, stored as "min-max" per hour ("" when empty). */
object HeartHours {
    fun empty(): List<IntRange?> = List(24) { null }

    fun record(hours: List<IntRange?>, hour: Int, bpm: Int): List<IntRange?> = hours.mapIndexed { i, r ->
        if (i != hour) r else r?.let { minOf(it.first, bpm)..maxOf(it.last, bpm) } ?: bpm..bpm
    }

    fun encode(hours: List<IntRange?>): String = hours.joinToString(",") { it?.let { r -> "${r.first}-${r.last}" } ?: "" }

    fun decode(text: String?): List<IntRange?> {
        val parts = text?.split(",")?.takeIf { it.size == 24 } ?: return empty()
        return parts.map { p -> p.split("-").takeIf { it.size == 2 }?.let { (a, b) -> a.toIntOrNull()?.let { lo -> b.toIntOrNull()?.let { hi -> lo..hi } } } }
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
        val (title, text) = AlertText.of(alert, context::getString)
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
        if (alert.kind == AlertKind.IRREGULAR_RHYTHM) {
            // Checking with an ECG right away is the most useful next step.
            builder.addAction(R.drawable.ic_heart, context.getString(R.string.alert_take_ecg), deepLink(5, MainActivity.ROUTE_ECG))
        }
        if (alert.vital == VitalAlert.STRESS) {
            // A minute of slow breathing, right from the notice.
            builder.addAction(R.drawable.ic_heart, context.getString(R.string.alert_breathe), deepLink(6, MainActivity.ROUTE_BREATHE))
        }
        manager.notify(alert.id.hashCode(), builder.build())
    }

    /** Opens [route] in the app (heartline://watch/<route>), not just the home screen. */
    private fun deepLink(requestCode: Int, route: String) = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(Intent.ACTION_VIEW, Uri.parse(DeepLinks.watch(EntryLinks.tag(route, EntrySource.NOTIFICATION))), context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Fallback only: the phone opens the calibration screen directly, and an open screen runs each
     * round by itself. This shows when the app was closed; it replaces (never stacks on) the last one.
     */
    fun calibrationRequest(round: Int) {
        val open = deepLink(1, MainActivity.ROUTE_BP_CALIBRATION)
        manager.notify(
            CALIBRATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle(context.getString(R.string.bp_calibration_round, round))
                .setContentText(context.getString(R.string.bp_calibrate_notification, round))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    fun cancelCalibrationRequest() = manager.cancel(CALIBRATION_ID)

    /** Fallback when the phone's direct launch failed: tapping opens the requested screen itself. */
    fun openRequest(route: String) {
        val open = deepLink(4, route)
        manager.notify(
            OPEN_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle(context.getString(R.string.open_request_title))
                .setContentText(context.getString(R.string.open_request_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
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
        batch.limits?.let { settings.limits = it }
        store.enqueueMessage(batch.id, Protocol.HR_BATCH, Protocol.json.encodeToString(batch).encodeToByteArray())
        sync.schedule()
    }

    override suspend fun enqueueAlert(alert: HealthAlert) {
        // Why it fired, so a notice can be checked from an exported log.
        HLog.i("Heartline/Alert", "fired ${alert.describe()}")
        store.enqueueMessage(alert.id, Protocol.ALERT, Protocol.json.encodeToString(alert).encodeToByteArray())
        sync.schedule()
    }

    override suspend fun loadMonitorState() = settings.monitorState

    override suspend fun saveMonitorState(state: MonitorState) {
        settings.monitorState = state
    }

    override fun notify(alert: HealthAlert) = notifier.alert(alert)

    override fun latestMinute(bpm: Int) {
        settings.recordHeartRate(bpm)
    }
}

/** Title and text of a heart alert, naming the limit, the wearer's normal and what they were doing (watch strings). */
object AlertText {
    fun of(alert: HealthAlert, string: (Int) -> String): Pair<String, String> {
        val bpm = alert.bpm ?: 0
        val limit = alert.threshold
        val normal = alert.normal
        val change = alert.value?.let { "%.1f".format(it) } ?: ""
        return when {
            alert.vital == VitalAlert.SPO2_LOW -> string(R.string.alert_spo2_low_title) to string(R.string.alert_spo2_low_text).format(bpm, limit ?: 90)
            alert.vital == VitalAlert.STRESS -> string(R.string.alert_stress_title) to string(R.string.alert_stress_text)
            alert.vital == VitalAlert.SPO2_NIGHTS -> string(R.string.alert_spo2_nights_title) to string(R.string.alert_spo2_nights_text)
            alert.vital == VitalAlert.TEMPERATURE -> string(R.string.alert_temp_title) to string(R.string.alert_temp_text).format(change)
            alert.vital == VitalAlert.COMBINED -> string(R.string.alert_combined_title) to string(R.string.alert_combined_text).format(change)
            alert.trend == HeartTrend.ELEVATED_RESTING ->
                string(R.string.alert_trend_title) to string(R.string.alert_trend_text).format(bpm, normal ?: 0)
            alert.trend == HeartTrend.HIGH_NORMAL ->
                string(R.string.alert_high_normal_title) to string(R.string.alert_high_normal_text).format(bpm)
            alert.kind == AlertKind.IRREGULAR_RHYTHM -> string(R.string.alert_irn_title) to string(R.string.alert_irn_text)
            alert.kind == AlertKind.HIGH_HEART_RATE -> string(R.string.alert_high_title) to when {
                limit == null -> string(R.string.alert_high_text).format(bpm)
                alert.context == HrContext.EXERCISE || alert.context == HrContext.ACTIVE -> string(R.string.alert_high_exercise_text).format(limit, bpm)
                normal != null -> string(if (alert.context == HrContext.SLEEP) R.string.alert_high_sleep_normal_text else R.string.alert_high_rest_normal_text)
                    .format(bpm, percentFrom(bpm, normal), normal)
                alert.context == HrContext.SLEEP -> string(R.string.alert_high_sleep_text).format(limit, bpm)
                else -> string(R.string.alert_high_rest_text).format(limit, bpm)
            }
            else -> string(R.string.alert_low_title) to when {
                limit == null -> string(R.string.alert_low_text).format(bpm)
                normal != null -> string(if (alert.context == HrContext.SLEEP) R.string.alert_low_sleep_normal_text else R.string.alert_low_rest_normal_text)
                    .format(bpm, percentFrom(bpm, normal), normal)
                alert.context == HrContext.SLEEP -> string(R.string.alert_low_sleep_text).format(limit, bpm)
                else -> string(R.string.alert_low_rest_text).format(limit, bpm)
            }
        }
    }

    /** How far [bpm] is from [normal], in percent (always positive). */
    fun percentFrom(bpm: Int, normal: Int): Int = if (normal <= 0) 0 else kotlin.math.abs(bpm - normal) * 100 / normal
}
