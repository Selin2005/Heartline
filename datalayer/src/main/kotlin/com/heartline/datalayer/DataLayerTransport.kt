// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.datalayer

import com.heartline.datalayer.diag.HLog
import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.PeerBackoff
import com.heartline.shared.sync.PeerDirectory
import com.heartline.shared.sync.PeerProbe
import com.heartline.shared.sync.SyncTransport
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * [SyncTransport] over the Wearable Data Layer. Small payloads use MessageClient; waveforms use
 * ChannelClient streams. Inbound data is pushed in by the app's WearableListenerService through
 * [deliver], so it arrives even when no UI is running.
 *
 * Everything is logged under [TAG] (successes too) so a device log shows exactly where a link breaks.
 *
 * @param peerCapability capability advertised by the other app (see Protocol.CAPABILITY_*).
 * @param backoff when set, routine sends stop looking the other app up for a while after it
 *   wasn't reachable (the watch: it sends on every background heart-rate delivery).
 */
class DataLayerTransport(context: Context, private val peerCapability: String, private val backoff: PeerBackoff? = null) :
    SyncTransport,
    PeerDirectory {
    private val appContext = context.applicationContext
    private val messages = Wearable.getMessageClient(appContext)
    private val channels = Wearable.getChannelClient(appContext)
    private val capabilities = Wearable.getCapabilityClient(appContext)
    private val nodes = Wearable.getNodeClient(appContext)
    private val inbox = MutableSharedFlow<Envelope>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)

    override val incoming: Flow<Envelope> = inbox.asSharedFlow()

    suspend fun deliver(envelope: Envelope) {
        backoff?.reset()
        HLog.i(TAG, "received ${envelope.path} (${envelope.data.size} B)")
        inbox.emit(envelope)
    }

    /** Nodes running the other Heartline app (looked up now, whatever the [backoff]). */
    suspend fun peers(): Set<Node> = lookup(routine = false).orEmpty()

    /** null: a [routine] lookup while the [backoff] waits (nothing asked, nothing logged). */
    private suspend fun lookup(routine: Boolean): Set<Node>? {
        if (routine && backoff?.shouldTry() == false) return null
        return find().also { backoff?.onLookup(it.isNotEmpty()) }
    }

    private suspend fun find(): Set<Node> = runCatching {
        capabilities.getCapability(peerCapability, CapabilityClient.FILTER_REACHABLE).await().nodes
    }.onFailure { HLog.w(TAG, "capability lookup '$peerCapability' failed", it) }
        .getOrDefault(emptySet())
        .also { found ->
            // Looked up for every message: logged only when the answer changes (it was most of the log).
            val text = found.joinToString { "${it.displayName}(${it.id})" }.ifEmpty { "none" }
            if (text != lastPeers) HLog.i(TAG, "capability '$peerCapability' -> $text")
            lastPeers = text
        }

    @Volatile private var lastPeers: String? = null

    /** Every device connected over Bluetooth/Wi-Fi, Heartline or not. */
    suspend fun connectedNodes(): List<Node> = runCatching { nodes.connectedNodes.await() }
        .onFailure { HLog.w(TAG, "connectedNodes failed", it) }
        .getOrDefault(emptyList())
        .also { found -> HLog.i(TAG, "connected nodes -> ${found.joinToString { "${it.displayName}(${it.id}, nearby=${it.isNearby})" }.ifEmpty { "none" }}") }

    override suspend fun probe(): PeerProbe = when {
        peers().isNotEmpty() -> PeerProbe.REACHABLE
        connectedNodes().isNotEmpty() -> PeerProbe.APP_MISSING
        else -> PeerProbe.NO_DEVICE
    }

    /** First peer running Heartline, for remote activity launches. */
    suspend fun peerNodeId(): String? = peers().firstOrNull()?.id

    override suspend fun send(path: String, data: ByteArray): Boolean {
        val targets = lookup(routine = true) ?: return false
        if (targets.isEmpty()) {
            HLog.w(TAG, "send $path: no peer${nextLookup()}")
            return false
        }
        return targets.map { node ->
            runCatching { messages.sendMessage(node.id, path, data).await() }
                .onSuccess { HLog.i(TAG, "sent $path (${data.size} B) to ${node.displayName}") }
                .onFailure { HLog.w(TAG, "sendMessage $path to ${node.displayName} failed", it) }
                .isSuccess
        }.any { it }
    }

    override suspend fun sendLarge(path: String, data: ByteArray): Boolean {
        val node = (lookup(routine = true) ?: return false).firstOrNull() ?: return false.also { HLog.w(TAG, "sendLarge $path: no peer${nextLookup()}") }
        return runCatching {
            val channel = channels.openChannel(node.id, path).await()
            try {
                withContext(Dispatchers.IO) {
                    channels.getOutputStream(channel).await().use { it.write(data) }
                }
            } finally {
                channels.close(channel)
            }
        }.onSuccess { HLog.i(TAG, "streamed $path (${data.size} B)") }
            .onFailure { HLog.w(TAG, "channel $path failed", it) }
            .isSuccess
    }

    /** Streams [input] on a channel without holding it in memory (the watch's log segments). */
    override suspend fun sendStream(path: String, input: InputStream): Boolean {
        val found = lookup(routine = true) ?: return false.also { input.close() }
        val node = found.firstOrNull() ?: return false.also {
            input.close()
            HLog.w(TAG, "sendStream $path: no peer${nextLookup()}")
        }
        var sent = 0L
        return runCatching {
            val channel = channels.openChannel(node.id, path).await()
            try {
                withContext(Dispatchers.IO) {
                    input.use { from ->
                        channels.getOutputStream(channel).await().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = from.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                sent += n
                            }
                        }
                    }
                }
            } finally {
                channels.close(channel)
            }
        }.onSuccess { HLog.i(TAG, "streamed $path ($sent B)") }
            .onFailure { HLog.w(TAG, "stream $path failed after $sent B", it) }
            .isSuccess
    }

    /**
     * Hands an inbound channel's stream to [read] (called from WearableListenerService
     * .onChannelOpened for large transfers that shouldn't be held in memory), then closes it.
     */
    suspend fun readChannelStream(channel: ChannelClient.Channel, read: suspend (InputStream) -> Unit) {
        backoff?.reset()
        try {
            val input = channels.getInputStream(channel).await()
            HLog.i(TAG, "receiving stream ${channel.path}")
            read(input)
        } finally {
            channels.close(channel)
        }
    }

    /** Reads a whole inbound channel (called from WearableListenerService.onChannelOpened). */
    suspend fun readChannel(channel: ChannelClient.Channel): ByteArray = withContext(Dispatchers.IO) {
        backoff?.reset()
        channels.getInputStream(channel).await().use { it.readBytes() }.also {
            channels.close(channel)
            HLog.i(TAG, "received stream ${channel.path} (${it.size} B)")
        }
    }

    private fun nextLookup(): String = backoff?.waitLeftMs()?.takeIf { it > 0 }?.let { ", next look in ${it / 1000} s" }.orEmpty()

    companion object {
        const val TAG = "Heartline/Sync"
    }
}
