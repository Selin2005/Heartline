// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import com.heartline.shared.vitals.Spo2Outcome
import com.heartline.shared.vitals.Spo2Attempt
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
import com.heartline.shared.hr.HeartBaseline
import com.heartline.shared.hr.HeartLimits
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.model.EcgResult
import com.heartline.shared.hr.VitalAlert
import com.heartline.shared.stress.StressLimits
import com.heartline.shared.stress.StressSample
import com.heartline.shared.vitals.Spo2Sample
import com.heartline.shared.vitals.TempSample
import com.heartline.shared.vitals.VitalsLimits
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
        repo = HeartRepository(db.heart(), onAlert = { notified += it })
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
        // Karvonen zones from a resting 64 to a maximum of 184: 124+, 136+, 148+, 160+, 172+.
        assertEquals(listOf(0, 0, 30, 0, 0), HeartSummaries.zones(all, HeartBaseline.zones(184, 64)))
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

    @Test
    fun watchLimitsAreKept() = runBlocking {
        var kept: HeartLimits? = null
        val withLimits = HeartRepository(db.heart(), onLimits = { kept = it })
        val limits = HeartLimits(60, 52, high = 92, low = 41, sleepLow = 36, exerciseMax = 186)
        withLimits.saveBatch(HrBatch("b", listOf(HrMinute(0, 60, 58, 62)), limits))
        assertEquals(limits, kept)
    }

    @Test
    fun watchVitalsAreStoredOnceAndDeleted() = runBlocking {
        var kept: VitalsLimits? = null
        val withVitals = HeartRepository(db.heart(), onVitalsLimits = { kept = it })
        val limits = VitalsLimits(spo2Low = 90, spo2DayNormal = 97, spo2NightNormal = 95, spo2NightDrop = 3, tempRise = 1.0f)
        val batch = HrBatch(
            "v", emptyList(),
            spo2 = listOf(Spo2Sample(1_000L, 96, HrContext.SLEEP), Spo2Sample(121_000L, 89, HrContext.SLEEP, confirmation = true)),
            skinTemp = listOf(TempSample(2_000L, 34.1f, 22f, HrContext.SLEEP, counted = true)),
            vitals = limits,
        )
        withVitals.saveBatch(batch)
        withVitals.saveBatch(batch)
        assertEquals(listOf(96, 89), withVitals.spo2Since(0).first().map { it.percent })
        assertEquals(1, withVitals.tempsSince(0).first().size)
        assertEquals(limits, kept)
        withVitals.deleteAll()
        assertTrue(withVitals.spo2Since(0).first().isEmpty() && withVitals.tempsSince(0).first().isEmpty())
    }

    @Test
    fun aResentAlertNotifiesOnceAndKeepsTheWatchVersion() = runBlocking {
        val shown = mutableListOf<HealthAlert>()
        val repo = HeartRepository(db.heart(), onAlert = { shown += it }, watchVersion = { "0.0.2.115-beta.1" })
        val alert = HealthAlert("r", AlertKind.IRREGULAR_RHYTHM, 5L, 90, windowStartsMs = listOf(1, 2, 3, 4, 5))
        // The watch resends until it has the acknowledgement (after a reconnect or an update).
        repo.saveAlert(alert)
        repo.saveAlert(alert)
        assertEquals(1, shown.size)
        assertEquals("0.0.2.115-beta.1", repo.alerts.first().single().watchVersion)
    }

    @Test
    fun stressReadingsAndVitalNoticesAreKept() = runBlocking {
        var kept: StressLimits? = null
        val repo = HeartRepository(db.heart(), onStressLimits = { kept = it })
        val limits = StressLimits(usualRmssd = 40.0, usualBpm = 66, confidence = 1.0, windows = 300)
        repo.saveBatch(
            HrBatch(
                "s", emptyList(),
                stress = listOf(StressSample(1_000L, 42.0, 66, 30, HrContext.REST), StressSample(2_000L, 60.0, 55, null, HrContext.SLEEP)),
                stressLimits = limits,
            ),
        )
        assertEquals(listOf(30, null), repo.stressSince(0).first().map { it.score })
        assertEquals(30, repo.latestStress.first()!!.score)
        assertEquals(limits, kept)
        // A vital notice keeps its kind and value, so the alert list can tell it from a heart-rate one.
        repo.saveAlert(HealthAlert("v", AlertKind.HIGH_HEART_RATE, 5L, 70, vital = VitalAlert.STRESS, value = 81f))
        assertEquals(VitalAlert.STRESS, repo.alerts.first().single().vital)
        repo.deleteAll()
        assertTrue(repo.stressSince(0).first().isEmpty())
    }

    @Test
    fun phoneComputesTheSameBaselineFromItsMinutes() {
        val zone = java.time.ZoneOffset.UTC
        val minutes = (0 until 600).map { HrMinuteEntity(it * 120_000L, 62 + it % 5, 58, 70, null, resting = true, activity = HrContext.REST) }
        val history = HeartSummaries.history(minutes, zone)
        val limits = HeartBaseline.limits(history, history.days.last().day, com.heartline.shared.hr.AlertSensitivity.STANDARD, 40, null)
        assertEquals(64, limits.restNormal)
        assertTrue(limits.high in 97..100)
    }

    @Test
    fun bloodOxygenTriesAreKeptTwoWeeks() = runBlocking {
        val day = 86_400_000L
        val old = Spo2Attempt(day, Spo2Outcome.MOVING, retryAtMs = day + 600_000)
        repo.saveBatch(HrBatch("a", emptyList(), spo2Attempts = listOf(old)))
        val now = 20 * day
        repo.saveBatch(HrBatch("b", emptyList(), spo2Attempts = listOf(Spo2Attempt(now, Spo2Outcome.MEASURED, percent = 97))))
        val kept = repo.spo2AttemptsSince(0).first()
        // Older than 14 days before the newest: gone.
        assertEquals(listOf(now), kept.map { it.tsMs })
        assertEquals(97, kept.single().percent)
        repo.deleteAll()
        assertTrue(repo.spo2AttemptsSince(0).first().isEmpty())
    }
}
