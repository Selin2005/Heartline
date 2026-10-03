// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecordEntity::class, HrMinuteEntity::class, AlertEntity::class, Spo2SampleEntity::class, TempSampleEntity::class, StressSampleEntity::class, BpCalibrationEntity::class, BpValidationEntity::class],
    version = 9,
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

        /** v4 (R7): watch-vs-cuff validation pairs. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bp_validations` (`id` TEXT NOT NULL, `readingId` TEXT NOT NULL, `atMs` INTEGER NOT NULL, " +
                        "`watchSystolic` INTEGER NOT NULL, `watchDiastolic` INTEGER NOT NULL, `cuffSystolic` INTEGER NOT NULL, " +
                        "`cuffDiastolic` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** v5: what the wearer was doing in each heart-rate minute; an alert's limit and context. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `hr_minutes` ADD COLUMN `activity` TEXT NOT NULL DEFAULT 'REST'")
                db.execSQL("UPDATE `hr_minutes` SET `activity` = 'ACTIVE' WHERE `resting` = 0")
                db.execSQL("ALTER TABLE `alerts` ADD COLUMN `threshold` INTEGER")
                db.execSQL("ALTER TABLE `alerts` ADD COLUMN `context` TEXT")
            }
        }

        /** v6: an alert's personal normal, and trend notices. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `alerts` ADD COLUMN `normal` INTEGER")
                db.execSQL("ALTER TABLE `alerts` ADD COLUMN `trend` TEXT")
            }
        }

        /** v7: background blood oxygen and skin temperature. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `spo2_samples` (`tsMs` INTEGER NOT NULL, `percent` INTEGER NOT NULL, `context` TEXT NOT NULL, " +
                        "`confirmation` INTEGER NOT NULL, PRIMARY KEY(`tsMs`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `skin_temp_samples` (`tsMs` INTEGER NOT NULL, `skinC` REAL NOT NULL, `ambientC` REAL, " +
                        "`context` TEXT NOT NULL, `counted` INTEGER NOT NULL, PRIMARY KEY(`tsMs`))",
                )
            }
        }

        /** v8: background stress, and the kind and value of vital notices in the alert list. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stress_samples` (`tsMs` INTEGER NOT NULL, `rmssdMs` REAL NOT NULL, `bpm` INTEGER NOT NULL, " +
                        "`score` INTEGER, `context` TEXT NOT NULL, PRIMARY KEY(`tsMs`))",
                )
                db.execSQL("ALTER TABLE `alerts` ADD COLUMN `vital` TEXT")
                db.execSQL("ALTER TABLE `alerts` ADD COLUMN `value` REAL")
            }
        }

        /** v9: the watch app version that sent each alert. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `alerts` ADD COLUMN `watchVersion` TEXT")
            }
        }

        fun create(context: Context): HeartlineDatabase =
            Room.databaseBuilder(context, HeartlineDatabase::class.java, "heartline.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()

        fun inMemory(context: Context): HeartlineDatabase =
            Room.inMemoryDatabaseBuilder(context, HeartlineDatabase::class.java).allowMainThreadQueries().build()
    }
}
