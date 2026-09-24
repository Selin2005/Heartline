package com.heartline.shared

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HeartDataSinkStub
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.Hrv
import com.heartline.shared.hr.MinuteAggregator
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.irn.HeartRateAlertRules
import com.heartline.shared.irn.IrnState
import com.heartline.shared.irn.IrregularRhythmDetector
import com.heartline.shared.sample.SyntheticHr
import com.heartline.shared.sync.InMemoryTransport
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartTest {
    private var ids = 0
    private val id = { "id-${ids++}" }

    @Test
    fun hrvMatchesHandComputedValues() {
        // Alternating 800/850 ms: successive diffs are all 50 ms.
        val ibis = List(20) { if (it % 2 == 0) 800 else 850 }
        val hrv = Hrv.compute(ibis)!!
        assertEquals(50.0, hrv.rmssdMs, 1e-9)
        assertEquals(0.0, hrv.pnn50, 1e-9) // > 50 ms strictly
        assertEquals(25.6, hrv.sdnnMs, 0.1)
    }

    @Test
    fun hrvRejectsArtefacts() {
        val ibis = List(20) { 800 } + listOf(1600, 250) + List(5) { 800 }
        assertEquals(25, Hrv.clean(ibis).size)
        assertEquals(0.0, Hrv.compute(ibis)!!.rmssdMs, 1e-9)
    }

    @Test
    fun minutesAggregateOnBodySamples() {
        val samples = SyntheticHr.samples(0, 180, bpm = 60.0)
        val minutes = MinuteAggregator.aggregate(samples)
        assertEquals(3, minutes.size)
        assertTrue(minutes.all { it.avgBpm in 57..63 })
        assertNotNull(minutes.first().rmssdMs)
    }

    @Test
    fun irregularWindowsTriggerOneAlertThenCooldown() {
        val detector = IrregularRhythmDetector()
        var state = IrnState()
        val alerts = mutableListOf<HealthAlert>()
        repeat(12) { w ->
            val window = SyntheticHr.samples(w * 15 * 60_000L, 60, bpm = 95.0, irregularity = 0.35, seed = w)
            val (next, alert) = detector.onWindow(state, window, id)
            state = next
            alert?.let(alerts::add)
        }
        assertEquals(1, alerts.size)
        assertEquals(AlertKind.IRREGULAR_RHYTHM, alerts.single().kind)
        assertEquals(5, alerts.single().windowStartsMs.size.coerceAtMost(5))
    }

    @Test
    fun regularRhythmNeverAlerts() {
        val detector = IrregularRhythmDetector()
        var state = IrnState()
        repeat(30) { w ->
            val (next, alert) = detector.onWindow(
                state,
                SyntheticHr.samples(w * 15 * 60_000L, 60, bpm = 70.0, irregularity = 0.03, seed = w),
                id
            )
            state = next
            assertNull(alert)
        }
    }

    @Test
    fun movingWindowsAreIgnored() {
        val (state, alert) = IrregularRhythmDetector().onWindow(
            IrnState(),
            SyntheticHr.samples(0, 60, irregularity = 0.35, moving = true),
            id
        )
        assertTrue(state.windows.isEmpty())
        assertNull(alert)
    }

    @Test
    fun sustainedHighRateAlertsOnce() {
        val rules = HeartRateAlertRules(sustainMinutes = 10)
        val minutes = List(10) { HrMinute(it * 60_000L, 131, 125, 140) }
        val first = rules.evaluate(minutes, MonitorSettings(), emptyMap(), id)
        assertEquals(listOf(AlertKind.HIGH_HEART_RATE), first.map { it.kind })
        val again = rules.evaluate(minutes, MonitorSettings(), mapOf(AlertKind.HIGH_HEART_RATE to 600_000L), id)
        assertTrue(again.isEmpty())
        val disabled = rules.evaluate(minutes, MonitorSettings(heartRateAlertsEnabled = false), emptyMap(), id)
        assertTrue(disabled.isEmpty())
    }

    @Test
    fun gapOrActivityPreventsHeartRateAlert() {
        val rules = HeartRateAlertRules(sustainMinutes = 10)
        val withGap = List(10) { HrMinute((if (it < 5) it else it + 3) * 60_000L, 35, 33, 38) }
        assertTrue(rules.evaluate(withGap, MonitorSettings(), emptyMap(), id).isEmpty())
        val active = List(10) { HrMinute(it * 60_000L, 150, 140, 160, resting = it != 4) }
        assertTrue(rules.evaluate(active, MonitorSettings(), emptyMap(), id).isEmpty())
    }

    @Test
    fun messagesSyncAndAreAcked() = runBlocking {
        val (watchSide, phoneSide) = InMemoryTransport.pair()
        val alert = HealthAlert("a1", AlertKind.IRREGULAR_RHYTHM, 1, 88)
        val batch = HrBatch("b1", listOf(HrMinute(0, 60, 55, 65)))
        val queue = mutableListOf(
            PendingMessage(alert.id, Protocol.ALERT, Protocol.json.encodeToString(alert).encodeToByteArray()),
            PendingMessage(batch.id, Protocol.HR_BATCH, Protocol.json.encodeToString(batch).encodeToByteArray())
        )
        val outbox = object : Outbox {
            override suspend fun pending() = emptyList<OutboxItem>()

            override suspend fun pendingMessages() = queue.toList()

            override suspend fun markDelivered(id: String) {
                queue.removeAll { it.id == id }
            }
        }
        val heart = HeartDataSinkStub()
        val noRecords = object : RecordSink {
            override suspend fun contains(id: String) = false

            override suspend fun save(meta: com.heartline.shared.model.RecordMeta, wave: FloatArray?) = Unit

            override suspend fun delete(id: String) = Unit
        }
        val watch = WatchSyncEngine(watchSide, outbox)
        val phone = PhoneSyncEngine(phoneSide, noRecords, heart)
        val jobs = listOf(
            phoneSide.incoming.onEach { phone.handle(it) }.launchIn(this),
            watchSide.incoming.onEach { watch.handle(it) }.launchIn(this)
        )
        yield()
        assertEquals(2, watch.flush())
        repeat(20) { yield() }
        assertEquals(listOf(alert), heart.alerts)
        assertEquals(listOf(batch), heart.batches)
        assertTrue(queue.isEmpty())
        jobs.forEach { it.cancel() }
    }
}
