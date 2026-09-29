// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.diag

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.heartline.datalayer.diag.HLog
import com.heartline.shared.diag.LogBundle
import com.heartline.shared.diag.SegmentedLog
import com.heartline.shared.diag.formatLogSize
import com.heartline.shared.sync.LogManifest
import com.heartline.shared.sync.LogSegment
import com.heartline.wear.BuildConfig
import com.heartline.wear.monitor.WatchSettingsStore
import com.heartline.wear.sensor.GatewayState
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.ui.setup.SetupPermissions
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.GZIPOutputStream

/**
 * The watch's side of Export logs. The phone first asks for the [manifest] (header and segment
 * list), then for each [segment] file, which is streamed as it is on disk. Phones from before
 * segmented export get [wholeLog] instead.
 */
class WatchLogExporter(private val context: Context, private val gateway: SensorGateway, private val settings: WatchSettingsStore) {
    private val logs = File(context.filesDir, "logs")
    private val logcat = File(context.cacheDir, LOGCAT)

    private fun header(): LinkedHashMap<String, String> {
        val s = settings.settings.value
        val sensors = gateway.state.value
        val connected = sensors as? GatewayState.Connected
        return linkedMapOf(
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
            "Diagnostic logs" to "on=${s.diagnosticLogs} kept=${formatLogSize(HLog.sizeBytes())}",
        )
    }

    /** Closes the current segment and lists them all, oldest first, with this process's logcat last. */
    fun manifest(): LogManifest {
        val segments = HLog.sealedSegments()
        GZIPOutputStream(logcat.outputStream().buffered()).use { it.write(HLog.processLogcat().toByteArray(Charsets.UTF_8)) }
        val head = header().apply { put("Log segments", "${segments.size}, ${formatLogSize(segments.sumOf { it.length() })} compressed") }
        return LogManifest(
            header = LogBundle.head(head),
            segments = (segments + logcat).map { LogSegment(it.name, it.length()) },
        )
    }

    /** The file of segment [name] (from the last [manifest]), or null when it's gone or not a log file. */
    fun segment(name: String): File? = when {
        name == LOGCAT -> logcat
        SegmentedLog.isSegment(name) -> File(logs, name)
        else -> null
    }?.takeIf { it.isFile }

    /** The whole log as one text, its last 4 MB (phones from before segmented export). */
    fun wholeLog(): String = LogBundle.build(header(), HLog.readRecent(4 * 1024 * 1024), HLog.processLogcat())

    companion object {
        /** The process logcat's segment name (always the last one). */
        const val LOGCAT = "logcat.log.gz"
    }
}
