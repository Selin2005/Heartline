// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.data

import com.heartline.shared.bp.BpSessionLog
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PendingMessage
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.WaveCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

/** Stores finished measurements and serves them to the sync engine as the outbox. */
class WatchRecordStore(
    private val dao: WatchRecordDao,
    private val root: File,
    private val messages: MessageDao? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : Outbox {
    val recent: Flow<List<RecordMeta>> = dao.recent().map { list -> list.map { Protocol.json.decodeFromString<RecordMeta>(it.metaJson) } }

    /** A longer history (newest first) for streaks and the user's usual ranges. */
    val history: Flow<List<RecordMeta>> = dao.recent(HISTORY).map { list -> list.map { Protocol.json.decodeFromString<RecordMeta>(it.metaJson) } }

    suspend fun add(meta: RecordMeta, wave: FloatArray?) {
        val path = wave?.let {
            withContext(Dispatchers.IO) {
                val relative = "outbox/${meta.id}.bin"
                File(root, relative).apply { parentFile?.mkdirs() }.writeBytes(WaveCodec.encode(meta.kind.ordinal, meta.sampleRateHz, it))
                relative
            }
        }
        dao.insert(WatchRecordEntity(meta.id, Protocol.json.encodeToString(meta), meta.startedAtMs, path))
    }

    override suspend fun pending(): List<OutboxItem> = dao.pending().map { entity ->
        val wave = entity.wavePath?.let { path ->
            withContext(Dispatchers.IO) { File(root, path).takeIf { it.exists() }?.let { WaveCodec.decode(it.readBytes()).samples } }
        }
        OutboxItem(Protocol.json.decodeFromString(entity.metaJson), wave)
    }

    suspend fun enqueueMessage(id: String, path: String, payload: ByteArray) {
        messages?.insert(MessageEntity(id, path, payload, now()))
        messages?.deleteOlderThan(now() - 7 * 24 * 3_600_000L)
    }

    override suspend fun pendingMessages(): List<PendingMessage> =
        messages?.pending().orEmpty().map { PendingMessage(it.id, it.path, it.payload) }

    /**
     * Keeps a raw BP session log until the phone has it (see [BpSessionLog]). Oldest logs are
     * dropped beyond [MAX_SESSION_BYTES] so a watch that is never synced can't fill up.
     */
    suspend fun addSession(log: BpSessionLog) = withContext(Dispatchers.IO) {
        val dir = File(root, SESSIONS).apply { mkdirs() }
        File(dir, "${log.header.id}.hlbp").writeBytes(log.encode())
        val files = dir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_SESSION_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    override suspend fun pendingSessions(): List<Pair<String, ByteArray>> = withContext(Dispatchers.IO) {
        File(root, SESSIONS).listFiles().orEmpty().filter { it.extension == "hlbp" }.sortedBy { it.lastModified() }
            .map { it.nameWithoutExtension to it.readBytes() }
    }

    override suspend fun markDelivered(id: String) {
        withContext(Dispatchers.IO) { File(File(root, SESSIONS), "$id.hlbp").delete() }
        messages?.delete(id)
        dao.get(id)?.wavePath?.let { withContext(Dispatchers.IO) { File(root, it).delete() } }
        dao.markDelivered(id)
        dao.prune()
    }

    suspend fun delete(id: String) {
        dao.get(id)?.wavePath?.let { withContext(Dispatchers.IO) { File(root, it).delete() } }
        dao.delete(id)
    }

    companion object {
        const val HISTORY = 400
        const val SESSIONS = "bp-sessions"
        const val MAX_SESSION_BYTES = 64L * 1024 * 1024
    }
}
