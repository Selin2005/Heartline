// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.diag

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.heartline.datalayer.diag.HLog
import com.heartline.shared.diag.DiagnosticsPolicy
import com.heartline.shared.diag.LogBundle
import com.heartline.wear.BuildConfig
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.ui.setup.SetupPermissions
import java.time.ZoneId
import java.time.ZonedDateTime

/** Builds the watch's log file that the phone asks for when the user exports logs. */
class WatchLogExporter(private val context: Context, private val gateway: SensorGateway, private val settings: WatchSettingsStore) {
    fun build(): String {
        val s = settings.settings.value
        val sensors = gateway.state.value
        val connected = sensors as? GatewayState.Connected
        val header = linkedMapOf(
            "App" to "Heartline watch ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})",
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "System" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}",
            "Exported" to ZonedDateTime.now().toString(),
            "Time zone" to ZoneId.systemDefault().id,
            "Health Sensor Service" to (connected?.serviceVersion ?: sensors.javaClass.simpleName),
            "Trackers" to connected?.trackers?.map { it.name }?.sorted()?.joinToString().orEmpty(),
            "Permissions" to SetupPermissions.requested.joinToString { p ->
                "${p.substringAfterLast('.')}=${if (context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "yes" else "no"}"
            },
            "Monitoring" to "irregularRhythm=${s.irregularRhythmEnabled} hrAlerts=${s.heartRateAlertsEnabled} (${s.lowBpm}-${s.highBpm}) " +
                "backgroundHr=${s.backgroundHeartRate} interval=${s.irnIntervalMinutes}min",
            "Diagnostic logs" to "on=${s.diagnosticLogs} detailed=${DiagnosticsPolicy.detailed(s.diagnosticLogs, s.detailedLogsUntilMs, System.currentTimeMillis())} " +
                "kept=${HLog.sizeBytes() / 1024} KB",
        )
        return LogBundle.build(header, HLog.read(), HLog.processLogcat())
    }
}
