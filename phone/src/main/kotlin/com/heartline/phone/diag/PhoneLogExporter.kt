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
import com.heartline.shared.diag.LogBundle
import com.heartline.shared.diag.RemoteLogs
import com.heartline.shared.diag.SegmentedLog
import com.heartline.shared.diag.formatLogSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export logs: one zip in a folder the user picked, with `phone.log` and `watch.log`, each the
 * whole kept log (every segment, oldest first) plus the process logcat. The watch's segments come
 * over one at a time ([RemoteLogs]) into a cache folder first, so a stalled one is simply asked
 * for again; everything is streamed, never held in memory whole.
 */
class PhoneLogExporter(
    private val context: Context,
    private val settings: SettingsRepository,
    private val updates: UpdateRepository,
    private val remote: RemoteLogs,
) {
    enum class Step { PHONE, WATCH, SAVING }

    /** Where the export is; [part] of [parts] and [bytes] only while the watch's segments arrive. */
    data class Progress(val step: Step, val part: Int = 0, val parts: Int = 0, val bytes: Long = 0)

    /** [missingParts]: watch segments that didn't arrive after every retry (marked in the file). */
    data class Result(val file: String, val watchReached: Boolean, val missingParts: Int = 0)

    suspend fun export(tree: Uri, onProgress: (Progress) -> Unit = {}): Result {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val work = File(context.cacheDir, "log-export").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            onProgress(Progress(Step.PHONE))
            val phone = withContext(Dispatchers.IO) { phoneParts(work) }
            onProgress(Progress(Step.WATCH))
            val watch = watchParts(work) { part, parts, bytes -> onProgress(Progress(Step.WATCH, part, parts, bytes)) }
            onProgress(Progress(Step.SAVING))
            val name = "heartline-logs-$stamp.zip"
            withContext(Dispatchers.IO) {
                writeZip(tree, name) { zip ->
                    zip.putNextEntry(ZipEntry("phone.log"))
                    phone.writeTo(zip)
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("watch.log"))
                    watch.writeTo(zip)
                    zip.closeEntry()
                }
            }
            HLog.i(TAG, "exported $name (watch reached=${watch.reached}, missing parts=${watch.missing})")
            return Result(name, watch.reached, watch.missing)
        } finally {
            withContext(Dispatchers.IO) { work.deleteRecursively() }
        }
    }

    /** One device's log file: the header, the segments (gzip files, oldest first), the logcat. */
    class LogParts(
        private val head: String,
        private val segments: List<File?>,
        private val logcat: File?,
        val reached: Boolean = true,
        /** A whole log sent as text by a watch from before segmented export. */
        private val wholeLog: ByteArray? = null,
    ) {
        val missing: Int get() = segments.count { it == null } + (if (reached && wholeLog == null && logcat == null) 1 else 0)

        fun writeTo(out: OutputStream) {
            if (wholeLog != null) return out.write(wholeLog)
            out.write(head.toByteArray(Charsets.UTF_8))
            if (segments.isEmpty()) out.write("(empty: diagnostic logs are off or nothing was logged yet)\n".toByteArray())
            segments.forEachIndexed { i, segment ->
                if (segment == null || !segment.exists()) {
                    out.write("\n[part ${i + 1} of ${segments.size} of this log didn't arrive]\n".toByteArray())
                } else {
                    SegmentedLog.open(segment).use { it.copyTo(out) }
                }
            }
            out.write("\n${LogBundle.LOGCAT_TITLE}\n".toByteArray())
            if (logcat != null) GZIPInputStream(logcat.inputStream().buffered()).use { it.copyTo(out) } else out.write("(not received)\n".toByteArray())
        }
    }

    suspend fun phoneParts(work: File): LogParts {
        val segments = HLog.sealedSegments()
        val logcat = File(work, "phone-logcat.log.gz")
        GZIPOutputStream(logcat.outputStream().buffered()).use { it.write(HLog.processLogcat().toByteArray(Charsets.UTF_8)) }
        return LogParts(LogBundle.head(phoneHeader(segments)), segments, logcat)
    }

    private suspend fun phoneHeader(segments: List<File>): Map<String, String> {
        val s = settings.current()
        val u = updates.current()
        val notifications = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return linkedMapOf(
            "App" to "Heartline phone ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})",
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "System" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}",
            "Exported" to ZonedDateTime.now().toString(),
            "Time zone" to ZoneId.systemDefault().id,
            "Updates" to "auto=${u.autoCheck} track=${u.track} updater=${BuildConfig.UPDATER}",
            "Watch app" to (u.watchVersion ?: "never connected"),
            "Notifications" to if (notifications) "allowed" else "denied",
            "Monitoring" to "irregularRhythm=${s.irregularRhythmEnabled} hrAlerts=${s.heartRateAlertsEnabled} (${s.lowBpm}-${s.highBpm}) " +
                "backgroundHr=${s.backgroundHeartRate} interval=${s.irnIntervalMinutes}min",
            "Diagnostic logs" to "on=${s.diagnosticLogs} kept=${formatLogSize(HLog.sizeBytes())}",
            "Log segments" to "${segments.size}, ${formatLogSize(segments.sumOf { it.length() })} compressed",
        )
    }

    /** Fetches the watch's log segment by segment into [work]; [onPart] reports (part, parts, bytes so far). */
    suspend fun watchParts(work: File, onPart: (Int, Int, Long) -> Unit = { _, _, _ -> }): LogParts =
        when (val answer = remote.manifest()) {
            null -> LogParts(
                LogBundle.head(
                    linkedMapOf("App" to "Heartline watch ${updates.current().watchVersion ?: "(unknown version)"}", "Exported" to ZonedDateTime.now().toString()),
                    note = "The watch did not answer. Make sure it is connected to the phone and open Heartline on the watch, then export again.",
                ),
                emptyList(),
                null,
                reached = false,
            )
            is RemoteLogs.Answer.WholeLog -> LogParts("", emptyList(), null, wholeLog = answer.text)
            is RemoteLogs.Answer.Manifest -> {
                val all = answer.manifest.segments
                val total = all.sumOf { it.size }
                var done = 0L
                val files = all.mapIndexed { i, segment ->
                    val target = File(work, "watch-${i.toString().padStart(6, '0')}.gz")
                    val ok = remote.download(segment, target) { bytes -> onPart(i + 1, all.size, done + bytes) }
                    done += segment.size
                    onPart(i + 1, all.size, done)
                    if (!ok) HLog.w(TAG, "watch log segment ${segment.name} (${segment.size} B) didn't arrive")
                    target.takeIf { ok }
                }
                val logcatIndex = all.indexOfLast { it.name == LOGCAT }
                LogParts(
                    answer.manifest.header,
                    files.filterIndexed { i, _ -> i != logcatIndex },
                    files.getOrNull(logcatIndex),
                ).also { HLog.i(TAG, "watch log: ${all.size} parts, ${formatLogSize(total)}") }
            }
        }

    private fun writeZip(tree: Uri, name: String, write: (ZipOutputStream) -> Unit) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val file = DocumentsContract.createDocument(resolver, parent, "application/zip", name) ?: throw IOException("Can't create $name")
        resolver.openOutputStream(file)?.use { out -> ZipOutputStream(out.buffered()).use(write) } ?: throw IOException("Can't write $name")
    }

    private companion object {
        const val TAG = "Heartline/Diag"

        /** The watch's logcat segment (WatchLogExporter.LOGCAT). */
        const val LOGCAT = "logcat.log.gz"
    }
}
