// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.profile.UserProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/** A finished measurement waiting on the watch until the phone acknowledges it. */
data class OutboxItem(val meta: RecordMeta, val wave: FloatArray?)

/** A small JSON message (HR batch, alert) that must reach the phone; acked by [id]. */
data class PendingMessage(val id: String, val path: String, val payload: ByteArray)

/** Watch-side persistence of unsent records (Room in the app, in-memory in tests). */
interface Outbox {
    suspend fun pending(): List<OutboxItem>

    suspend fun pendingMessages(): List<PendingMessage> = emptyList()

    /** Raw BP session logs (id, encoded bytes) waiting for the phone. */
    suspend fun pendingSessions(): List<Pair<String, ByteArray>> = emptyList()

    /** Called for acked records and messages alike. */
    suspend fun markDelivered(id: String)
}

/** Phone-side sink for heart-rate trends and alerts. */
interface HeartDataSink {
    suspend fun saveBatch(batch: HrBatch)

    suspend fun saveAlert(alert: HealthAlert)
}

/** Phone-side persistence of received records. */
interface RecordSink {
    suspend fun contains(id: String): Boolean

    suspend fun save(meta: RecordMeta, wave: FloatArray?)

    suspend fun delete(id: String)
}

/**
 * Watch side of the protocol: pushes every pending record (meta, then waveform) and removes it
 * from the outbox when the phone acks. Sending is idempotent, so a retry after a lost ack is safe.
 */
