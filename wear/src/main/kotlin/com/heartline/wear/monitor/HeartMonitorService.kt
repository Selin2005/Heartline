package com.heartline.wear.monitor

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.heartline.shared.model.Metric
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.wear.sensor.HrSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service (type health) that keeps HEART_RATE_CONTINUOUS running for trends,
 * high/low heart rate alerts and irregular rhythm notifications.
 */
class HeartMonitorService : Service() {
    private val source: HrSource by inject()
    private val output: WatchMonitorOutput by inject()
    private val settingsStore: WatchSettingsStore by inject()
    private val notifier: WatchNotifier by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var motion: StepMotionMonitor? = null
    private var monitor: HeartMonitor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, WatchNotifier.MONITOR_ID, notifier.monitoring(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        if (job?.isActive != true) {
            val steps = StepMotionMonitor(this).also { it.start() }
            motion = steps
            val heart = HeartMonitor(output, steps, { settingsStore.settings.value })
            monitor = heart
            job = scope.launch {
                // Restart the stream after errors (e.g. service reconnect) with a short pause.
                while (true) {
                    source.stream()
                        .catch { Log.w(TAG, "HR stream failed", it) }
                        .collect { heart.onSample(it) }
                    delay(30_000)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        motion?.stop()
        monitor?.let { m -> CoroutineScope(Dispatchers.Default).launch { m.flushBatch() } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HeartMonitor"

        fun canRun(context: Context): Boolean {
            val needed = PermissionPolicy.permissionsFor(Metric.HEART_RATE, Build.VERSION.SDK_INT)
            return needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        }

        /** Starts or stops monitoring to match the settings; no-op without the HR permission. */
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, HeartMonitorService::class.java)
            if (enabled && canRun(context)) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }

        val monitoringPermissions: Array<String>
            get() = (
                PermissionPolicy.permissionsFor(Metric.HEART_RATE, Build.VERSION.SDK_INT) +
                    Manifest.permission.ACTIVITY_RECOGNITION +
                    (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
                ).toTypedArray()
    }
}

/** Resumes monitoring after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = WatchSettingsStore(context).settings.value
        HeartMonitorService.sync(context, settings.irregularRhythmEnabled || settings.heartRateAlertsEnabled)
    }
}
