// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.describe
import com.heartline.shared.hr.HeartLimits
import com.heartline.shared.stress.StressLimits
import com.heartline.shared.vitals.VitalsLimits
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HeartTrend
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.hr.MinuteAggregator
import com.heartline.shared.hr.MonitorSettings
import com.heartline.shared.pots.OrthostaticEventDetector
import com.heartline.shared.pots.PosturalPatternRules
import com.heartline.shared.pots.RiseSource
import com.heartline.shared.pots.StandProbe
import com.heartline.shared.pots.StandReading
import com.heartline.shared.model.Symptom
import com.heartline.shared.sync.Protocol
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import com.heartline.shared.sync.HeartDataSink
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId
import java.util.UUID

/** Heart-rate trends and alerts received from the watch. */
class HeartRepository(
    private val dao: HeartDao,
    private val onAlert: suspend (HealthAlert) -> Unit = {},
    private val onLimits: suspend (HeartLimits) -> Unit = {},
    private val onVitalsLimits: suspend (VitalsLimits) -> Unit = {},
    private val onStressLimits: suspend (StressLimits) -> Unit = {},
    /** The connected watch app's version, kept with each alert. */
    private val watchVersion: suspend () -> String? = { null },
    /** For the phone's own check of rises after getting up: the settings, the wearer's age and tagged symptom times. */
    private val settings: suspend () -> MonitorSettings = { MonitorSettings() },
    private val age: suspend () -> Int? = { null },
    private val symptomTimes: suspend (fromMs: Long) -> List<Long> = { emptyList() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : HeartDataSink {
    /** Background stress readings since [fromMs], and the latest awake one. */
    fun stressSince(fromMs: Long): Flow<List<StressSampleEntity>> = dao.stressSince(fromMs)

    val latestStress: Flow<StressSampleEntity?> = dao.latestStress()

    /** Background blood oxygen and skin temperature since [fromMs]. */
    fun spo2Since(fromMs: Long): Flow<List<Spo2SampleEntity>> = dao.spo2Since(fromMs)

    fun tempsSince(fromMs: Long): Flow<List<TempSampleEntity>> = dao.tempsSince(fromMs)

    /** The watch's background SpO2 tries since [fromMs]. */
    fun spo2AttemptsSince(fromMs: Long): Flow<List<Spo2AttemptEntity>> = dao.spo2AttemptsSince(fromMs)

    /** The newest background reading of each kind (Home tiles, widgets). */
    val backgroundLatest: Flow<BackgroundLatest> =
        kotlinx.coroutines.flow.combine(dao.latestSpo2(), dao.latestTemp(), dao.latestStress()) { o, t, s -> BackgroundLatest(o, t, s) }

    /** Every background reading, for the data export. */
    suspend fun backgroundAll() = BackgroundAll(dao.allSpo2(), dao.allTemps(), dao.allStress())

    fun minutes(fromMs: Long, toMs: Long): Flow<List<HrMinuteEntity>> = dao.minutes(fromMs, toMs)

    val latestMinute: Flow<HrMinuteEntity?> = dao.latestMinute()

    val alerts: Flow<List<AlertEntity>> = dao.alerts()

    /**
     * The same minute can arrive twice: from the all-day stream and from a rhythm check (which alone
     * has HRV), or closed early and then completed. Both are combined rather than one replacing the other.
     */
    override suspend fun saveBatch(batch: HrBatch) {
        val incoming = batch.minutes.map { HrMinuteEntity(it.minuteStartMs, it.avgBpm, it.minBpm, it.maxBpm, it.rmssdMs, it.resting, it.activity, it.steps) }
        val existing = dao.minutesAt(incoming.map { it.minuteStartMs }).associateBy { it.minuteStartMs }
        dao.insertMinutes(incoming.map { m -> existing[m.minuteStartMs]?.let { merge(it, m) } ?: m })
        batch.limits?.let { onLimits(it) }
        if (batch.spo2.isNotEmpty()) dao.insertSpo2(batch.spo2.map { Spo2SampleEntity(it.tsMs, it.percent, it.context, it.confirmation, it.lowConfidence) })
        if (batch.skinTemp.isNotEmpty()) dao.insertTemps(batch.skinTemp.map { TempSampleEntity(it.tsMs, it.skinC, it.ambientC, it.context, it.counted) })
        batch.vitals?.let { onVitalsLimits(it) }
        if (batch.stress.isNotEmpty()) dao.insertStress(batch.stress.map { StressSampleEntity(it.tsMs, it.rmssdMs, it.bpm, it.score, it.context) })
        batch.stressLimits?.let { onStressLimits(it) }
        if (batch.spo2Attempts.isNotEmpty()) {
            dao.insertSpo2Attempts(batch.spo2Attempts.map { Spo2AttemptEntity(it.tsMs, it.outcome, it.percent, it.retryAtMs) })
            dao.deleteSpo2AttemptsBefore(batch.spo2Attempts.maxOf { it.tsMs } - ATTEMPTS_KEPT_MS)
        }
        if (batch.stands.isNotEmpty()) dao.insertStandProbes(batch.stands.map { it.toEntity() })
        // As of the newest data stored: a probe can arrive before the still minutes it needs.
        if (incoming.isNotEmpty() || batch.stands.isNotEmpty()) {
            val newest = listOfNotNull(dao.newestMinuteMs()?.plus(MINUTE), dao.newestStandProbeMs()).maxOrNull()
            if (newest != null) checkPosturalRise(newest)
        }
    }

    /** The minutes and stand probes from [fromMs] on, for the report of rises after getting up. */
    fun posturalSince(fromMs: Long): Flow<Pair<List<HrMinuteEntity>, List<StandProbe>>> =
        kotlinx.coroutines.flow.combine(dao.minutes(fromMs, Long.MAX_VALUE), dao.standProbesSince(fromMs)) { m, p -> m to p.map { it.toProbe() } }

    /** How the wearer felt at each rise after getting up, by the rise's start. */
    val riseSymptoms: Flow<Map<Long, List<Symptom>>> = dao.riseSymptoms().map { list -> list.associate { it.startMs to decodeSymptoms(it.symptoms) } }

    suspend fun setRiseSymptoms(startMs: Long, symptoms: List<Symptom>) {
        if (symptoms.isEmpty()) dao.clearRiseSymptoms(startMs) else dao.setRiseSymptoms(RiseSymptomsEntity(startMs, symptoms.joinToString(",") { it.name }))
    }

    /**
     * Rises after getting up (docs/algorithms/POSTURAL_RISE.md): found here rather than on the
     * watch, which keeps only 90 minutes while a pattern takes days, from the minutes and the
     * watch's stand probes of the last two weeks. The notice is the phone's own: it carries no
     * watch version, and no watch ever receives it.
     */
    private suspend fun checkPosturalRise(nowMs: Long) {
        val cfg = settings()
        if (!cfg.posturalRiseActive) return
        val last = dao.lastTrendAlert(HeartTrend.POSTURAL_RISE.name)
        if (last != null && nowMs - last < PosturalPatternRules.COOLDOWN_MS) return
        val from = nowMs - PATTERN_LOOKBACK_MS
        val minutes = dao.minutesBetween(from, nowMs).map { it.toMinute() }
        val probes = dao.standProbesBetween(from, nowMs + 1).map { it.toProbe() }
        val minRise = OrthostaticEventDetector.minRiseFor(age())
        val events = OrthostaticEventDetector(minRiseBpm = minRise).detect(minutes, nowMs, probes)
        val rules = PosturalPatternRules()
        // Symptoms only describe a pattern, so the ECG records are read only once there is one.
        if (rules.evaluate(events, nowMs, zone) == null) return
        val tagged = dao.riseSymptomsSince(from).filter { r -> decodeSymptoms(r.symptoms).any { it in FELT } }.map { it.startMs }
        val pattern = rules.evaluate(events, nowMs, zone, symptomTimes(from) + tagged) ?: return
        val alert = HealthAlert(
            newId(), AlertKind.HIGH_HEART_RATE, nowMs, pattern.medianPeakBpm,
            threshold = minRise, context = HrContext.ACTIVE, normal = pattern.medianRestBpm,
            trend = HeartTrend.POSTURAL_RISE, value = pattern.medianRiseBpm.toFloat(),
        )
        HLog.i("Heartline/Alert", "postural rise: ${pattern.events} rises on ${pattern.days} days (${events.count { it.source == RiseSource.PROBE }} measured), symptoms=${pattern.withSymptoms} ${alert.describe()}")
        dao.insertAlert(AlertEntity(alert.id, alert.kind, alert.atMs, alert.bpm, 0, threshold = alert.threshold, context = alert.context, normal = alert.normal, trend = alert.trend, value = alert.value))
        onAlert(alert)
    }

    /**
     * Stores an alert and notifies once. The watch resends an alert until the phone acknowledges
     * it, so the same alert can arrive again (after a reconnect or an update): only a new one notifies.
     */
    override suspend fun saveAlert(alert: HealthAlert) {
        val from = watchVersion()
        val row = dao.insertAlert(
            AlertEntity(alert.id, alert.kind, alert.atMs, alert.bpm, alert.windowStartsMs.size, threshold = alert.threshold, context = alert.context, normal = alert.normal, trend = alert.trend, vital = alert.vital, value = alert.value, watchVersion = from),
        )
        val fresh = row != -1L
        HLog.i("Heartline/Alert", "received ${alert.describe()} watch=${from ?: "unknown"} ${if (fresh) "notified" else "duplicate, not notified"}")
        if (fresh) onAlert(alert)
    }

    suspend fun markAlertsRead() = dao.markAlertsRead()

    companion object {
        /** The SpO2 tries are for the "today" card and a look back; two weeks is plenty. */
        const val ATTEMPTS_KEPT_MS = 14 * 86_400_000L
        private const val MINUTE = 60_000L

        /** The pattern's two weeks, and the still minutes before its first day's rise. */
        const val PATTERN_LOOKBACK_MS = 14 * 86_400_000L + 15 * MINUTE

        /** How the wearer may feel at a rise; tagged on a rise or on an ECG, they describe the notice. */
        val FELT = setOf(Symptom.DIZZINESS, Symptom.PALPITATIONS, Symptom.FAINTING, Symptom.FATIGUE)

        fun HrMinuteEntity.toMinute() = HrMinute(minuteStartMs, avgBpm, minBpm, maxBpm, rmssdMs, resting, activity, steps)

        fun StandProbe.toEntity() = StandProbeEntity(startMs, Protocol.json.encodeToString(ListSerializer(StandReading.serializer()), readings), stepsPerMinute)

        fun StandProbeEntity.toProbe() = StandProbe(startMs, runCatching { Protocol.json.decodeFromString(ListSerializer(StandReading.serializer()), readingsJson) }.getOrDefault(emptyList()), stepsPerMinute)

        fun decodeSymptoms(text: String): List<Symptom> = text.split(',').mapNotNull { name -> Symptom.entries.firstOrNull { it.name == name } }

        fun merge(old: HrMinuteEntity, new: HrMinuteEntity) = new.copy(
            avgBpm = (old.avgBpm + new.avgBpm) / 2,
            minBpm = minOf(old.minBpm, new.minBpm),
            maxBpm = maxOf(old.maxBpm, new.maxBpm),
            rmssdMs = new.rmssdMs ?: old.rmssdMs,
            resting = old.resting && new.resting,
            activity = if (new.activity == HrContext.REST) old.activity else new.activity,
            steps = MinuteAggregator.maxSteps(old.steps, new.steps),
        )
    }

    suspend fun deleteAll() {
        dao.deleteSpo2()
        dao.deleteSpo2Attempts()
        dao.deleteTemps()
        dao.deleteStress()
        dao.deleteMinutes()
        dao.deleteStandProbes()
        dao.deleteRiseSymptoms()
        dao.deleteAlerts()
    }
}

/** The newest background blood oxygen, skin temperature and stress reading, if any. */
data class BackgroundLatest(val spo2: Spo2SampleEntity? = null, val temp: TempSampleEntity? = null, val stress: StressSampleEntity? = null)

data class BackgroundAll(val spo2: List<Spo2SampleEntity> = emptyList(), val temps: List<TempSampleEntity> = emptyList(), val stress: List<StressSampleEntity> = emptyList())