class WatchSyncEngine(
    private val transport: SyncTransport,
    private val outbox: Outbox,
    private val onRemoteDelete: suspend (String) -> Unit = {},
    private val onSettings: suspend (MonitorSettings) -> Unit = {},
    private val onCalibration: suspend (BpCalibration?) -> Unit = {},
    private val onCaptureRequest: suspend (CaptureRequest) -> Unit = {},
    private val onProfile: suspend (UserProfile) -> Unit = {},
    private val onOpen: suspend (String) -> Unit = {},
    private val onStatus: suspend (PhoneStatus) -> Unit = {},
    private val onLogRequest: suspend (LogRequest) -> Unit = {}
) {
    /** Watch → phone: the diagnostic log the phone asked for with [LogRequest]. */
    suspend fun sendLogs(requestId: String, text: ByteArray): Boolean = transport.sendLarge(Protocol.logsPath(requestId), text)

    /** Watch → phone: settings changed on the watch (the phone keeps the newer copy). */
    suspend fun sendSettings(settings: MonitorSettings): Boolean =
        transport.send(Protocol.SETTINGS, Protocol.json.encodeToString(settings).encodeToByteArray())

    /** @return number of records handed to the transport. */
    suspend fun flush(): Int {
        var sent = 0
        for (item in outbox.pending()) {
            val metaSent = transport.send(Protocol.RECORD_META, Protocol.json.encodeToString(item.meta).encodeToByteArray())
            if (!metaSent) break
            if (item.wave != null) {
                val bytes = WaveCodec.encode(item.meta.kind.ordinal, item.meta.sampleRateHz, item.wave)
                if (!transport.sendLarge(Protocol.wavePath(item.meta.id), bytes)) break
            }
            sent++
        }
        for (message in outbox.pendingMessages()) {
            if (!transport.send(message.path, message.payload)) break
            sent++
        }
        for ((id, bytes) in outbox.pendingSessions()) {
            if (!transport.sendLarge(Protocol.sessionPath(id), bytes)) break
            sent++
        }
        return sent
    }

    /** Malformed or unknown messages are dropped rather than crashing the listener service. */
    suspend fun handle(envelope: Envelope) {
        runCatching { handleOrThrow(envelope) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    private suspend fun handleOrThrow(envelope: Envelope) {
        when (envelope.path) {
            Protocol.RECORD_ACK -> {
                val ack = Protocol.json.decodeFromString<Ack>(envelope.data.decodeToString())
                if (ack.ok) outbox.markDelivered(ack.id)
            }
            Protocol.DELETE -> onRemoteDelete(Protocol.json.decodeFromString<DeleteRecord>(envelope.data.decodeToString()).id)
            Protocol.SETTINGS -> onSettings(Protocol.json.decodeFromString<MonitorSettings>(envelope.data.decodeToString()))
            Protocol.BP_CALIBRATION -> onCalibration(
                envelope.data.decodeToString().takeIf { it != "null" }?.let { Protocol.json.decodeFromString<BpCalibration>(it) }
            )
            Protocol.OPEN -> onOpen(envelope.data.decodeToString())
            Protocol.STATUS -> onStatus(Protocol.json.decodeFromString<PhoneStatus>(envelope.data.decodeToString()))
            Protocol.PROFILE -> onProfile(Protocol.json.decodeFromString<UserProfile>(envelope.data.decodeToString()))
            Protocol.BP_CALIBRATION_CAPTURE -> onCaptureRequest(
                Protocol.json.decodeFromString<CaptureRequest>(envelope.data.decodeToString())
            )
            Protocol.LOGS_REQUEST -> onLogRequest(Protocol.json.decodeFromString<LogRequest>(envelope.data.decodeToString()))
        }
    }
}

/**
 * Phone side: joins meta + waveform (either may arrive first), saves once, and acks. Records
 * already stored are acked again without re-saving (idempotency on [RecordMeta.id]).
 */
class PhoneSyncEngine(
    private val transport: SyncTransport,
    private val sink: RecordSink,
    private val heart: HeartDataSink? = null,
    private val onHello: suspend (Hello) -> Unit = {},
    private val onCaptureResult: suspend (CaptureResult) -> Unit = {},
    private val onSetupRequest: suspend (SetupRequest) -> Unit = {},
    private val onSettings: suspend (MonitorSettings) -> Unit = {},
    /** A raw BP session log arrived (id, encoded bytes); acked after it returns. */
    private val onSessionLog: suspend (String, ByteArray) -> Unit = { _, _ -> },
    private val onLogs: suspend (requestId: String, text: ByteArray) -> Unit = { _, _ -> }
) {
    private val metas = mutableMapOf<String, RecordMeta>()
    private val waves = mutableMapOf<String, FloatArray>()
    private val lock = Mutex()

    /** Safe to call concurrently (listener callbacks arrive on arbitrary threads). */
    suspend fun handle(envelope: Envelope) = lock.withLock {
        runCatching { handleLocked(envelope) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        Unit
    }

    private suspend fun handleLocked(envelope: Envelope) {
        when {
            envelope.path == Protocol.RECORD_META -> {
                val meta = Protocol.json.decodeFromString<RecordMeta>(envelope.data.decodeToString())
                if (sink.contains(meta.id)) {
                    ack(meta.id)
                } else {
                    metas[meta.id] = meta
                    tryComplete(meta.id)
                }
            }
            envelope.path == Protocol.HR_BATCH -> {
                val batch = Protocol.json.decodeFromString<HrBatch>(envelope.data.decodeToString())
                heart?.saveBatch(batch)
                ack(batch.id)
            }
            envelope.path == Protocol.ALERT -> {
                val alert = Protocol.json.decodeFromString<HealthAlert>(envelope.data.decodeToString())
                heart?.saveAlert(alert)
                ack(alert.id)
            }
            envelope.path == Protocol.HELLO -> onHello(Protocol.json.decodeFromString<Hello>(envelope.data.decodeToString()))
            envelope.path == Protocol.SETTINGS -> onSettings(
                Protocol.json.decodeFromString<MonitorSettings>(envelope.data.decodeToString())
            )
            envelope.path == Protocol.SETUP_REQUEST -> onSetupRequest(
                Protocol.json.decodeFromString<SetupRequest>(envelope.data.decodeToString())
            )
            envelope.path == Protocol.BP_CALIBRATION_CAPTURE -> {
                val result = Protocol.json.decodeFromString<CaptureResult>(envelope.data.decodeToString())
                onCaptureResult(result)
                ack(result.id)
            }
            envelope.path.startsWith(Protocol.LOGS_PREFIX) -> onLogs(envelope.path.removePrefix(Protocol.LOGS_PREFIX), envelope.data)
            envelope.path.startsWith(Protocol.BP_SESSION_PREFIX) -> {
                val id = envelope.path.removePrefix(Protocol.BP_SESSION_PREFIX)
                onSessionLog(id, envelope.data)
                ack(id)
            }
            envelope.path.startsWith(Protocol.RECORD_WAVE_PREFIX) -> {
                val id = envelope.path.removePrefix(Protocol.RECORD_WAVE_PREFIX)
                if (sink.contains(id)) return
                waves[id] = WaveCodec.decode(envelope.data).samples
                tryComplete(id)
            }
        }
    }

    suspend fun sendCalibration(calibration: BpCalibration?): Boolean =
        transport.send(Protocol.BP_CALIBRATION, Protocol.json.encodeToString(calibration).encodeToByteArray())

    suspend fun requestCapture(request: CaptureRequest): Boolean =
        transport.send(Protocol.BP_CALIBRATION_CAPTURE, Protocol.json.encodeToString(request).encodeToByteArray())

    /** Asks the watch to open a measurement screen (it shows a tap-to-open notification). */
    suspend fun openOnWatch(route: String): Boolean = transport.send(Protocol.OPEN, route.encodeToByteArray())

    suspend fun sendProfile(profile: UserProfile): Boolean =
        transport.send(Protocol.PROFILE, Protocol.json.encodeToString(profile).encodeToByteArray())

    /** Phone → watch: send back (or erase) your diagnostic log. */
    suspend fun requestLogs(request: LogRequest): Boolean =
        transport.send(Protocol.LOGS_REQUEST, Protocol.json.encodeToString(request).encodeToByteArray())

    suspend fun sendStatus(status: PhoneStatus): Boolean =
        transport.send(Protocol.STATUS, Protocol.json.encodeToString(status).encodeToByteArray())

    suspend fun sendSettings(settings: MonitorSettings): Boolean =
        transport.send(Protocol.SETTINGS, Protocol.json.encodeToString(settings).encodeToByteArray())

    suspend fun requestDelete(id: String) {
        sink.delete(id)
        transport.send(Protocol.DELETE, Protocol.json.encodeToString(DeleteRecord(id)).encodeToByteArray())
    }

    private suspend fun tryComplete(id: String) {
        val meta = metas[id] ?: return
        val wave = waves[id]
        if (meta.sampleCount > 0 && wave == null) return
        sink.save(meta, wave)
        metas.remove(id)
        waves.remove(id)
        ack(id)
    }

    private suspend fun ack(id: String) {
        transport.send(Protocol.RECORD_ACK, Protocol.json.encodeToString(Ack(id, ok = true)).encodeToByteArray())
    }
}
