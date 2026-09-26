// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.datalayer

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.PeerDirectory
import com.heartline.shared.sync.PeerProbe
import com.heartline.shared.sync.SyncTransport
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
 */
class DataLayerTransport(context: Context, private val peerCapability: String) :
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
        Log.i(TAG, "received ${envelope.path} (${envelope.data.size} B)")
        inbox.emit(envelope)
    }

    /** Nodes running the other Heartline app. */
    suspend fun peers(): Set<Node> = runCatching {
        capabilities.getCapability(peerCapability, CapabilityClient.FILTER_REACHABLE).await().nodes
    }.onFailure { Log.w(TAG, "capability lookup '$peerCapability' failed", it) }
        .getOrDefault(emptySet())
        .also { found -> Log.i(TAG, "capability '$peerCapability' -> ${found.joinToString { "${it.displayName}(${it.id})" }.ifEmpty { "none" }}") }

    /** Every device connected over Bluetooth/Wi-Fi, Heartline or not. */
    suspend fun connectedNodes(): List<Node> = runCatching { nodes.connectedNodes.await() }
        .onFailure { Log.w(TAG, "connectedNodes failed", it) }
        .getOrDefault(emptyList())
        .also { found -> Log.i(TAG, "connected nodes -> ${found.joinToString { "${it.displayName}(${it.id}, nearby=${it.isNearby})" }.ifEmpty { "none" }}") }

    override suspend fun probe(): PeerProbe = when {
        peers().isNotEmpty() -> PeerProbe.REACHABLE
        connectedNodes().isNotEmpty() -> PeerProbe.APP_MISSING
        else -> PeerProbe.NO_DEVICE
    }

    /** First peer running Heartline, for remote activity launches. */
    suspend fun peerNodeId(): String? = peers().firstOrNull()?.id

    override suspend fun send(path: String, data: ByteArray): Boolean {
        val targets = peers()
        if (targets.isEmpty()) {
            Log.w(TAG, "send $path: no peer")
            return false
        }
        return targets.map { node ->
            runCatching { messages.sendMessage(node.id, path, data).await() }
                .onSuccess { Log.i(TAG, "sent $path (${data.size} B) to ${node.displayName}") }
                .onFailure { Log.w(TAG, "sendMessage $path to ${node.displayName} failed", it) }
                .isSuccess
        }.any { it }
    }

    override suspend fun sendLarge(path: String, data: ByteArray): Boolean {
        val node = peers().firstOrNull() ?: return false.also { Log.w(TAG, "sendLarge $path: no peer") }
        return runCatching {
            val channel = channels.openChannel(node.id, path).await()
            try {
                withContext(Dispatchers.IO) {
                    channels.getOutputStream(channel).await().use { it.write(data) }
                }
            } finally {
                channels.close(channel)
            }
        }.onSuccess { Log.i(TAG, "streamed $path (${data.size} B)") }
            .onFailure { Log.w(TAG, "channel $path failed", it) }
            .isSuccess
    }

    /** Reads a whole inbound channel (called from WearableListenerService.onChannelOpened). */
    suspend fun readChannel(channel: ChannelClient.Channel): ByteArray = withContext(Dispatchers.IO) {
        channels.getInputStream(channel).await().use { it.readBytes() }.also {
            channels.close(channel)
            Log.i(TAG, "received stream ${channel.path} (${it.size} B)")
        }
    }

    companion object {
        const val TAG = "Heartline/Sync"
    }
}
