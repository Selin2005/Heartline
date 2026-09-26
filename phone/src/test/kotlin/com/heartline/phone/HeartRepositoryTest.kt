// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.phone.data.DemoData
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.HeartlineDatabase
import com.heartline.phone.data.HrMinuteEntity
import com.heartline.phone.ui.model.HeartSummaries
import com.heartline.shared.hr.HealthAlert
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeartRepositoryTest {
    private lateinit var db: HeartlineDatabase
    private val notified = mutableListOf<HealthAlert>()
    private lateinit var repo: HeartRepository

    @Before
    fun setUp() {
        db = HeartlineDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repo = HeartRepository(db.heart()) { notified += it }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun batchesAndAlertsAreStoredIdempotently() = runBlocking {
        val now = 1_800_000_000_000L
        val batch = DemoData.heartBatch(now)
        repo.saveBatch(batch)
        repo.saveBatch(batch)
        val stored = repo.minutes(0, Long.MAX_VALUE).first()
        assertEquals(batch.minutes.size, stored.size)

        val alert = DemoData.alert(now)
        repo.saveAlert(alert)
        repo.saveAlert(alert)
        assertEquals(1, repo.alerts.first().size)
        assertEquals(5, repo.alerts.first().single().windowCount)
        assertTrue(notified.isNotEmpty())

        repo.markAlertsRead()
        assertTrue(repo.alerts.first().all { it.read })
    }

    @Test
    fun restingAndHrvSummaries() {
        val minutes = (0 until 20).map { HrMinuteEntity(it * 60_000L, 60 + it, 55, 90, 20.0 + it, resting = it % 5 != 0) }
        assertEquals(62, HeartSummaries.resting(minutes))
        assertEquals(31, HeartSummaries.hrv(minutes))
        assertNull(HeartSummaries.resting(minutes.take(3)))
    }
}
