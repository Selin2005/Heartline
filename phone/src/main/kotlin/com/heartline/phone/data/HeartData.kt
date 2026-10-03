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
import com.heartline.shared.hr.HeartTrend
import com.heartline.shared.hr.HrContext
import com.heartline.shared.hr.VitalAlert
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
    /** The wearer's usual heart rate then, and whether this is a several-day trend notice (v6). */
    val normal: Int? = null,
    val trend: HeartTrend? = null,
    /** A blood-oxygen, temperature, combined or stress notice and its value (v8). */
    val vital: VitalAlert? = null,
    val value: Float? = null,
)

/** A background stress reading from the watch's rhythm windows (v8); [score] is null in sleep. */
@Entity(tableName = "stress_samples")
data class StressSampleEntity(
    @PrimaryKey val tsMs: Long,
    val rmssdMs: Double,
    val bpm: Int,
    val score: Int?,
    val context: HrContext,
)

/** A background blood-oxygen reading from the watch (v7). */
@Entity(tableName = "spo2_samples")
data class Spo2SampleEntity(
    @PrimaryKey val tsMs: Long,
    val percent: Int,
    val context: HrContext,
    val confirmation: Boolean,
)

/** A background skin-temperature reading from the watch (v7); [counted] belongs to the night's value. */
@Entity(tableName = "skin_temp_samples")
data class TempSampleEntity(
    @PrimaryKey val tsMs: Long,
    val skinC: Float,
    val ambientC: Float?,
    val context: HrContext,
    val counted: Boolean,
)

@Dao
interface HeartDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpo2(samples: List<Spo2SampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemps(samples: List<TempSampleEntity>)

    @Query("SELECT * FROM spo2_samples WHERE tsMs >= :fromMs ORDER BY tsMs")
    fun spo2Since(fromMs: Long): Flow<List<Spo2SampleEntity>>

    @Query("SELECT * FROM skin_temp_samples WHERE tsMs >= :fromMs ORDER BY tsMs")
    fun tempsSince(fromMs: Long): Flow<List<TempSampleEntity>>

    @Query("DELETE FROM spo2_samples")
    suspend fun deleteSpo2()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStress(samples: List<StressSampleEntity>)

    @Query("SELECT * FROM stress_samples WHERE tsMs >= :fromMs ORDER BY tsMs")
    fun stressSince(fromMs: Long): Flow<List<StressSampleEntity>>

    @Query("SELECT * FROM stress_samples WHERE score IS NOT NULL ORDER BY tsMs DESC LIMIT 1")
    fun latestStress(): Flow<StressSampleEntity?>

    @Query("DELETE FROM stress_samples")
    suspend fun deleteStress()

    @Query("DELETE FROM skin_temp_samples")
    suspend fun deleteTemps()

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
