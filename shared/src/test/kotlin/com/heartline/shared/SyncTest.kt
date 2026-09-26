// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sample.SyntheticEcg
import com.heartline.shared.sensor.MetricRequirements
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.shared.sensor.TrackerKind
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.Hello
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.WatchSyncEngine
import com.heartline.shared.sync.WaveCodec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {
    private class MemoryOutbox(items: List<OutboxItem>) : Outbox {
        val items = items.toMutableList()

        override suspend fun pending() = items.toList()

        override suspend fun markDelivered(id: String) {
            items.removeAll { it.meta.id == id }
        }
    }

    private class MemorySink : RecordSink {
        val saved = mutableMapOf<String, Pair<RecordMeta, FloatArray?>>()
        var saveCount = 0

        override suspend fun contains(id: String) = id in saved

        override suspend fun save(meta: RecordMeta, wave: FloatArray?) {
            saveCount++
            saved[meta.id] = meta to wave
        }

        override suspend fun delete(id: String) {
            saved.remove(id)
        }
    }

    private fun ecgItem(id: String): OutboxItem {
        val wave = SyntheticEcg.generate(30.0)
        return OutboxItem(
            RecordMeta(
                id,
                RecordKind.ECG,
                startedAtMs = 1_000,
                durationMs = 30_000,
                sampleRateHz = 500,
                sampleCount = wave.size,
                summary = RecordSummary.Ecg(72, EcgResult.SINUS_RHYTHM, 0f)
            ),
            wave
        )
    }

    private fun bpItem(id: String) = OutboxItem(
        RecordMeta(id, RecordKind.BLOOD_PRESSURE, 5_000, 20_000, 0, 0, RecordSummary.BloodPressure(118, 76, 64)),
        null
    )

    @Test
    fun waveCodecRoundTrips() {
        val samples = floatArrayOf(0f, 1.5f, -0.25f, 3.75f)
        val decoded = WaveCodec.decode(WaveCodec.encode(2, 500, samples))
        assertEquals(2, decoded.kindOrdinal)
        assertEquals(500, decoded.sampleRateHz)
        assertArrayEquals(samples, decoded.samples, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun waveCodecRejectsTruncatedPayload() {
        WaveCodec.decode(WaveCodec.encode(0, 500, FloatArray(10)).copyOf(30))
    }

    @Test
    fun recordMetaSerializesPolymorphicSummary() {
        val meta = ecgItem("a").meta
        val json = Protocol.json.encodeToString(meta)
        assertTrue(json.contains("\"type\""))
        assertEquals(meta, Protocol.json.decodeFromString<RecordMeta>(json))
    }

    @Test
    fun helloChecksProtocolVersion() {
        assertTrue(Hello(appVersion = "1").isCompatible())
        assertFalse(Hello(protocol = 99, appVersion = "1").isCompatible())
    }

    @Test
    fun endToEndDeliversAndClearsOutbox() = runBlocking {
        val (watchSide, phoneSide) = InMemoryTransport.pair()
        val outbox = MemoryOutbox(listOf(ecgItem("e1"), bpItem("b1")))
        val sink = MemorySink()
        val watch = WatchSyncEngine(watchSide, outbox)
        val phone = PhoneSyncEngine(phoneSide, sink)
        val jobs = listOf(
            phoneSide.incoming.onEach { phone.handle(it) }.launchIn(this),
            watchSide.incoming.onEach { watch.handle(it) }.launchIn(this)
        )
        yield()

        assertEquals(2, watch.flush())
        repeat(20) { yield() }

        assertEquals(setOf("e1", "b1"), sink.saved.keys)
        assertEquals(15_000, sink.saved.getValue("e1").second!!.size)
        assertTrue(outbox.items.isEmpty())
        jobs.forEach { it.cancel() }
    }

    @Test
    fun offlineRecordsStayQueuedAndResendIsIdempotent() = runBlocking {
        val (watchSide, phoneSide) = InMemoryTransport.pair()
        val outbox = MemoryOutbox(listOf(ecgItem("e1")))
        val sink = MemorySink()
        val watch = WatchSyncEngine(watchSide, outbox)
        val phone = PhoneSyncEngine(phoneSide, sink)
        val jobs = listOf(
            phoneSide.incoming.onEach { phone.handle(it) }.launchIn(this),
            watchSide.incoming.onEach { watch.handle(it) }.launchIn(this)
        )
        yield()

        phoneSide.connected = false
        assertEquals(0, watch.flush())
        assertEquals(1, outbox.items.size)

        phoneSide.connected = true
        watch.flush()
        repeat(20) { yield() }
        // Simulate a lost ack: the watch sends the same record again.
        outbox.items.add(ecgItem("e1"))
        watch.flush()
        repeat(20) { yield() }

        assertEquals(1, sink.saveCount)
        assertTrue(outbox.items.isEmpty())
        jobs.forEach { it.cancel() }
    }

    @Test
    fun waveBeforeMetaIsJoined() = runTest {
        val (watchSide, phoneSide) = InMemoryTransport.pair()
        val sink = MemorySink()
        val phone = PhoneSyncEngine(phoneSide, sink)
        val ack = async(start = CoroutineStart.UNDISPATCHED) { watchSide.incoming.first() }
        val item = ecgItem("x")
        phone.handle(Envelope(Protocol.wavePath("x"), WaveCodec.encode(0, 500, item.wave!!)))
        assertTrue(sink.saved.isEmpty())
        phone.handle(Envelope(Protocol.RECORD_META, Protocol.json.encodeToString(item.meta).encodeToByteArray()))
        assertTrue("x" in sink.saved)
        assertEquals(Protocol.RECORD_ACK, ack.await().path)
    }

    @Test
    fun capabilitiesGateMetrics() {
        val watch4 =
            setOf(TrackerKind.ECG_ON_DEMAND, TrackerKind.HEART_RATE_CONTINUOUS, TrackerKind.PPG_ON_DEMAND, TrackerKind.SPO2_ON_DEMAND)
        val metrics = MetricRequirements.supportedMetrics(watch4)
        assertTrue(Metric.ECG in metrics)
        assertFalse(Metric.SKIN_TEMPERATURE in metrics)
        assertFalse(Metric.BODY_COMPOSITION in metrics)
    }

    @Test
    fun permissionsFollowTargetSdk() {
        assertEquals(setOf(PermissionPolicy.BODY_SENSORS), PermissionPolicy.permissionsFor(TrackerKind.ECG_ON_DEMAND, 35))
        assertEquals(setOf(PermissionPolicy.READ_ADDITIONAL_HEALTH_DATA), PermissionPolicy.permissionsFor(TrackerKind.ECG_ON_DEMAND, 36))
        assertEquals(setOf(PermissionPolicy.READ_HEART_RATE), PermissionPolicy.permissionsFor(Metric.HEART_RATE, 36))
    }
}
