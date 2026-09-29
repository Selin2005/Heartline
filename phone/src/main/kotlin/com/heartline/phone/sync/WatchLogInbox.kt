// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.sync

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.diag.WatchLogArchive
import com.heartline.shared.sync.ArchiveAck
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Takes the log segments and raw sensor sessions the watch moves here, keeps them in the
 * [archive] and tells the watch it may delete its copy ([ack]).
 */
class WatchLogInbox(private val archive: WatchLogArchive, private val ack: suspend (ArchiveAck) -> Boolean) {
    suspend fun receive(type: String, name: String, input: InputStream) {
        val size = runCatching { withContext(Dispatchers.IO) { archive.receive(type, name, input) } }
            .onFailure { HLog.w(TAG, "watch log $type/$name not stored", it) }
            .getOrNull() ?: return
        HLog.i(TAG, "stored watch $type/$name ($size B)")
        ack(ArchiveAck(type, name, size))
    }

    private companion object {
        const val TAG = "Heartline/Diag"
    }
}
