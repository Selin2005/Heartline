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
import com.heartline.shared.hr.HrContext
import com.heartline.shared.sync.HeartDataSink
import kotlinx.coroutines.flow.Flow

/** Heart-rate trends and alerts received from the watch. */
class HeartRepository(
    private val dao: HeartDao,
    private val onAlert: suspend (HealthAlert) -> Unit = {},
    private val onLimits: suspend (HeartLimits) -> Unit = {},
    private val onVitalsLimits: suspend (VitalsLimits) -> Unit = {},
    private val onStressLimits: suspend (StressLimits) -> Unit = {},
    /** The connected watch app's version, kept with each alert. */
    private val watchVersion: suspend () -> String? = { null },
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
        val incoming = batch.minutes.map { HrMinuteEntity(it.minuteStartMs, it.avgBpm, it.minBpm, it.maxBpm, it.rmssdMs, it.resting, it.activity) }
        val existing = dao.minutesAt(incoming.map { it.minuteStartMs }).associateBy { it.minuteStartMs }
        dao.insertMinutes(incoming.map { m -> existing[m.minuteStartMs]?.let { merge(it, m) } ?: m })
        batch.limits?.let { onLimits(it) }
        if (batch.spo2.isNotEmpty()) dao.insertSpo2(batch.spo2.map { Spo2SampleEntity(it.tsMs, it.percent, it.context, it.confirmation) })
        if (batch.skinTemp.isNotEmpty()) dao.insertTemps(batch.skinTemp.map { TempSampleEntity(it.tsMs, it.skinC, it.ambientC, it.context, it.counted) })
        batch.vitals?.let { onVitalsLimits(it) }
        if (batch.stress.isNotEmpty()) dao.insertStress(batch.stress.map { StressSampleEntity(it.tsMs, it.rmssdMs, it.bpm, it.score, it.context) })
        batch.stressLimits?.let { onStressLimits(it) }
        if (batch.spo2Attempts.isNotEmpty()) {
            dao.insertSpo2Attempts(batch.spo2Attempts.map { Spo2AttemptEntity(it.tsMs, it.outcome, it.percent, it.retryAtMs) })
            dao.deleteSpo2AttemptsBefore(batch.spo2Attempts.maxOf { it.tsMs } - ATTEMPTS_KEPT_MS)
        }
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

        fun merge(old: HrMinuteEntity, new: HrMinuteEntity) = new.copy(
            avgBpm = (old.avgBpm + new.avgBpm) / 2,
            minBpm = minOf(old.minBpm, new.minBpm),
            maxBpm = maxOf(old.maxBpm, new.maxBpm),
            rmssdMs = new.rmssdMs ?: old.rmssdMs,
            resting = old.resting && new.resting,
            activity = if (new.activity == HrContext.REST) old.activity else new.activity,
        )
    }

    suspend fun deleteAll() {
        dao.deleteSpo2()
        dao.deleteSpo2Attempts()
        dao.deleteTemps()
        dao.deleteStress()
        dao.deleteMinutes()
        dao.deleteAlerts()
    }
}

/** The newest background blood oxygen, skin temperature and stress reading, if any. */
data class BackgroundLatest(val spo2: Spo2SampleEntity? = null, val temp: TempSampleEntity? = null, val stress: StressSampleEntity? = null)

data class BackgroundAll(val spo2: List<Spo2SampleEntity> = emptyList(), val temps: List<TempSampleEntity> = emptyList(), val stress: List<StressSampleEntity> = emptyList())
