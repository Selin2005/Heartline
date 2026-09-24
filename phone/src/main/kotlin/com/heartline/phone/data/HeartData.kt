package com.heartline.phone.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.heartline.shared.hr.AlertKind
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "hr_minutes")
data class HrMinuteEntity(
    @PrimaryKey val minuteStartMs: Long,
    val avgBpm: Int,
    val minBpm: Int,
    val maxBpm: Int,
    val rmssdMs: Double?,
    val resting: Boolean,
)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey val id: String,
    val kind: AlertKind,
    val atMs: Long,
    val bpm: Int?,
    val windowCount: Int,
    val read: Boolean = false,
)

@Dao
interface HeartDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMinutes(minutes: List<HrMinuteEntity>)

    @Query("SELECT * FROM hr_minutes WHERE minuteStartMs >= :fromMs AND minuteStartMs < :toMs ORDER BY minuteStartMs")
    fun minutes(fromMs: Long, toMs: Long): Flow<List<HrMinuteEntity>>

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

    @Query("DELETE FROM alerts")
    suspend fun deleteAlerts()
}
