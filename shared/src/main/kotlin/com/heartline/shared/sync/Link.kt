// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString

/** What the transport can see of the other device before any message is exchanged. */
enum class PeerProbe {
    /** No phone is connected to the watch at all (Bluetooth off, out of range, not paired). */
    NO_DEVICE,

    /** A phone is connected, but it doesn't advertise the Heartline capability: app not installed. */
    APP_MISSING,

    /** Heartline is installed on a connected phone. */
    REACHABLE
}

interface PeerDirectory {
    suspend fun probe(): PeerProbe
}

enum class LinkStage { CHECKING, NO_DEVICE, APP_MISSING, NO_RESPONSE, INCOMPATIBLE, CONNECTED }

/** A [PhoneStatus] and when it arrived (watch clock). */
data class StampedStatus(val status: PhoneStatus, val receivedAtMs: Long)

data class LinkState(val stage: LinkStage, val status: PhoneStatus? = null)

/**
 * Watch-side handshake: find the phone, say hello, and wait for a fresh [PhoneStatus].
 * [latest] is fed by the sync engine's onStatus callback.
 */
class WatchLinkChecker(
    private val directory: PeerDirectory,
    private val transport: SyncTransport,
    private val latest: StateFlow<StampedStatus?>,
    private val hello: () -> Hello,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 8_000,
    private val log: (String) -> Unit = {}
) {
    suspend fun check(): LinkState {
        val probe = directory.probe()
        log("probe=$probe")
        when (probe) {
            PeerProbe.NO_DEVICE -> return LinkState(LinkStage.NO_DEVICE)
            PeerProbe.APP_MISSING -> return LinkState(LinkStage.APP_MISSING)
            PeerProbe.REACHABLE -> Unit
        }
        val askedAt = clock()
        val sent = transport.send(Protocol.HELLO, Protocol.json.encodeToString(hello()).encodeToByteArray())
        log("hello sent=$sent")
        if (!sent) return LinkState(LinkStage.NO_RESPONSE)
        val reply = withTimeoutOrNull(timeoutMs) { latest.first { it != null && it.receivedAtMs >= askedAt } }
        log("reply=${reply?.status}")
        return when {
            reply == null -> LinkState(LinkStage.NO_RESPONSE)
            !reply.status.isCompatible() -> LinkState(LinkStage.INCOMPATIBLE, reply.status)
            else -> LinkState(LinkStage.CONNECTED, reply.status)
        }
    }
}
