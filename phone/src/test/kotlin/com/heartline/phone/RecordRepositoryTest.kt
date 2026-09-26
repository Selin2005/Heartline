// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.data.DemoData
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.WaveStore
import com.heartline.phone.ui.model.RecordFormatter
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.model.Symptom
import com.heartline.shared.sync.InMemoryTransport
import com.heartline.shared.sync.Outbox
import com.heartline.shared.sync.OutboxItem
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.WatchSyncEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class RecordRepositoryTest {
    private lateinit var db: HeartlineDatabase
    private lateinit var repo: RecordRepository
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = HeartlineDatabase.inMemory(context)
        dir = File(context.cacheDir, "waves-test").apply { deleteRecursively() }
        repo = RecordRepository(db.records(), WaveStore(dir), now = { 42L })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun saveStoresMetaAndWaveformFile() = runBlocking {
        val (meta, wave) = DemoData.ecgRecords(now = 1_000_000L).first()
        repo.save(meta, wave)

        val stored = repo.observe(RecordKind.ECG).first().single()
        assertEquals(meta.id, stored.id)
        assertEquals(meta.summary, stored.summary)
        assertEquals(wave.size, repo.wave(stored)!!.size)
        assertTrue(File(dir, "waves/${meta.id}.bin").exists())
    }

    @Test
    fun deleteRemovesWaveformFile() = runBlocking {
        val (meta, wave) = DemoData.ecgRecords(now = 1_000_000L).first()
        repo.save(meta, wave)
        repo.delete(meta.id)
        assertTrue(repo.isEmpty())
        assertTrue(!File(dir, "waves/${meta.id}.bin").exists())
    }

    @Test
    fun updateSymptomsRewritesSummary() = runBlocking {
        val (meta, wave) = DemoData.ecgRecords(now = 1_000_000L).first()
        repo.save(meta, wave)
        repo.updateEcgSymptoms(meta.id, listOf(Symptom.FATIGUE), null)
        val summary = repo.observe(meta.id).first()!!.summary as RecordSummary.Ecg
        assertEquals(listOf(Symptom.FATIGUE), summary.symptoms)
    }

    @Test
    fun watchToPhoneSyncLandsInRoom() = runBlocking {
        val (watchSide, phoneSide) = InMemoryTransport.pair()
        val (meta, wave) = DemoData.ecgRecords(now = 1_000_000L)[1]
        val queue = mutableListOf(OutboxItem(meta, wave))
        val outbox = object : Outbox {
            override suspend fun pending() = queue.toList()

            override suspend fun markDelivered(id: String) {
                queue.removeAll { it.meta.id == id }
            }
        }
        val watch = WatchSyncEngine(watchSide, outbox)
        val phone = PhoneSyncEngine(phoneSide, repo)
        val jobs = listOf(
            phoneSide.incoming.onEach { phone.handle(it) }.launchIn(this),
            watchSide.incoming.onEach { watch.handle(it) }.launchIn(this),
        )
        yield()
        watch.flush()
        // Room writes on its own executor, so wait for the result rather than yielding.
        withTimeout(5_000) { repo.observe(meta.id).first { it != null } }
        withTimeout(5_000) { while (queue.isNotEmpty()) delay(10) }

        assertNotNull(repo.observe(meta.id).first())
        assertTrue(queue.isEmpty())

        phone.requestDelete(meta.id)
        assertNull(repo.observe(meta.id).first())
        jobs.forEach { it.cancel() }
    }

    @Test
    fun formatterUsesRelativeDates() = runBlocking {
        val zone = ZoneOffset.UTC
        val formatter = RecordFormatter("Today", "Yesterday", zone = zone, locale = Locale.US) { LocalDate.of(2026, 9, 24) }
        val today = LocalDate.of(2026, 9, 24).atTime(9, 41).toInstant(zone).toEpochMilli()
        val earlier = LocalDate.of(2026, 9, 21).atTime(22, 15).toInstant(zone).toEpochMilli()
        assertEquals("Today", formatter.date(today))
        assertEquals("Sep 21", formatter.date(earlier))
        assertEquals("September 2026", formatter.month(earlier))

        val (meta, wave) = DemoData.ecgRecords(now = today).first()
        repo.save(meta, wave)
        val ui = formatter.ecg(repo.observe(meta.id).first()!!)
        assertEquals(EcgResult.SINUS_RHYTHM, ui.result)
        assertEquals(30, ui.durationSec)
    }
}
