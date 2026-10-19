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
    /** Steps the watch counted in the minute (v12); null from older watches and older rows. */
    val steps: Int? = null,
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
    /** The watch app version that sent it (v9): another version than the phone's runs other algorithms. */
    val watchVersion: String? = null,
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
    @ColumnInfo(defaultValue = "0") val lowConfidence: Boolean = false,
)

/** A background SpO2 try and how it ended (v10), for the oxygen screen's "today" card. Kept 14 days. */
@Entity(tableName = "spo2_attempts")
data class Spo2AttemptEntity(
    @PrimaryKey val tsMs: Long,
    val outcome: com.heartline.shared.vitals.Spo2Outcome,
    val percent: Int?,
    val retryAtMs: Long?,
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

/** A measurement the watch took right after the wearer got up (v13); [readingsJson] is the StandReading list. */
@Entity(tableName = "stand_probes")
data class StandProbeEntity(
    @PrimaryKey val startMs: Long,
    val readingsJson: String,
    val stepsPerMinute: Int?,
)

/** How the wearer felt at a rise after getting up (v13): Symptom names, comma separated, keyed by the rise's start. */
@Entity(tableName = "rise_symptoms")
data class RiseSymptomsEntity(
    @PrimaryKey val startMs: Long,
    val symptoms: String,
)

@Dao
interface HeartDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStandProbes(probes: List<StandProbeEntity>)

    @Query("SELECT * FROM stand_probes WHERE startMs >= :fromMs AND startMs < :toMs ORDER BY startMs")
    suspend fun standProbesBetween(fromMs: Long, toMs: Long): List<StandProbeEntity>

    @Query("SELECT * FROM stand_probes WHERE startMs >= :fromMs ORDER BY startMs")
    fun standProbesSince(fromMs: Long): Flow<List<StandProbeEntity>>

    @Query("SELECT MAX(startMs) FROM stand_probes")
    suspend fun newestStandProbeMs(): Long?

    @Query("SELECT MAX(minuteStartMs) FROM hr_minutes")
    suspend fun newestMinuteMs(): Long?

    @Query("DELETE FROM stand_probes")
    suspend fun deleteStandProbes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setRiseSymptoms(entry: RiseSymptomsEntity)

    @Query("DELETE FROM rise_symptoms WHERE startMs = :startMs")
    suspend fun clearRiseSymptoms(startMs: Long)

    @Query("SELECT * FROM rise_symptoms")
    fun riseSymptoms(): Flow<List<RiseSymptomsEntity>>

    @Query("SELECT * FROM rise_symptoms WHERE startMs >= :fromMs")
    suspend fun riseSymptomsSince(fromMs: Long): List<RiseSymptomsEntity>

    @Query("DELETE FROM rise_symptoms")
    suspend fun deleteRiseSymptoms()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpo2(samples: List<Spo2SampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemps(samples: List<TempSampleEntity>)

    @Query("SELECT * FROM spo2_samples WHERE tsMs >= :fromMs ORDER BY tsMs")
    fun spo2Since(fromMs: Long): Flow<List<Spo2SampleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpo2Attempts(attempts: List<Spo2AttemptEntity>)

    @Query("SELECT * FROM spo2_attempts WHERE tsMs >= :fromMs ORDER BY tsMs")
    fun spo2AttemptsSince(fromMs: Long): Flow<List<Spo2AttemptEntity>>

    @Query("DELETE FROM spo2_attempts WHERE tsMs < :beforeMs")
    suspend fun deleteSpo2AttemptsBefore(beforeMs: Long)

    @Query("DELETE FROM spo2_attempts")
    suspend fun deleteSpo2Attempts()

    @Query("SELECT * FROM skin_temp_samples WHERE tsMs >= :fromMs ORDER BY tsMs")
    fun tempsSince(fromMs: Long): Flow<List<TempSampleEntity>>

    @Query("SELECT * FROM spo2_samples ORDER BY tsMs DESC LIMIT 1")
    fun latestSpo2(): Flow<Spo2SampleEntity?>

    @Query("SELECT * FROM skin_temp_samples ORDER BY tsMs DESC LIMIT 1")
    fun latestTemp(): Flow<TempSampleEntity?>

    @Query("SELECT * FROM spo2_samples ORDER BY tsMs")
    suspend fun allSpo2(): List<Spo2SampleEntity>

    @Query("SELECT * FROM skin_temp_samples ORDER BY tsMs")
    suspend fun allTemps(): List<TempSampleEntity>

    @Query("SELECT * FROM stress_samples ORDER BY tsMs")
    suspend fun allStress(): List<StressSampleEntity>

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

    /** @return the new row id, or -1 when the alert was already stored (a resend). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlert(alert: AlertEntity): Long

    @Query("SELECT * FROM alerts ORDER BY atMs DESC")
    fun alerts(): Flow<List<AlertEntity>>

    @Query("SELECT MAX(atMs) FROM alerts WHERE trend = :trend")
    suspend fun lastTrendAlert(trend: String): Long?

    @Query("SELECT * FROM hr_minutes WHERE minuteStartMs >= :fromMs AND minuteStartMs < :toMs ORDER BY minuteStartMs")
    suspend fun minutesBetween(fromMs: Long, toMs: Long): List<HrMinuteEntity>

    @Query("UPDATE alerts SET read = 1")
    suspend fun markAlertsRead()

    @Query("DELETE FROM hr_minutes")
    suspend fun deleteMinutes()

    @Query("DELETE FROM alerts WHERE id LIKE 'demo-%'")
    suspend fun deleteDemoAlerts(): Int

    @Query("DELETE FROM alerts")
    suspend fun deleteAlerts()
}
