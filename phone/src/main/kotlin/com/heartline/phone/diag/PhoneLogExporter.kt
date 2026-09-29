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
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.SettingsRepository
import com.heartline.phone.update.UpdateRepository
import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.diag.LogBundle
import com.heartline.shared.diag.RawSessions
import com.heartline.shared.diag.RemoteLogs
import com.heartline.shared.diag.SegmentedLog
import com.heartline.shared.diag.SessionCsv
import com.heartline.shared.diag.WatchLogArchive
import com.heartline.shared.diag.formatLogSize
import com.heartline.shared.sync.LogSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export logs: one zip in a folder the user picked with everything both apps kept:
 * - `phone.log` and `watch.log`: the whole text log of each (every segment, oldest first; the
 *   watch's from the phone's [archive] plus what's still on the watch) and its process logcat;
 * - `sessions/<time>-<kind>-<id>/`: every raw sensor session (each measurement, background
 *   monitoring, every blood-pressure session), `header.json` and one CSV per sensor stream;
 * - `records/`: every saved measurement (`records.csv`) and each one's stored wave.
 *
 * What's still on the watch comes over one piece at a time ([RemoteLogs]) into a cache folder
 * first, so a stalled piece is simply asked for again; nothing is held in memory whole.
 */
class PhoneLogExporter(
    private val context: Context,
    private val settings: SettingsRepository,
    private val updates: UpdateRepository,
    private val remote: RemoteLogs,
    private val archive: WatchLogArchive? = null,
    private val records: RecordRepository? = null,
    /** The raw blood-pressure sessions the phone keeps for the algorithm (BpRepository). */
    private val bpSessions: File? = null,
    /** Blood-pressure calibrations and cuff checks (the cuff values every session is compared with). */
    private val bpData: (suspend () -> Map<String, ByteArray>)? = null,
) {
    enum class Step { PHONE, WATCH, SAVING }

    /** Where the export is; [part] of [parts] and [bytes] only while the watch's pieces arrive. */
    data class Progress(val step: Step, val part: Int = 0, val parts: Int = 0, val bytes: Long = 0)

    /** [missingParts]: watch pieces that didn't arrive after every retry (marked in the file). */
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
                writeZip(tree, name) { zip -> writeAll(zip, phone, watch) }
            }
            HLog.i(TAG, "exported $name (watch reached=${watch.log.reached}, sessions=${watch.sessions.size}, missing parts=${watch.missing})")
            return Result(name, watch.log.reached, watch.missing)
        } finally {
            withContext(Dispatchers.IO) { work.deleteRecursively() }
        }
    }

    /** The zip's content (separate from the Storage Access Framework so tests can read it). */
    suspend fun writeAll(zip: ZipOutputStream, phone: LogParts, watch: WatchParts) {
        zip.entry("phone.log") { phone.writeTo(it) }
        zip.entry("watch.log") { watch.log.writeTo(it) }
        writeSessions(zip, watch.sessions)
        writeRecords(zip)
        bpData?.invoke()?.forEach { (name, bytes) -> zip.entry(name) { it.write(bytes) } }
    }

    /** One device's log file: the header, the segments (gzip files, oldest first; null = missing), the logcat. */
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

    /** The watch's log file and its raw sessions (from the phone's archive and the watch). */
    class WatchParts(val log: LogParts, val sessions: List<File>, private val missingSessions: Int = 0) {
        val missing: Int get() = log.missing + missingSessions
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
            "Diagnostic logs" to "on=${s.diagnosticLogs} kept=${formatLogSize(HLog.sizeBytes())} watch copy=${formatLogSize(archive?.sizeBytes() ?: 0)}",
            "Log segments" to "${segments.size}, ${formatLogSize(segments.sumOf { it.length() })} compressed",
        )
    }

    /**
     * The watch's log and raw sessions: what already moved to the phone ([archive]) plus what's
     * still on the watch, fetched piece by piece into [work]; [onPart] reports (part, parts, bytes).
     */
    suspend fun watchParts(work: File, onPart: (Int, Int, Long) -> Unit = { _, _, _ -> }): WatchParts {
        val archivedLog = archive?.logSegments().orEmpty()
        val archivedRaw = archive?.rawSessions().orEmpty()
        return when (val answer = remote.manifest()) {
            null -> WatchParts(
                LogParts(
                    LogBundle.head(
                        linkedMapOf("App" to "Heartline watch ${updates.current().watchVersion ?: "(unknown version)"}", "Exported" to ZonedDateTime.now().toString()),
                        note = "The watch did not answer, so this is only what had already moved to the phone. Make sure the watch is " +
                            "connected and open Heartline on it, then export again for the newest part.",
                    ),
                    archivedLog,
                    null,
                    reached = false,
                ),
                archivedRaw,
            )
            is RemoteLogs.Answer.WholeLog -> WatchParts(LogParts("", emptyList(), null, wholeLog = answer.text), archivedRaw)
            is RemoteLogs.Answer.Manifest -> {
                val have = (archivedLog + archivedRaw).map { it.name }.toSet()
                // Only what isn't on the phone yet comes over.
                val wanted = answer.manifest.segments.filter { it.name.substringAfterLast('/') !in have }
                val total = wanted.sumOf { it.size }
                var done = 0L
                val fetched = wanted.mapIndexed { i, segment ->
                    val target = File(work, "watch-${i.toString().padStart(6, '0')}-${segment.name.substringAfterLast('/')}")
                    val ok = remote.download(segment, target) { bytes -> onPart(i + 1, wanted.size, done + bytes) }
                    done += segment.size
                    onPart(i + 1, wanted.size, done)
                    // It may have just moved to the phone on its own (the watch sends finished pieces
                    // in the background), and so be gone from the watch but here in the archive.
                    val moved = if (ok) null else archived(segment.name.substringAfterLast('/'))
                    if (!ok && moved == null) HLog.w(TAG, "watch log piece ${segment.name} (${segment.size} B) didn't arrive")
                    segment to (target.takeIf { ok } ?: moved?.copyTo(target, overwrite = true))
                }
                HLog.i(TAG, "watch log: ${archivedLog.size + archivedRaw.size} pieces already here, ${wanted.size} fetched, ${formatLogSize(total)}")
                fun kind(s: LogSegment) = when {
                    s.name == LOGCAT -> "logcat"
                    s.name.startsWith(RAW) -> "raw"
                    else -> "log"
                }
                val logs = archivedLog.map { SegmentedLog.number(it.name) to it } +
                    fetched.filter { kind(it.first) == "log" }.map { (s, f) -> SegmentedLog.number(s.name) to f }
                val raws = fetched.filter { kind(it.first) == "raw" }
                WatchParts(
                    LogParts(answer.manifest.header, logs.sortedBy { it.first }.map { it.second }, fetched.firstOrNull { kind(it.first) == "logcat" }?.second),
                    (archivedRaw + raws.mapNotNull { it.second?.let { f -> f.renamed(it.first.name.removePrefix(RAW)) } }).sortedBy { it.name },
                    missingSessions = raws.count { it.second == null },
                )
            }
        }
    }

    private fun archived(name: String): File? = archive?.let { a -> (a.logSegments() + a.rawSessions()).firstOrNull { it.name == name } }

    /** A downloaded piece under its own name (the session folder in the zip is named after it). */
    private fun File.renamed(name: String): File = File(parentFile, name).also { renameTo(it) }

    /** Every raw session as `sessions/<name>/header.json` and one CSV per stream, and the phone's BP sessions not among them. */
    private suspend fun writeSessions(zip: ZipOutputStream, watchSessions: List<File>) {
        val ids = watchSessions.map { RawSessions.idOf(it.name) }.toSet()
        val phoneBp = bpSessions?.listFiles { f -> f.extension == "hlbp" }.orEmpty().associateBy { it.nameWithoutExtension.filter(Char::isLetterOrDigit).take(8) }
        val phoneOnly = phoneBp.filterKeys { it !in ids }.values
        val all = watchSessions.map { it to null } + phoneOnly.map { it to "bp" }
        for ((file, kind) in all) {
            val log = withContext(Dispatchers.IO) {
                runCatching { BpSessionLog.decode(file.readBytes()) }.getOrNull()?.let { log ->
                    // The watch's copy of a BP session gets the cuff reading the phone added to its own copy.
                    val phoneHeader = if (kind == null) phoneBp[RawSessions.idOf(file.name)]?.let { runCatching { BpSessionLog.decode(it.readBytes()).header }.getOrNull() } else null
                    if (phoneHeader?.cuffSystolic != null && log.header.cuffSystolic == null) {
                        log.copy(header = log.header.copy(cuffSystolic = phoneHeader.cuffSystolic, cuffDiastolic = phoneHeader.cuffDiastolic, cuffPulse = phoneHeader.cuffPulse))
                    } else {
                        log
                    }
                }
            }
            if (log == null) {
                zip.entry("sessions/unreadable-${file.name}") { out -> file.inputStream().use { it.copyTo(out) } }
                continue
            }
            val name = (if (kind == null) file.name else RawSessions.fileName(log.header.startedAtMs, kind, log.header.id)).removeSuffix(".hlbp")
            zip.entry("sessions/$name/header.json") { it.write(SessionCsv.header(log.header).toByteArray()) }
            log.streams.forEach { stream -> zip.entry("sessions/$name/${SessionCsv.fileName(stream)}") { SessionCsv.write(stream, it) } }
        }
    }

    /** Every saved measurement (one row each) and each one's stored wave as it came from the watch. */
    private suspend fun writeRecords(zip: ZipOutputStream) {
        val repo = records ?: return
        val all = repo.all().sortedBy { it.startedAtMs }
        zip.entry("records/records.csv") { out ->
            val w = out.bufferedWriter()
            w.write("id,kind,start,startMs,durationMs,sampleRateHz,sampleCount,note,summary\n")
            all.forEach { r ->
                w.write(listOf(r.id, r.kind.name, TIME.format(Instant.ofEpochMilli(r.startedAtMs)), r.startedAtMs, r.durationMs, r.sampleRateHz, r.sampleCount, r.note.orEmpty(), r.summaryJson).joinToString(",") { csv(it.toString()) })
                w.write("\n")
            }
            w.flush()
        }
        for (r in all) {
            val wave = repo.wave(r) ?: continue
            val fs = r.sampleRateHz.takeIf { it > 0 } ?: 1
            zip.entry("records/${FILE_TIME.format(Instant.ofEpochMilli(r.startedAtMs))}-${r.kind.name.lowercase()}-${r.id.take(8)}-wave.csv") { out ->
                val w = out.bufferedWriter()
                w.write("index,timeMs,value\n")
                wave.forEachIndexed { i, v -> w.write("$i,${i * 1000L / fs},${if (v.isFinite()) v.toString() else ""}\n") }
                w.flush()
            }
        }
    }

    private suspend fun ZipOutputStream.entry(name: String, write: suspend (OutputStream) -> Unit) {
        putNextEntry(ZipEntry(name))
        write(this)
        closeEntry()
    }

    private fun csv(value: String) = if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private suspend fun writeZip(tree: Uri, name: String, write: suspend (ZipOutputStream) -> Unit) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val file = DocumentsContract.createDocument(resolver, parent, "application/zip", name) ?: throw IOException("Can't create $name")
        val out = resolver.openOutputStream(file) ?: throw IOException("Can't write $name")
        out.use { ZipOutputStream(it.buffered()).use { zip -> write(zip) } }
    }

    private companion object {
        const val TAG = "Heartline/Diag"

        /** The watch's logcat piece (WatchLogExporter.LOGCAT) and raw session prefix (RAW_PREFIX). */
        const val LOGCAT = "logcat.log.gz"
        const val RAW = "raw/"

        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
    }
}
