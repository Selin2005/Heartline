// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

import com.heartline.shared.sync.ArchiveAck
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/** The two kinds of files the watch moves to the phone. */
object LogFiles {
    /** A closed segment of the text log ([SegmentedLog]). */
    const val LOG = "log"

    /** A raw sensor session ([RawSessions]). */
    const val RAW = "raw"

    fun valid(type: String, name: String) = when (type) {
        LOG -> SegmentedLog.isSegment(name)
        RAW -> RawSessions.isSession(name)
        else -> false
    }
}

/**
 * Phone side: the watch's log segments and raw sessions, moved here as they're finished so the
 * watch keeps room for new ones. Kept in [dir]/log and [dir]/raw up to [budgetBytes], oldest
 * first out (sooner while the phone has less than [minFreeBytes] free).
 */
class WatchLogArchive(
    private val dir: File,
    private val budgetBytes: Long,
    private val minFreeBytes: Long = 1024L * 1024 * 1024,
    private val freeBytes: () -> Long = { dir.usableSpace }
) {
    /** Stores [input] as file [name] of [type]; returns its size, or null for a name that isn't a log file. */
    @Synchronized
    fun receive(type: String, name: String, input: InputStream): Long? = input.use { stream ->
        if (!LogFiles.valid(type, name)) return null
        val folder = File(dir, type).apply { mkdirs() }
        val tmp = File(folder, "$name.tmp")
        tmp.outputStream().buffered().use { stream.copyTo(it) }
        val file = File(folder, name)
        file.delete()
        tmp.renameTo(file)
        trim()
        file.length()
    }

    /** The watch's text log segments here, oldest first. */
    fun logSegments(): List<File> = files(LogFiles.LOG).sortedBy { SegmentedLog.number(it.name) }

    /** The watch's raw sessions here, oldest first. */
    fun rawSessions(): List<File> = files(LogFiles.RAW).sortedBy { it.name }

    fun sizeBytes(): Long = files(LogFiles.LOG).sumOf { it.length() } + files(LogFiles.RAW).sumOf { it.length() }

    @Synchronized
    fun clear() {
        File(dir, LogFiles.LOG).deleteRecursively()
        File(dir, LogFiles.RAW).deleteRecursively()
    }

    private fun files(type: String): List<File> = File(dir, type).listFiles { f -> LogFiles.valid(type, f.name) }.orEmpty().toList()

    private fun trim() {
        val all = (logSegments() + rawSessions()).sortedBy { it.lastModified() }.toMutableList()
        var total = all.sumOf { it.length() }
        while (all.size > 1 && (total > budgetBytes || freeBytes() < minFreeBytes)) {
            val oldest = all.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }
}

/**
 * Watch side: sends every finished log segment and raw session to the phone ([send] streams one
 * file) and deletes it once the phone confirms it stored the whole file ([onAck]). Files the phone
 * didn't confirm stay and go again on the next [run] (after [resendAfterMs]), so nothing is lost
 * while the phone is away; the watch's own budgets only matter then.
 */
class LogOffload(
    /** Every file that could go, oldest first: (type, file). */
    private val pending: () -> List<Pair<String, File>>,
    private val send: suspend (type: String, file: File) -> Boolean,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val resendAfterMs: Long = 5 * 60_000L
) {
    private val mutex = Mutex()
    private val awaiting = ConcurrentHashMap<String, Long>()

    /** The last [run] stopped at a file the phone didn't take (it's away): worth trying again later. */
    @Volatile var stalled = false
        private set

    /**
     * Sends what's pending, oldest first, stopping at the first failure (the phone is away).
     * Returns how many files went; 0 right away if a run is already going.
     */
    suspend fun run(): Int {
        if (!mutex.tryLock()) return 0
        try {
            var sent = 0
            stalled = false
            for ((type, file) in pending()) {
                val key = "$type/${file.name}"
                val since = awaiting[key]
                if (since != null && clockMs() - since < resendAfterMs) continue
                if (!file.exists()) continue
                if (!send(type, file)) {
                    stalled = true
                    break
                }
                awaiting[key] = clockMs()
                sent++
            }
            return sent
        } finally {
            mutex.unlock()
        }
    }

    /** The phone stored [ack]'s file: the local copy goes if it's the same size. */
    fun onAck(ack: ArchiveAck) {
        awaiting.remove("${ack.type}/${ack.name}")
        pending().firstOrNull { (type, file) -> type == ack.type && file.name == ack.name }?.second
            ?.takeIf { it.length() == ack.size }
            ?.delete()
    }
}
