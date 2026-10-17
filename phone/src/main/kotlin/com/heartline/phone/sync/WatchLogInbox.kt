// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.sync

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.diag.IdleTimeoutInputStream
import com.heartline.shared.diag.WatchLogArchive
import com.heartline.shared.sync.ArchiveAck
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Takes the log segments and raw sensor sessions the watch moves here, keeps them in the
 * [archive] and tells the watch it may delete its copy ([ack]).
 */
class WatchLogInbox(
    private val archive: WatchLogArchive,
    private val idleMs: Long = IDLE_MS,
    private val ackRetryMs: Long = ACK_RETRY_MS,
    private val ack: suspend (ArchiveAck) -> Boolean
) {
    suspend fun receive(type: String, name: String, input: InputStream) {
        val watched = IdleTimeoutInputStream(input, idleMs)
        val size = runCatching { withContext(Dispatchers.IO) { archive.receive(type, name, watched) } }
            .onFailure { HLog.w(TAG, "watch log $type/$name not stored${if (watched.timedOut) " (stream stalled)" else ""}", it) }
            .getOrNull() ?: return
        HLog.i(TAG, "stored watch $type/$name ($size B)")
        // Without the ack the watch keeps its copy and streams it again in 5 minutes.
        repeat(ACK_TRIES) { attempt ->
            if (ack(ArchiveAck(type, name, size))) return
            if (attempt < ACK_TRIES - 1) delay(ackRetryMs * (attempt + 1))
        }
    }

    private companion object {
        const val TAG = "Heartline/Diag"
        const val IDLE_MS = 60_000L
        const val ACK_TRIES = 3
        const val ACK_RETRY_MS = 5_000L
    }
}
