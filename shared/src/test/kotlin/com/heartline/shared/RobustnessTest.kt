// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.model.RecordMeta
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.WatchSyncEngine
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Garbage from the other device must never crash a listener service. */
class RobustnessTest {
    private val paths = listOf(
        Protocol.RECORD_META,
        Protocol.wavePath("x"),
        Protocol.RECORD_ACK,
        Protocol.HR_BATCH,
        Protocol.ALERT,
        Protocol.SETTINGS,
        Protocol.PROFILE,
        Protocol.BP_CALIBRATION,
        Protocol.BP_CALIBRATION_CAPTURE,
        Protocol.DELETE,
        Protocol.OPEN,
        Protocol.MEASURE_STATE,
        Protocol.MEASURE_RESULT,
        Protocol.MEASURE_CANCEL,
        "/hl/v1/unknown"
    )

    @Test
    fun enginesIgnoreMalformedPayloads() = runBlocking {
        val (a, b) = InMemoryTransport.pair()
        val saved = mutableListOf<String>()
        val sink = object : RecordSink {
            override suspend fun contains(id: String) = false

            override suspend fun save(meta: RecordMeta, wave: FloatArray?) {
                saved += meta.id
            }

            override suspend fun delete(id: String) = Unit
        }
        val outbox = object : Outbox {
            override suspend fun pending() = emptyList<OutboxItem>()

            override suspend fun markDelivered(id: String) = Unit
        }
        val phone = PhoneSyncEngine(a, sink)
        val watch = WatchSyncEngine(b, outbox)
        val random = Random(42)
        repeat(500) {
            val path = paths[random.nextInt(paths.size)]
            val payload = when (random.nextInt(4)) {
                0 -> ByteArray(random.nextInt(0, 64)).also(random::nextBytes)
                1 -> "{}".encodeToByteArray()
                2 -> """{"id":1}""".encodeToByteArray()
                else -> ByteArray(0)
            }
            phone.handle(Envelope(path, payload))
            watch.handle(Envelope(path, payload))
        }
        assertTrue(saved.isEmpty())
    }
}
