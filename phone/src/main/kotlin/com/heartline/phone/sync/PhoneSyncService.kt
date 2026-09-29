// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.sync

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.heartline.datalayer.DataLayerTransport
import com.heartline.phone.di.APP_SCOPE
import com.heartline.shared.diag.RemoteLogs
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/** Receives records from the watch even when the phone UI isn't running. */
class PhoneSyncService : WearableListenerService() {
    private val engine: PhoneSyncEngine by inject()
    private val transport: DataLayerTransport by inject()
    private val remoteLogs: RemoteLogs by inject()
    // App-wide scope: a save must not be cancelled when this short-lived service is destroyed.
    private val scope: CoroutineScope by inject(APP_SCOPE)

    override fun onMessageReceived(event: MessageEvent) {
        val envelope = Envelope(event.path, event.data)
        scope.launch {
            transport.deliver(envelope)
            engine.handle(envelope)
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        scope.launch {
            if (channel.path.startsWith(Protocol.LOGS_SEGMENT_PREFIX)) {
                // A segment of the watch's log: streamed into a file, not read into memory.
                val requestId = channel.path.removePrefix(Protocol.LOGS_SEGMENT_PREFIX)
                transport.readChannelStream(channel) { remoteLogs.onSegment(requestId, it) }
            } else {
                engine.handle(Envelope(channel.path, transport.readChannel(channel)))
            }
        }
    }
}
