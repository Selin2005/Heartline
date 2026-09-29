// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.bp.BpSessionRecorder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Every sensor value the watch receives, kept as sessions: each measurement ([begin] … [end])
 * gets one file with every sensor it used, sample by sample, in the [BpSessionLog] format
 * (`<time>-<kind>-<id>.hlbp`; tools/bp-ml/read_session.py reads it). Values that arrive outside
 * a measurement (background monitoring) go into an `other` session that is closed every
 * [otherMaxMs] or [otherMaxRows], so nothing is lost and nothing grows without end.
 *
 * Files are trimmed oldest first to [budgetBytes] and while the device has less than
 * [minFreeBytes] free; [onSaved] gets each new file (the watch sends it to the phone). It runs on
 * the writing thread and must only hand the file on, not wait.
 * Thread-safe: sensor callbacks record from their own threads.
 */
class RawSessions(
    private val dir: File,
    private val budgetBytes: Long,
    private val device: String = "",
    private val appVersion: String = "",
    private val minFreeBytes: Long = 500L * 1024 * 1024,
    private val freeBytes: () -> Long = { dir.usableSpace },
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val otherMaxMs: Long = 10 * 60_000L,
    private val otherMaxRows: Int = 200_000,
    private val onSaved: (File) -> Unit = {}
) {
    private class Active(val id: String, val kind: String, val startedAtMs: Long, val recorder: BpSessionRecorder) {
        /** Column sets per stream name: a stream whose columns change continues under a new name. */
        val columns = mutableMapOf<String, List<String>>()
        var rows = 0
    }

    /** Recording happens only while diagnostic logs are on. */
    @Volatile var enabled = false

    private val io = Executors.newSingleThreadExecutor { Thread(it, "RawSessions").apply { isDaemon = true } }

    private var current: Active? = null
    private var other: Active? = null

    /**
     * Starts a measurement session of [kind] (closing the one before, if any); returns its id. A
     * [background] session (a monitoring window) doesn't interrupt a running measurement: it
     * returns "" and its values go into that measurement.
     */
    @Synchronized
    fun begin(kind: String, notes: Map<String, String> = emptyMap(), background: Boolean = false): String {
        if (background && current != null) return ""
        close(other).also { other = null }
        close(current)
        val id = UUID.randomUUID().toString()
        current = if (enabled) {
            start(id, kind).also { a -> notes.forEach { (k, v) -> a.recorder.note(k, v) } }
        } else {
            null
        }
        return id
    }

    /** Ends measurement [id], with its result, and saves it. */
    @Synchronized
    fun end(id: String, values: Map<String, Double?> = emptyMap(), notes: Map<String, String> = emptyMap()) {
        val active = current?.takeIf { it.id == id } ?: return
        values.forEach { (k, v) -> active.recorder.value(k, v) }
        notes.forEach { (k, v) -> active.recorder.note(k, v) }
        current = null
        close(active)
    }

    /** Ends measurement [id] without saving it (its data is kept elsewhere). */
    @Synchronized
    fun discard(id: String) {
        if (current?.id == id) current = null
    }

    /** A timed event in the current session (phase changes, errors). */
    @Synchronized
    fun event(type: String, detail: String = "") {
        (current ?: other)?.recorder?.event(type, detail)
    }

    /**
     * Appends samples to stream [name] of the current session (or the `other` one). Each row is
     * one timestamp (wall clock, ns) and one value per column; NaN for a value that wasn't there.
     */
    @Synchronized
    fun rows(name: String, columns: List<String>, timestampsNs: LongArray, values: List<FloatArray>) {
        if (!enabled || timestampsNs.isEmpty()) return
        val now = clockMs()
        val target = current ?: other?.takeIf { now - it.startedAtMs < otherMaxMs && it.rows < otherMaxRows }
            ?: run {
                close(other)
                start(UUID.randomUUID().toString(), OTHER).also { other = it }
            }
        var stream = name
        var n = 1
        while (target.columns[stream]?.let { it != columns } == true) stream = "$name#${++n}"
        target.columns[stream] = columns
        val writer = target.recorder.stream(stream, *columns.toTypedArray())
        for (i in timestampsNs.indices) writer.append(timestampsNs[i], *values[i])
        target.rows += timestampsNs.size
    }

    /** Keeps a session recorded elsewhere (the blood-pressure flow's own log) with the others. */
    @Synchronized
    fun saveLog(kind: String, log: BpSessionLog) {
        if (!enabled) return
        val name = fileName(log.header.startedAtMs, kind, log.header.id)
        io.execute { write(name, log.encode()) }
    }

    /** Saves whatever is being recorded (before an export). */
    @Synchronized
    fun flush() {
        close(other).also { other = null }
    }

    /** The saved sessions, oldest first. */
    fun files(): List<File> = synchronized(dir) { list() }

    private fun list(): List<File> = dir.listFiles { f -> isSession(f.name) }.orEmpty().sortedBy { it.name }

    fun sizeBytes(): Long = files().sumOf { it.length() }

    @Synchronized
    fun clear() {
        current = null
        other = null
        awaitWritten()
        files().forEach { it.delete() }
    }

    private fun start(id: String, kind: String) = Active(id, kind, clockMs(), BpSessionRecorder(id, kind, clockMs(), clockMs))

    /** Snapshots [active] here; compressing and writing happen on the background thread. */
    private fun close(active: Active?) {
        active ?: return
        if (active.rows == 0 && active.kind == OTHER) return
        // Named apart: inside the header's copy() "device" would be the header's own field.
        val deviceName = device
        val version = appVersion
        val log = active.recorder.build { copy(device = deviceName, appVersion = version) }
        val name = fileName(active.startedAtMs, active.kind, active.id)
        io.execute { write(name, log.encode()) }
    }

    /** Waits until every session closed so far is on disk. */
    fun awaitWritten() {
        io.submit {}.get()
    }

    private fun write(name: String, bytes: ByteArray) = synchronized(dir) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        val file = File(dir, name)
        tmp.renameTo(file)
        trim()
        if (file.exists()) onSaved(file)
    }

    private fun trim() {
        val kept = list().toMutableList()
        var total = kept.sumOf { it.length() }
        while (kept.size > 1 && (total > budgetBytes || freeBytes() < minFreeBytes)) {
            val oldest = kept.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    private fun fileName(startedAtMs: Long, kind: String, id: String) = Companion.fileName(startedAtMs, kind, id)

    companion object {
        /** `<local time>-<kind>-<first 8 of id>.hlbp`: sorts by time, says what it is. */
        fun fileName(startedAtMs: Long, kind: String, id: String) = "${STAMP.format(Instant.ofEpochMilli(startedAtMs))}-${kind.filter {
            it.isLetterOrDigit() || it == '_'
        }}-${id.filter { it.isLetterOrDigit() }.take(8)}.hlbp"

        /** The id part (first 8 characters) of a session file name. */
        fun idOf(name: String): String = name.removeSuffix(".hlbp").substringAfterLast('-')

        const val OTHER = "other"
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())
        private val SESSION = Regex("\\d{8}-\\d{6}-\\d{3}-[A-Za-z0-9_]+-[A-Za-z0-9]{1,8}\\.hlbp")

        /** Whether [name] is a raw session file name. */
        fun isSession(name: String) = SESSION.matches(name)

        /** The kind in a session file name ("ecg", "bp", "other", …). */
        fun kindOf(name: String): String = name.split('-').getOrNull(3).orEmpty()
    }
}
