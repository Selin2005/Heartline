// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.sync.HeartDataSink
import kotlinx.coroutines.flow.Flow

/** Heart-rate trends and alerts received from the watch. */
class HeartRepository(private val dao: HeartDao, private val onAlert: (HealthAlert) -> Unit = {}) : HeartDataSink {
    fun minutes(fromMs: Long, toMs: Long): Flow<List<HrMinuteEntity>> = dao.minutes(fromMs, toMs)

    val latestMinute: Flow<HrMinuteEntity?> = dao.latestMinute()

    val alerts: Flow<List<AlertEntity>> = dao.alerts()

    override suspend fun saveBatch(batch: HrBatch) {
        dao.insertMinutes(batch.minutes.map { HrMinuteEntity(it.minuteStartMs, it.avgBpm, it.minBpm, it.maxBpm, it.rmssdMs, it.resting) })
    }

    override suspend fun saveAlert(alert: HealthAlert) {
        dao.insertAlert(AlertEntity(alert.id, alert.kind, alert.atMs, alert.bpm, alert.windowStartsMs.size))
        onAlert(alert)
    }

    suspend fun markAlertsRead() = dao.markAlertsRead()

    suspend fun deleteAll() {
        dao.deleteMinutes()
        dao.deleteAlerts()
    }
}
