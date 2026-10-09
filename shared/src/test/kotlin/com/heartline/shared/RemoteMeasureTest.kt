// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.measure.HeartRateCheck
import com.heartline.shared.measure.RemoteMeasureLink
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.sync.Features
import com.heartline.shared.sync.Hello
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.MeasureCancel
import com.heartline.shared.sync.MeasureOutcome
import com.heartline.shared.sync.MeasureResult
import com.heartline.shared.sync.MeasureStage
import com.heartline.shared.sync.MeasureState
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PendingMessage
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.WatchSyncEngine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMeasureTest {
    private class Box : Outbox {
        val messages = mutableListOf<PendingMessage>()

        override suspend fun pending() = emptyList<OutboxItem>()

        override suspend fun pendingMessages() = messages.toList()

        override suspend fun markDelivered(id: String) {
            messages.removeAll { it.id == id }
        }
    }

    private object NoRecords : RecordSink {
        override suspend fun contains(id: String) = false

        override suspend fun save(meta: RecordMeta, wave: FloatArray?) = Unit

        override suspend fun delete(id: String) = Unit
    }

    @Test fun linkRoundTrips() {
        val link = RemoteMeasureLink(Metric.SPO2, "abc-1")
        assertEquals("remote/SPO2?session=abc-1", link.route)
        assertEquals(link, RemoteMeasureLink.parse(link.route))
        val round = RemoteMeasureLink(Metric.BLOOD_PRESSURE, "cal", round = 2)
        assertEquals(round, RemoteMeasureLink.parse(round.route))
    }

    @Test fun linkRejectsWhatCannotBeMeasuredFromThePhone() {
        assertNull(RemoteMeasureLink.parse("remote/ECG?session=x"))
        assertNull(RemoteMeasureLink.parse("remote/BODY_COMPOSITION?session=x"))
        assertNull(RemoteMeasureLink.parse("remote/SPO2"))
        assertNull(RemoteMeasureLink.parse("remote/NOPE?session=x"))
        assertNull(RemoteMeasureLink.parse("quick/SPO2"))
        assertNull(RemoteMeasureLink(Metric.BLOOD_PRESSURE, "c", round = 9).route.let(RemoteMeasureLink::parse)?.round)
    }

    @Test fun onlyKeyFreeMetricsMeasureOnThePhone() {
        assertEquals(
            setOf(Metric.HEART_RATE, Metric.SPO2, Metric.SKIN_TEMPERATURE, Metric.STRESS, Metric.BLOOD_PRESSURE),
            Metric.entries.filter { it.measuresOnPhone }.toSet()
        )
    }

    @Test fun helloFeaturesAreOptionalBothWays() {
        val old = """{"protocol":1,"appVersion":"0.0.1"}"""
        assertTrue(Protocol.json.decodeFromString<Hello>(old).features.isEmpty())
        val new = Protocol.json.encodeToString(Hello(appVersion = "2", features = listOf(Features.REMOTE_MEASURE)))
        assertEquals(listOf(Features.REMOTE_MEASURE), Protocol.json.decodeFromString<Hello>(new).features)
    }

    @Test fun heartRateRecordRoundTrips() {
        val meta = RecordMeta("h", RecordKind.HEART_RATE, 1, 30_000, 0, 0, RecordSummary.HeartRate(68, 61, 74, 30))
        assertEquals(meta, Protocol.json.decodeFromString<RecordMeta>(Protocol.json.encodeToString(meta)))
        assertEquals(RecordKind.entries.lastIndex, RecordKind.HEART_RATE.ordinal)
    }

    @Test fun stateResultAndCancelTravel() = runBlocking {
        val (watchSide, phoneSide) = InMemoryTransport.pair()
        val box = Box()
        val cancels = mutableListOf<MeasureCancel>()
        val states = mutableListOf<MeasureState>()
        val results = mutableListOf<MeasureResult>()
        val watch = WatchSyncEngine(watchSide, box, onMeasureCancel = { cancels += it })
        val phone = PhoneSyncEngine(phoneSide, NoRecords, onMeasureState = { states += it }, onMeasureResult = { results += it })
        val jobs = listOf(
            phoneSide.incoming.onEach { phone.handle(it) }.launchIn(this),
            watchSide.incoming.onEach { watch.handle(it) }.launchIn(this)
        )
        yield()

        watch.sendMeasureState(MeasureState("s1", Metric.SPO2, MeasureStage.MEASURING, seq = 3, progress = 0.4f))
        val result =
            MeasureResult("r1", "s1", Metric.SPO2, MeasureOutcome.OK, recordId = "rec", summary = RecordSummary.Spo2(97, 64, false))
        box.messages += PendingMessage(result.id, Protocol.MEASURE_RESULT, Protocol.json.encodeToString(result).encodeToByteArray())
        watch.flush()
        phone.cancelMeasure("s1")
        repeat(20) { yield() }

        assertEquals(0.4f, states.single().progress, 0f)
        assertEquals(result, results.single())
        assertTrue("acked, so it leaves the outbox", box.messages.isEmpty())
        assertEquals("s1", cancels.single().sessionId)
        jobs.forEach { it.cancel() }
    }

    @Test fun heartRateCheckWaitsForGoodReadings() {
        val check = HeartRateCheck()
        repeat(10) { check.add(70, good = false, elapsedMs = 1_000) }
        assertEquals(0f, check.progress, 0f)
        for (i in 0 until 30) check.add(60 + i % 9, good = true, elapsedMs = 1_000)
        assertTrue(check.done)
        val r = check.result()!!
        assertEquals(64, r.bpm)
        assertEquals(60, r.min)
        assertEquals(68, r.max)
        assertEquals(30, r.samples)
    }

    @Test fun heartRateCheckNeedsEnoughReadings() {
        val check = HeartRateCheck()
        repeat(5) { check.add(70, good = true, elapsedMs = 1_000) }
        assertNull(check.result())
        check.add(500, good = true, elapsedMs = 1_000)
        assertEquals(5_000f / HeartRateCheck.DURATION_MS, check.progress, 0.0001f)
    }
}
