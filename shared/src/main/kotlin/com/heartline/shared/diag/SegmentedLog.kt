// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The diagnostic log, kept as gzip segments in [dir]: lines go to `current.log`; at
 * [segmentBytes] it's compressed into `seg-<number>.log.gz` (about a tenth of the size) and a new
 * `current.log` starts. The oldest segments are deleted when all of them together pass
 * [budgetBytes], or while the device has less than [minFreeBytes] free, so the log never fills
 * it. Segments are exported one at a time, straight from the files.
 *
 * Not thread-safe: callers write from one thread.
 */
class SegmentedLog(
    private val dir: File,
    private val budgetBytes: Long,
    private val segmentBytes: Long = 1024L * 1024,
    private val minFreeBytes: Long = 500L * 1024 * 1024,
    private val freeBytes: () -> Long = { dir.usableSpace }
) {
    private val current = File(dir, CURRENT)

    /** Keeps what the earlier two-file log (current.log + previous.log) holds. Call once, off the main thread. */
    fun migrate() {
        val previous = File(dir, "previous.log")
        if (previous.exists()) {
            if (previous.length() > 0) compress(previous, nextSegment())
            previous.delete()
        }
    }

    fun append(text: String) {
        dir.mkdirs()
        current.appendBytes(text.toByteArray(Charsets.UTF_8))
        if (current.length() >= segmentBytes) seal()
    }

    /** Closes `current.log` into a segment (before an export, so the segments hold everything). */
    fun seal() {
        if (!current.exists() || current.length() == 0L) return
        compress(current, nextSegment())
        current.delete()
        trim()
    }

    /** The segments, oldest first. */
    fun segments(): List<File> = dir.listFiles { f -> SEGMENT.matches(f.name) }.orEmpty().sortedBy { number(it) }

    /** Everything kept, oldest first, as text: for tests and small logs only. */
    fun readAll(): String = buildString {
        segments().forEach { seg -> open(seg).use { append(it.readBytes().toString(Charsets.UTF_8)) } }
        if (current.exists()) append(current.readText(Charsets.UTF_8))
    }

    /** The last [maxBytes] of text (for a phone app from before segmented export). */
    fun readRecent(maxBytes: Int): String {
        val parts = ArrayDeque<ByteArray>()
        var size = 0
        if (current.exists()) {
            current.readBytes().also {
                parts.addFirst(it)
                size += it.size
            }
        }
        for (seg in segments().asReversed()) {
            if (size >= maxBytes) break
            val bytes = open(seg).use { it.readBytes() }
            parts.addFirst(bytes)
            size += bytes.size
        }
        val all = parts.fold(ByteArray(0)) { acc, b -> acc + b }
        return all.copyOfRange(maxOf(0, all.size - maxBytes), all.size).toString(Charsets.UTF_8)
    }

    fun sizeBytes(): Long = segments().sumOf { it.length() } + (if (current.exists()) current.length() else 0)

    fun clear() {
        segments().forEach { it.delete() }
        current.delete()
    }

    private fun nextSegment(): File = File(dir, "seg-%08d.log.gz".format((segments().lastOrNull()?.let(::number) ?: 0) + 1))

    private fun compress(from: File, to: File) {
        dir.mkdirs()
        val tmp = File(dir, to.name + ".tmp")
        GZIPOutputStream(tmp.outputStream().buffered()).use { out -> from.inputStream().use { it.copyTo(out) } }
        tmp.renameTo(to)
    }

    private fun trim() {
        val kept = segments().toMutableList()
        var total = kept.sumOf { it.length() }
        while (kept.size > 1 && (total > budgetBytes || freeBytes() < minFreeBytes)) {
            val oldest = kept.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    companion object {
        const val CURRENT = "current.log"
        private val SEGMENT = Regex("seg-(\\d+)\\.log\\.gz")

        private fun number(f: File) = SEGMENT.matchEntire(f.name)?.groupValues?.get(1)?.toLong() ?: 0

        /** Whether [name] is a segment file name (the watch sends only those). */
        fun isSegment(name: String) = SEGMENT.matches(name)

        fun open(segment: File): InputStream = GZIPInputStream(segment.inputStream().buffered())
    }
}
