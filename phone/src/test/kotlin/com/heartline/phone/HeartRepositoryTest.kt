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
import com.heartline.phone.data.AlertEntity
import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.model.EcgResult
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

    @Test
    fun sameMinuteFromTwoSourcesIsCombined() = runBlocking {
        // All-day stream first (no HRV), then a rhythm check of the same minute (with HRV).
        repo.saveBatch(HrBatch("p", listOf(HrMinute(0, 70, 66, 74, null, activity = HrContext.REST))))
        repo.saveBatch(HrBatch("i", listOf(HrMinute(0, 72, 64, 80, 35.0, activity = HrContext.REST))))
        val m = repo.minutes(0, Long.MAX_VALUE).first().single()
        assertEquals(35.0, m.rmssdMs!!, 1e-9)
        assertEquals(64, m.minBpm)
        assertEquals(80, m.maxBpm)
        // A later copy without HRV does not erase it.
        repo.saveBatch(HrBatch("p2", listOf(HrMinute(0, 71, 66, 74, null, activity = HrContext.REST))))
        assertEquals(35.0, repo.minutes(0, Long.MAX_VALUE).first().single().rmssdMs!!, 1e-9)
    }

    @Test
    fun restingLeavesOutSleepAndExercise() {
        val rest = (0 until 10).map { HrMinuteEntity(it * 60_000L, 64, 60, 68, null, resting = true, activity = HrContext.REST) }
        val sleep = (10 until 30).map { HrMinuteEntity(it * 60_000L, 48, 45, 50, 40.0, resting = false, activity = HrContext.SLEEP) }
        val workout = (30 until 60).map { HrMinuteEntity(it * 60_000L, 150, 140, 170, null, resting = false, activity = HrContext.EXERCISE) }
        val all = rest + sleep + workout
        assertEquals(64, HeartSummaries.resting(all))
        assertEquals(48 to 45, HeartSummaries.sleep(all))
        // Zones for a maximum of 190: 95+, 114+, 133+, 152+, 171+.
        assertEquals(listOf(0, 0, 30, 0, 0), HeartSummaries.zones(all, 190))
    }

    @Test
    fun rhythmNotificationShowsItsEcgFollowUp() {
        val alert = AlertEntity("a", AlertKind.IRREGULAR_RHYTHM, 1_000_000L, 90, 5)
        assertNull(HeartSummaries.ecgFollowUp(alert, emptyList()))
        // A poor recording is skipped; the next usable one decides.
        val ecgs = listOf(1_600_000L to EcgResult.POOR_RECORDING, 1_900_000L to EcgResult.SINUS_RHYTHM)
        assertEquals(true, HeartSummaries.ecgFollowUp(alert, ecgs))
        assertEquals(false, HeartSummaries.ecgFollowUp(alert, listOf(1_200_000L to EcgResult.AFIB_SIGNS)))
        // Too late (over two hours after) or before the notification: not a follow-up.
        assertNull(HeartSummaries.ecgFollowUp(alert, listOf(1_000_000L + 3 * 3_600_000L to EcgResult.SINUS_RHYTHM)))
        assertNull(HeartSummaries.ecgFollowUp(alert, listOf(500_000L to EcgResult.SINUS_RHYTHM)))
    }
}
