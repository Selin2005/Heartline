// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

import com.heartline.shared.sync.LogManifest
import com.heartline.shared.sync.LogRequest
import com.heartline.shared.sync.LogSegment
import com.heartline.shared.sync.Protocol
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString

/**
 * Phone side of fetching the watch's log, piece by piece: first the [LogManifest] (header and
 * segment list), then each segment on its own stream into a file. A segment that stalls (no bytes
 * for [idleMs]) or arrives incomplete is asked for again, [attempts] times in all, so a long log
 * over Bluetooth gets through however long it takes, and a lost connection costs one segment.
 */
class RemoteLogs(
    private val send: suspend (LogRequest) -> Boolean,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val idleMs: Long = 60_000,
    private val attempts: Int = 3,
    private val pollMs: Long = 1_000,
    /** Where segment files are written (tests run it on their own dispatcher). */
    private val io: CoroutineContext = Dispatchers.IO
) {
    /** What the watch answered to a manifest request. */
    sealed interface Answer {
        data class Manifest(val manifest: LogManifest) : Answer

        /** A watch from before segmented export sent its whole log as text. */
        data class WholeLog(val text: ByteArray) : Answer
    }

    private class Download(val target: File) {
        val bytes = AtomicLong(0)
        val started = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Long>()

        @Volatile var cancelled = false
    }

    private val replies = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()
    private val downloads = ConcurrentHashMap<String, Download>()

    /** The watch's header and segment list, its whole log (older watches), or null without an answer. */
    suspend fun manifest(timeoutMs: Long = idleMs): Answer? {
        repeat(attempts) {
            val bytes = request(LogRequest(newId(), manifest = true), timeoutMs) ?: return@repeat
            val manifest = runCatching { Protocol.json.decodeFromString<LogManifest>(bytes.decodeToString()) }.getOrNull()
            return if (manifest != null) Answer.Manifest(manifest) else Answer.WholeLog(bytes)
        }
        return null
    }

    /**
     * Downloads [segment] into [target], retrying a stalled or incomplete transfer.
     * [onBytes] reports the bytes received so far in the current attempt.
     */
    suspend fun download(segment: LogSegment, target: File, onBytes: (Long) -> Unit = {}): Boolean {
        repeat(attempts) {
            if (downloadOnce(segment, target, onBytes)) return true
        }
        target.delete()
        return false
    }

    private suspend fun downloadOnce(segment: LogSegment, target: File, onBytes: (Long) -> Unit): Boolean {
        val id = newId()
        val download = Download(target)
        downloads[id] = download
        try {
            if (!send(LogRequest(id, segment = segment.name))) return false
            if (withTimeoutOrNull(idleMs) { download.started.await() } == null) return false
            var last = -1L
            var idle = 0L
            while (!download.done.isCompleted) {
                delay(pollMs)
                val now = download.bytes.get()
                onBytes(now)
                if (now == last) idle += pollMs else idle = 0
                last = now
                if (idle >= idleMs) {
                    download.cancelled = true
                    return false
                }
            }
            val received = runCatching { download.done.await() }.getOrNull() ?: return false
            onBytes(received)
            return received == segment.size
        } finally {
            downloads.remove(id)
        }
    }

    /** Asks the watch to erase its log. */
    suspend fun delete(): Boolean = send(LogRequest(newId(), delete = true))

    /** A small reply (manifest, or a whole log from an older watch). */
    fun onLogs(requestId: String, text: ByteArray) {
        replies[requestId]?.complete(text)
    }

    /** A segment stream: copied into the waiting download's file, whatever its size. */
    suspend fun onSegment(requestId: String, input: InputStream) {
        val download = downloads[requestId] ?: return input.close()
        download.started.complete(Unit)
        val result = runCatching {
            withContext(io) {
                input.use { stream ->
                    download.target.outputStream().buffered().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (download.cancelled) throw IOException("stalled")
                            val n = stream.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            download.bytes.addAndGet(n.toLong())
                        }
                    }
                }
                download.bytes.get()
            }
        }
        result.onSuccess { download.done.complete(it) }.onFailure { download.done.completeExceptionally(it) }
    }

    private suspend fun request(request: LogRequest, timeoutMs: Long): ByteArray? {
        val reply = CompletableDeferred<ByteArray>()
        replies[request.requestId] = reply
        return try {
            if (!send(request)) null else withTimeoutOrNull(timeoutMs) { reply.await() }
        } finally {
            replies.remove(request.requestId)
        }
    }
}

/** The layout of an exported log file: a header, the kept log, then the process logcat. */
object LogBundle {
    const val LOG_TITLE = "=== Heartline log (kept on the device) ==="
    const val LOGCAT_TITLE = "=== logcat (this app's process) ==="

    /** The header lines, up to and including the log's title. */
    fun head(header: Map<String, String>, note: String? = null): String = buildString {
        appendLine("Heartline diagnostic log")
        appendLine("=".repeat(40))
        val width = header.keys.maxOfOrNull { it.length } ?: 0
        header.forEach { (k, v) -> appendLine("${k.padEnd(width)} : $v") }
        note?.let {
            appendLine()
            appendLine("NOTE: $it")
        }
        appendLine()
        appendLine(LOG_TITLE)
    }

    fun build(header: Map<String, String>, persistent: String, logcat: String, note: String? = null): String = buildString {
        append(head(header, note))
        appendLine(persistent.ifBlank { "(empty: diagnostic logs are off or nothing was logged yet)" })
        appendLine()
        appendLine(LOGCAT_TITLE)
        appendLine(logcat.ifBlank { "(empty)" })
    }
}

/** "412 KB", "18.3 MB". */
fun formatLogSize(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
    else -> "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
}
