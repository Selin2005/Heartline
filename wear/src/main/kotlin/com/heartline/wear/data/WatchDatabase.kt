package com.heartline.wear.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** A measurement taken on the watch; kept for the watch's history after delivery. */
@Entity(tableName = "records")
data class WatchRecordEntity(
    @PrimaryKey val id: String,
    val metaJson: String,
    val startedAtMs: Long,
    val wavePath: String?,
    val delivered: Boolean = false,
)

@Dao
interface WatchRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WatchRecordEntity)

    @Query("SELECT * FROM records WHERE delivered = 0 ORDER BY startedAtMs")
    suspend fun pending(): List<WatchRecordEntity>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun get(id: String): WatchRecordEntity?

    @Query("UPDATE records SET delivered = 1, wavePath = NULL WHERE id = :id")
    suspend fun markDelivered(id: String)

    @Query("SELECT * FROM records ORDER BY startedAtMs DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<WatchRecordEntity>>

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun delete(id: String)

    /** Keeps the newest [keep] delivered records. */
    @Query("DELETE FROM records WHERE delivered = 1 AND id NOT IN (SELECT id FROM records ORDER BY startedAtMs DESC LIMIT :keep)")
    suspend fun prune(keep: Int = 50)
}

/** A small message (HR batch, alert) waiting for the phone's ack. */
@Entity(tableName = "messages")
data class MessageEntity(@PrimaryKey val id: String, val path: String, val payload: ByteArray, val createdAtMs: Long)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY createdAtMs LIMIT 200")
    suspend fun pending(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    /** Keeps the queue bounded if the phone is away for days. */
    @Query("DELETE FROM messages WHERE createdAtMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}

@Database(entities = [WatchRecordEntity::class, MessageEntity::class], version = 2, exportSchema = true)
abstract class WatchDatabase : RoomDatabase() {
    abstract fun records(): WatchRecordDao

    abstract fun messages(): MessageDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `path` TEXT NOT NULL, `payload` BLOB NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        fun create(context: Context): WatchDatabase =
            Room.databaseBuilder(context, WatchDatabase::class.java, "heartline-watch.db").addMigrations(MIGRATION_1_2).build()

        fun inMemory(context: Context): WatchDatabase =
            Room.inMemoryDatabaseBuilder(context, WatchDatabase::class.java).allowMainThreadQueries().build()
    }
}
