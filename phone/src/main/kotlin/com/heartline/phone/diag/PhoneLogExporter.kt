// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.heartline.datalayer.diag.HLog
import com.heartline.phone.BuildConfig
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.update.UpdateRepository
import com.heartline.shared.diag.DiagnosticsPolicy
import com.heartline.shared.diag.LogBundle
import com.heartline.shared.diag.RemoteLogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Writes the phone's and the watch's logs as two text files into a folder the user picked. */
class PhoneLogExporter(
    private val context: Context,
    private val settings: SettingsRepository,
    private val updates: UpdateRepository,
    private val remote: RemoteLogs,
) {
    enum class Step { PHONE, WATCH, SAVING }

    data class Result(val phoneFile: String, val watchFile: String, val watchReached: Boolean)

    suspend fun export(tree: Uri, onStep: (Step) -> Unit = {}): Result {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        onStep(Step.PHONE)
        val phone = withContext(Dispatchers.IO) { phoneLog() }
        onStep(Step.WATCH)
        val watchBytes = remote.fetch()
        val watch = watchLog(watchBytes)
        onStep(Step.SAVING)
        val phoneName = "heartline-phone-$stamp.log"
        val watchName = "heartline-watch-$stamp.log"
        withContext(Dispatchers.IO) {
            write(tree, phoneName, phone)
            write(tree, watchName, watch)
        }
        HLog.i(TAG, "exported logs (watch reached=${watchBytes != null})")
        return Result(phoneName, watchName, watchBytes != null)
    }

    suspend fun phoneLog(): String {
        val s = settings.current()
        val u = updates.current()
        val notifications = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val header = linkedMapOf(
            "App" to "Heartline phone ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})",
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "System" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}",
            "Exported" to ZonedDateTime.now().toString(),
            "Time zone" to ZoneId.systemDefault().id,
            "Updates" to "auto=${u.autoCheck} beta=${u.beta} updater=${BuildConfig.UPDATER}",
            "Watch app" to (u.watchVersion ?: "never connected"),
            "Notifications" to if (notifications) "allowed" else "denied",
            "Monitoring" to "irregularRhythm=${s.irregularRhythmEnabled} hrAlerts=${s.heartRateAlertsEnabled} (${s.lowBpm}-${s.highBpm}) " +
                "backgroundHr=${s.backgroundHeartRate} interval=${s.irnIntervalMinutes}min",
            "Diagnostic logs" to "on=${s.diagnosticLogs} detailed=${DiagnosticsPolicy.detailed(s.diagnosticLogs, s.detailedLogsUntilMs, System.currentTimeMillis())} " +
                "kept=${HLog.sizeBytes() / 1024} KB",
        )
        return LogBundle.build(header, HLog.read(), HLog.processLogcat())
    }

    /** The watch's file as it sent it, or a note saying it didn't answer. */
    suspend fun watchLog(received: ByteArray?): String = received?.decodeToString() ?: LogBundle.build(
        linkedMapOf("App" to "Heartline watch ${updates.current().watchVersion ?: "(unknown version)"}", "Exported" to ZonedDateTime.now().toString()),
        persistent = "",
        logcat = "",
        note = "The watch did not answer. Make sure it is connected to the phone and open Heartline on the watch, then export again.",
    )

    private fun write(tree: Uri, name: String, text: String) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val file = DocumentsContract.createDocument(resolver, parent, "text/plain", name) ?: throw IOException("Can't create $name")
        resolver.openOutputStream(file)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw IOException("Can't write $name")
    }

    private companion object {
        const val TAG = "Heartline/Diag"
    }
}
