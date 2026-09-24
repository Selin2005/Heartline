package com.heartline.shared

import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.RecordSink
import com.heartline.shared.sync.WatchSyncEngine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings live on both devices; a change on either side reaches the other, and the newer copy wins. */
class SettingsSyncTest {
    private object NoOutbox : Outbox {
        override suspend fun pending() = emptyList<OutboxItem>()

        override suspend fun markDelivered(id: String) = Unit
    }

    private object NoSink : RecordSink {
        override suspend fun contains(id: String) = false

        override suspend fun save(meta: RecordMeta, wave: FloatArray?) = Unit

        override suspend fun delete(id: String) = Unit
    }

    /** Last-writer-wins store, as both apps implement it. */
    private class Store(var value: MonitorSettings) {
        fun offer(incoming: MonitorSettings): Boolean {
            if (!incoming.isNewerThan(value)) return false
            value = incoming
            return true
        }
    }

    @Test
    fun changesFlowBothWaysAndNewerWins() = runTest {
        val (w, p) = InMemoryTransport.pair()
        val phoneStore = Store(MonitorSettings(updatedAtMs = 10))
        val watchStore = Store(MonitorSettings(updatedAtMs = 10))
        val phone = PhoneSyncEngine(p, NoSink, onSettings = { phoneStore.offer(it) })
        val watch = WatchSyncEngine(w, NoOutbox, onSettings = { watchStore.offer(it) })
        w.incoming.onEach { watch.handle(it) }.launchIn(backgroundScope)
        p.incoming.onEach { phone.handle(it) }.launchIn(backgroundScope)
        testScheduler.runCurrent()

        // Turned off on the watch.
        watchStore.value = watchStore.value.copy(irregularRhythmEnabled = false, updatedAtMs = 20)
        watch.sendSettings(watchStore.value)
        testScheduler.runCurrent()
        assertFalse(phoneStore.value.irregularRhythmEnabled)

        // Threshold changed on the phone.
        phoneStore.value = phoneStore.value.copy(highBpm = 130, updatedAtMs = 30)
        phone.sendSettings(phoneStore.value)
        testScheduler.runCurrent()
        assertEquals(130, watchStore.value.highBpm)
        assertFalse(watchStore.value.irregularRhythmEnabled)

        // A stale copy (e.g. delayed message) never overwrites a newer one.
        phone.sendSettings(MonitorSettings(updatedAtMs = 5))
        testScheduler.runCurrent()
        assertEquals(130, watchStore.value.highBpm)
        assertTrue(watchStore.value.updatedAtMs == 30L)
    }
}
