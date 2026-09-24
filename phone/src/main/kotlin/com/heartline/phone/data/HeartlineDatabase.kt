package com.heartline.phone.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecordEntity::class, HrMinuteEntity::class, AlertEntity::class, BpCalibrationEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class HeartlineDatabase : RoomDatabase() {
    abstract fun records(): RecordDao

    abstract fun heart(): HeartDao

    abstract fun bp(): BpDao

    companion object {
        /** v2 (P5): heart-rate minutes and heart alerts. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hr_minutes` (`minuteStartMs` INTEGER NOT NULL, `avgBpm` INTEGER NOT NULL, " +
                        "`minBpm` INTEGER NOT NULL, `maxBpm` INTEGER NOT NULL, `rmssdMs` REAL, `resting` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`minuteStartMs`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `alerts` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `atMs` INTEGER NOT NULL, " +
                        "`bpm` INTEGER, `windowCount` INTEGER NOT NULL, `read` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** v3 (P6): blood-pressure calibrations. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bp_calibrations` (`id` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
                        "`json` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        fun create(context: Context): HeartlineDatabase =
            Room.databaseBuilder(context, HeartlineDatabase::class.java, "heartline.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        fun inMemory(context: Context): HeartlineDatabase =
            Room.inMemoryDatabaseBuilder(context, HeartlineDatabase::class.java).allowMainThreadQueries().build()
    }
}
