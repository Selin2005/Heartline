package com.heartline.datalayer

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.heartline.shared.sync.Envelope
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
 * @param peerCapability capability advertised by the other app (see Protocol.CAPABILITY_*).
 */
class DataLayerTransport(context: Context, private val peerCapability: String) : SyncTransport {
    private val appContext = context.applicationContext
    private val messages = Wearable.getMessageClient(appContext)
    private val channels = Wearable.getChannelClient(appContext)
    private val capabilities = Wearable.getCapabilityClient(appContext)
    private val inbox = MutableSharedFlow<Envelope>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)

    override val incoming: Flow<Envelope> = inbox.asSharedFlow()

    suspend fun deliver(envelope: Envelope) = inbox.emit(envelope)

    suspend fun peers(): Set<Node> = runCatching {
        capabilities.getCapability(peerCapability, CapabilityClient.FILTER_REACHABLE).await().nodes
    }.onFailure { Log.w(TAG, "Capability lookup failed", it) }.getOrDefault(emptySet())

    override suspend fun send(path: String, data: ByteArray): Boolean {
        val nodes = peers()
        if (nodes.isEmpty()) return false
        return nodes.map { node ->
            runCatching { messages.sendMessage(node.id, path, data).await() }
                .onFailure { Log.w(TAG, "sendMessage $path failed", it) }
                .isSuccess
        }.any { it }
    }

    override suspend fun sendLarge(path: String, data: ByteArray): Boolean {
        val node = peers().firstOrNull() ?: return false
        return runCatching {
            val channel = channels.openChannel(node.id, path).await()
            try {
                withContext(Dispatchers.IO) {
                    channels.getOutputStream(channel).await().use { it.write(data) }
                }
            } finally {
                channels.close(channel)
            }
        }.onFailure { Log.w(TAG, "channel $path failed", it) }.isSuccess
    }

    /** Reads a whole inbound channel (called from WearableListenerService.onChannelOpened). */
    suspend fun readChannel(channel: ChannelClient.Channel): ByteArray = withContext(Dispatchers.IO) {
        channels.getInputStream(channel).await().use { it.readBytes() }.also { channels.close(channel) }
    }

    private companion object {
        const val TAG = "HeartlineSync"
    }
}
