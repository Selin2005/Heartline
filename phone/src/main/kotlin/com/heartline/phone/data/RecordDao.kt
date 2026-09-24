package com.heartline.phone.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heartline.shared.model.RecordKind
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE kind = :kind ORDER BY startedAtMs DESC")
    fun observe(kind: RecordKind): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE id = :id")
    fun observeById(id: String): Flow<RecordEntity?>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun get(id: String): RecordEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM records WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: RecordEntity)

    @Query("UPDATE records SET summaryJson = :summaryJson, note = :note WHERE id = :id")
    suspend fun updateSummary(id: String, summaryJson: String, note: String?)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM records")
    suspend fun deleteAll()

    @Query("SELECT * FROM records ORDER BY startedAtMs")
    suspend fun all(): List<RecordEntity>

    @Query("SELECT COUNT(*) FROM records")
    suspend fun count(): Int
}
