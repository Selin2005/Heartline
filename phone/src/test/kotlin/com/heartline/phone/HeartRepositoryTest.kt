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

    /** Still readings every 3 minutes, then up: [peak] held for two readings, with [steps] a minute. */
    private fun getUp(upMs: Long, rest: Int = 68, peak: Int = 102, steps: Int? = 40) =
        (5 downTo 1).map { HrMinute(upMs - it * 180_000L, rest, rest - 2, rest + 2, activity = HrContext.REST) } +
            (0..2).map { HrMinute(upMs + it * 180_000L, peak, peak - 3, peak + 3, resting = false, activity = HrContext.ACTIVE, steps = steps) }

    private val day = 86_400_000L
    private val start = 1_800_000_000_000L

    private fun posturalRepo(on: Boolean = true, health: com.heartline.shared.hr.HealthContext = com.heartline.shared.hr.HealthContext()) = HeartRepository(
        db.heart(),
        onAlert = { notified += it },
        settings = { com.heartline.shared.hr.MonitorSettings(health = health).let { if (on) it else it.withMonitoring(false) } },
        zone = java.time.ZoneOffset.UTC,
    )

    @Test
    fun stepsAreStoredAndMerged() = runBlocking {
        repo.saveBatch(HrBatch("a", listOf(HrMinute(0, 90, 85, 95, resting = false, activity = HrContext.ACTIVE, steps = 30))))
        repo.saveBatch(HrBatch("b", listOf(HrMinute(0, 92, 85, 97, resting = false, activity = HrContext.ACTIVE, steps = 45))))
        repo.saveBatch(HrBatch("c", listOf(HrMinute(0, 92, 85, 97, resting = false, activity = HrContext.ACTIVE))))
        assertEquals(45, repo.minutes(0, Long.MAX_VALUE).first().single().steps)
    }

    @Test
    fun risesOnThreeDaysNotifyOnce() = runBlocking {
        val repo = posturalRepo()
        // Two days: not yet a pattern.
        repo.saveBatch(HrBatch("d1", getUp(start + 8 * 3_600_000L)))
        repo.saveBatch(HrBatch("d2", getUp(start + day + 8 * 3_600_000L)))
        assertTrue(notified.isEmpty())
        // The third day's rise counts once its 10 minutes have passed (a later reading).
        repo.saveBatch(HrBatch("d3", getUp(start + 2 * day + 8 * 3_600_000L) + HrMinute(start + 2 * day + 8 * 3_600_000L + 15 * 60_000L, 75, 72, 78)))
        val alert = notified.single()
        assertEquals(com.heartline.shared.hr.HeartTrend.POSTURAL_RISE, alert.trend)
        assertEquals(68, alert.normal)
        assertEquals(102, alert.bpm)
        assertEquals(34f, alert.value)
        val stored = repo.alerts.first().single()
        assertEquals(com.heartline.shared.hr.HeartTrend.POSTURAL_RISE, stored.trend)
        assertNull(stored.watchVersion)
        // A fourth day within the cooldown: no second notice.
        repo.saveBatch(HrBatch("d4", getUp(start + 3 * day + 8 * 3_600_000L) + HrMinute(start + 3 * day + 9 * 3_600_000L, 70, 68, 72)))
        assertEquals(1, notified.size)
    }

    @Test
    fun risesAreNotJudgedWhenOffOrWithAPacemaker() = runBlocking {
        val off = posturalRepo(on = false)
        val paced = posturalRepo(health = com.heartline.shared.hr.HealthContext(heartDevice = com.heartline.shared.hr.Answer.YES))
        for (r in listOf(off, paced)) {
            db.heart().deleteMinutes()
            (0..3).forEach { d -> r.saveBatch(HrBatch("p$d", getUp(start + d * day + 8 * 3_600_000L) + HrMinute(start + d * day + 9 * 3_600_000L, 70, 68, 72))) }
        }
        assertTrue(notified.isEmpty())
    }

    @Test
    fun briskWalkingIsNotGettingUp() = runBlocking {
        val repo = posturalRepo()
        (0..3).forEach { d -> repo.saveBatch(HrBatch("w$d", getUp(start + d * day + 8 * 3_600_000L, steps = 118) + HrMinute(start + d * day + 9 * 3_600_000L, 70, 68, 72))) }
        assertTrue(notified.isEmpty())
    }

    /** Still readings before [upMs] only; the watch's probe sees what follows. */
    private fun still(upMs: Long, rest: Int = 68) = (5 downTo 1).map { HrMinute(upMs / 60_000L * 60_000L - it * 180_000L, rest, rest - 2, rest + 2, activity = HrContext.REST) }

    private fun probe(upMs: Long, held: Int = 103) = com.heartline.shared.pots.StandProbe(
        upMs,
        listOf(20_000L, 150_000L, 360_000L).flatMap { at -> (0 until 8).map { k -> com.heartline.shared.pots.StandReading(upMs + at + k * 5_000L, held + k % 3 - 1) } },
        40,
    )

    @Test
    fun theWatchesProbesFindRisesTheSparseReadingsMiss() = runBlocking {
        val repo = posturalRepo()
        (0..2).forEach { d ->
            val up = start + d * day + 8 * 3_600_000L + 20_000L
            repo.saveBatch(HrBatch("m$d", still(up)))
            repo.saveBatch(HrBatch("p$d", emptyList(), stands = listOf(probe(up))))
        }
        val alert = notified.single()
        assertEquals(com.heartline.shared.hr.HeartTrend.POSTURAL_RISE, alert.trend)
        assertEquals(68, alert.normal)
        assertEquals(103, alert.bpm)
    }

    @Test
    fun aProbeCountsOnceItsStillMinutesArrive() = runBlocking {
        val repo = posturalRepo()
        // The probe can reach the phone before the passive readings of the minutes before it.
        (0..2).forEach { d -> repo.saveBatch(HrBatch("p$d", emptyList(), stands = listOf(probe(start + d * day + 8 * 3_600_000L)))) }
        assertTrue(notified.isEmpty())
        (0..2).forEach { d -> repo.saveBatch(HrBatch("m$d", still(start + d * day + 8 * 3_600_000L))) }
        assertEquals(1, notified.size)
    }

    @Test
    fun howTheWearerFeltIsKeptPerRise() = runBlocking {
        repo.setRiseSymptoms(42L, listOf(com.heartline.shared.model.Symptom.DIZZINESS, com.heartline.shared.model.Symptom.FATIGUE))
        assertEquals(listOf(com.heartline.shared.model.Symptom.DIZZINESS, com.heartline.shared.model.Symptom.FATIGUE), repo.riseSymptoms.first()[42L])
        repo.setRiseSymptoms(42L, emptyList())
        assertTrue(repo.riseSymptoms.first().isEmpty())
        repo.saveBatch(HrBatch("p", emptyList(), stands = listOf(probe(start))))
        repo.setRiseSymptoms(start, listOf(com.heartline.shared.model.Symptom.PALPITATIONS))
        repo.deleteAll()
        assertTrue(repo.posturalSince(0).first().second.isEmpty())
        assertTrue(repo.riseSymptoms.first().isEmpty())
    }

    @Test
    fun theReportExistsOnlyOnceThereIsAPattern() {
        val zone = java.time.ZoneOffset.UTC
        val minutes = (0..2).flatMap { d -> getUp(start + d * day + 8 * 3_600_000L) }.map {
            HrMinuteEntity(it.minuteStartMs, it.avgBpm, it.minBpm, it.maxBpm, it.rmssdMs, it.resting, it.activity, it.steps)
        }
        val now = start + 2 * day + 9 * 3_600_000L
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val events = com.heartline.phone.ui.model.PosturalSummaries.events(minutes, emptyList(), now, 30)
        val felt = mapOf(events.first().startMs to listOf(com.heartline.shared.model.Symptom.DIZZINESS))
        val report = com.heartline.phone.ui.model.PosturalSummaries.report(events, felt, null, now, 30, today, zone, java.util.Locale.UK) { "d$it" to "t" }
        assertEquals(com.heartline.shared.pots.PosturalStatus.PATTERN, report.status)
        assertEquals(3, report.days)
        assertEquals(3, report.riseCount)
        assertEquals(34, report.medianRiseBpm)
        assertEquals(3, report.chart.size)
        assertEquals(29, report.chart.last().index)
        // Newest first, with how the wearer felt and the curve.
        assertEquals(start + 2 * day + 8 * 3_600_000L, report.rises.first().startMs)
        assertEquals(listOf(com.heartline.shared.model.Symptom.DIZZINESS), report.rises.last().symptoms)
        assertTrue(report.curves.getValue(report.rises.first().startMs).any { it.seconds < 0 })
        // Two days in: single rises are no report, so the heart screen shows nothing.
        val earlier = start + day + 9 * 3_600_000L
        assertNull(com.heartline.phone.ui.model.PosturalSummaries.banner(events.filter { it.startMs < earlier }, null, earlier, zone))
        assertEquals(com.heartline.shared.pots.PosturalStatus.PATTERN, com.heartline.phone.ui.model.PosturalSummaries.banner(events, null, now, zone)?.status)
        // After a notice, with the rises gone, the report stays as "seen less often".
        val later = now + 20 * day
        assertEquals(com.heartline.shared.pots.PosturalStatus.EASING, com.heartline.phone.ui.model.PosturalSummaries.banner(events, now, later, zone)?.status)
    }
}
