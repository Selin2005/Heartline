// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

import com.heartline.shared.sync.LogRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phone side of fetching the watch's log: sends a [LogRequest] and waits for the reply that
 * arrives through [onLogs] (a separate channel transfer), or gives up after the timeout.
 */
class RemoteLogs(private val send: suspend (LogRequest) -> Boolean, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

    /** The watch's log file, or null when the watch didn't answer in time. */
    suspend fun fetch(timeoutMs: Long = 30_000): ByteArray? {
        val id = newId()
        val reply = CompletableDeferred<ByteArray>()
        pending[id] = reply
        return try {
            if (!send(LogRequest(id))) null else withTimeoutOrNull(timeoutMs) { reply.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Asks the watch to erase its log. */
    suspend fun delete(): Boolean = send(LogRequest(newId(), delete = true))

    fun onLogs(requestId: String, text: ByteArray) {
        pending[requestId]?.complete(text)
    }
}

/** The layout of an exported log file: a header, the persistent log, then the process logcat. */
object LogBundle {
    fun build(header: Map<String, String>, persistent: String, logcat: String, note: String? = null): String = buildString {
        appendLine("Heartline diagnostic log")
        appendLine("=".repeat(40))
        val width = header.keys.maxOfOrNull { it.length } ?: 0
        header.forEach { (k, v) -> appendLine("${k.padEnd(width)} : $v") }
        note?.let {
            appendLine()
            appendLine("NOTE: $it")
        }
        appendLine()
        appendLine("=== Heartline log (kept on the device) ===")
        appendLine(persistent.ifBlank { "(empty: diagnostic logs are off or nothing was logged yet)" })
        appendLine()
        appendLine("=== logcat (this app's process) ===")
        appendLine(logcat.ifBlank { "(empty)" })
    }
}
