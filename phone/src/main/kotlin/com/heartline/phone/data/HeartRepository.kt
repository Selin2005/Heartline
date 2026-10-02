// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HeartLimits
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrContext
import com.heartline.shared.sync.HeartDataSink
import kotlinx.coroutines.flow.Flow

/** Heart-rate trends and alerts received from the watch. */
class HeartRepository(
    private val dao: HeartDao,
    private val onAlert: suspend (HealthAlert) -> Unit = {},
    private val onLimits: suspend (HeartLimits) -> Unit = {},
) : HeartDataSink {
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
    }

    override suspend fun saveAlert(alert: HealthAlert) {
        dao.insertAlert(
            AlertEntity(alert.id, alert.kind, alert.atMs, alert.bpm, alert.windowStartsMs.size, threshold = alert.threshold, context = alert.context, normal = alert.normal, trend = alert.trend),
        )
        onAlert(alert)
    }

    suspend fun markAlertsRead() = dao.markAlertsRead()

    companion object {
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
        dao.deleteMinutes()
        dao.deleteAlerts()
    }
}
