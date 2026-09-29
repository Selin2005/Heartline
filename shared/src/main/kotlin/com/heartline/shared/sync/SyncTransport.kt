// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

import java.io.InputStream
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** An inbound message or stream from the paired device. */
data class Envelope(val path: String, val data: ByteArray)

/**
 * Transport between watch and phone. Implemented with the Wearable Data Layer on devices and
 * [InMemoryTransport] in tests. [send] is for small messages; [sendLarge] for waveforms
 * (ChannelClient on device).
 */
interface SyncTransport {
    val incoming: Flow<Envelope>

    /** @return true if a peer node received it. */
    suspend fun send(path: String, data: ByteArray): Boolean

    suspend fun sendLarge(path: String, data: ByteArray): Boolean = send(path, data)

    /** Streams [input] (closed afterwards) without holding it in memory, where the transport can. */
    suspend fun sendStream(path: String, input: InputStream): Boolean = sendLarge(path, input.use { it.readBytes() })
}

/** Two connected in-memory endpoints; set [connected] false to simulate the peer being away. */
class InMemoryTransport private constructor() : SyncTransport {
    private val inbox = MutableSharedFlow<Envelope>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
    private lateinit var peer: InMemoryTransport
    var connected = true

    override val incoming: Flow<Envelope> = inbox.asSharedFlow()

    override suspend fun send(path: String, data: ByteArray): Boolean {
        if (!connected || !peer.connected) return false
        peer.inbox.emit(Envelope(path, data))
        return true
    }

    companion object {
        fun pair(): Pair<InMemoryTransport, InMemoryTransport> {
            val a = InMemoryTransport()
            val b = InMemoryTransport()
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}
