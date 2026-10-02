// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HrContext
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "hr_minutes")
data class HrMinuteEntity(
    @PrimaryKey val minuteStartMs: Long,
    val avgBpm: Int,
    val minBpm: Int,
    val maxBpm: Int,
    val rmssdMs: Double?,
    val resting: Boolean,
    /** Rest, moving about, exercise or sleep (rest for minutes from older watch versions). */
    @ColumnInfo(defaultValue = "REST") val activity: HrContext = if (resting) HrContext.REST else HrContext.ACTIVE,
)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey val id: String,
    val kind: AlertKind,
    val atMs: Long,
    val bpm: Int?,
    val windowCount: Int,
    val read: Boolean = false,
    /** The limit that was crossed, and what the wearer was doing (high/low alerts from newer watches). */
    val threshold: Int? = null,
    val context: HrContext? = null,
)

@Dao
interface HeartDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMinutes(minutes: List<HrMinuteEntity>)

    @Query("SELECT * FROM hr_minutes WHERE minuteStartMs >= :fromMs AND minuteStartMs < :toMs ORDER BY minuteStartMs")
    fun minutes(fromMs: Long, toMs: Long): Flow<List<HrMinuteEntity>>

    @Query("SELECT * FROM hr_minutes WHERE minuteStartMs IN (:starts)")
    suspend fun minutesAt(starts: List<Long>): List<HrMinuteEntity>

    @Query("SELECT * FROM hr_minutes ORDER BY minuteStartMs DESC LIMIT 1")
    fun latestMinute(): Flow<HrMinuteEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlert(alert: AlertEntity)

    @Query("SELECT * FROM alerts ORDER BY atMs DESC")
    fun alerts(): Flow<List<AlertEntity>>

    @Query("UPDATE alerts SET read = 1")
    suspend fun markAlertsRead()

    @Query("DELETE FROM hr_minutes")
    suspend fun deleteMinutes()

    @Query("DELETE FROM alerts WHERE id LIKE 'demo-%'")
    suspend fun deleteDemoAlerts(): Int

    @Query("DELETE FROM alerts")
    suspend fun deleteAlerts()
}
