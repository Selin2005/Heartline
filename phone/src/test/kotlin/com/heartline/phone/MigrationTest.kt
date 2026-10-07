// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heartline.phone.data.HeartlineDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrading from every shipped schema must keep existing records (P9). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HeartlineDatabase::class.java)

    @Test
    fun migrate1To3KeepsRecords() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO records (id, kind, startedAtMs, durationMs, sampleRateHz, sampleCount, summaryJson, wavePath, note, receivedAtMs) " +
                    "VALUES ('e1', 'ECG', 1, 30000, 500, 15000, '{}', 'waves/e1.bin', NULL, 2)",
            )
        }
        helper.runMigrationsAndValidate(DB, 3, true, HeartlineDatabase.MIGRATION_1_2, HeartlineDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT COUNT(*) FROM records").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM bp_calibrations").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrate3To4AddsValidations() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL("INSERT INTO bp_calibrations (id, createdAtMs, json) VALUES ('c1', 1, '{}')")
        }
        helper.runMigrationsAndValidate(DB, 4, true, HeartlineDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT COUNT(*) FROM bp_calibrations").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.execSQL("INSERT INTO bp_validations (id, readingId, atMs, watchSystolic, watchDiastolic, cuffSystolic, cuffDiastolic) VALUES ('v', 'r', 1, 120, 80, 118, 79)")
        }
    }

    @Test
    fun migrate4To5LabelsHeartMinutes() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL("INSERT INTO hr_minutes (minuteStartMs, avgBpm, minBpm, maxBpm, rmssdMs, resting) VALUES (0, 60, 58, 62, NULL, 1)")
            db.execSQL("INSERT INTO hr_minutes (minuteStartMs, avgBpm, minBpm, maxBpm, rmssdMs, resting) VALUES (60000, 110, 100, 120, NULL, 0)")
            db.execSQL("INSERT INTO alerts (id, kind, atMs, bpm, windowCount, read) VALUES ('a', 'HIGH_HEART_RATE', 1, 130, 0, 0)")
        }
        helper.runMigrationsAndValidate(DB, 5, true, HeartlineDatabase.MIGRATION_4_5).use { db ->
            db.query("SELECT activity FROM hr_minutes ORDER BY minuteStartMs").use { c ->
                c.moveToFirst()
                assertEquals("REST", c.getString(0))
                c.moveToNext()
                assertEquals("ACTIVE", c.getString(0))
            }
            db.query("SELECT threshold, context FROM alerts").use { c ->
                c.moveToFirst()
                assertEquals(true, c.isNull(0) && c.isNull(1))
            }
        }
    }

    @Test
    fun migrate5To6AddsAlertNormalAndTrend() {
        helper.createDatabase(DB, 5).use { db ->
            db.execSQL("INSERT INTO alerts (id, kind, atMs, bpm, windowCount, read, threshold, context) VALUES ('a', 'HIGH_HEART_RATE', 1, 130, 0, 0, 120, 'REST')")
        }
        helper.runMigrationsAndValidate(DB, 6, true, HeartlineDatabase.MIGRATION_5_6).use { db ->
            db.query("SELECT threshold, normal, trend FROM alerts").use { c ->
                c.moveToFirst()
                assertEquals(120, c.getInt(0))
                assertEquals(true, c.isNull(1) && c.isNull(2))
            }
        }
    }

    @Test
    fun migrate6To7AddsOxygenAndTemperatureTables() {
        helper.createDatabase(DB, 6).close()
        helper.runMigrationsAndValidate(DB, 7, true, HeartlineDatabase.MIGRATION_6_7).use { db ->
            db.execSQL("INSERT INTO spo2_samples (tsMs, percent, context, confirmation) VALUES (1, 96, 'SLEEP', 0)")
            db.execSQL("INSERT INTO skin_temp_samples (tsMs, skinC, ambientC, context, counted) VALUES (1, 34.1, NULL, 'SLEEP', 1)")
            db.query("SELECT COUNT(*) FROM spo2_samples").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
    }

    @Test
    fun migrate7To8AddsStressAndAlertVitals() {
        helper.createDatabase(DB, 7).use { db ->
            db.execSQL("INSERT INTO alerts (id, kind, atMs, bpm, windowCount, read) VALUES ('a', 'LOW_HEART_RATE', 1, 88, 0, 0)")
        }
        helper.runMigrationsAndValidate(DB, 8, true, HeartlineDatabase.MIGRATION_7_8).use { db ->
            db.execSQL("INSERT INTO stress_samples (tsMs, rmssdMs, bpm, score, context) VALUES (1, 42.0, 66, 31, 'REST')")
            db.query("SELECT vital, value FROM alerts").use { c ->
                c.moveToFirst()
                assertEquals(true, c.isNull(0) && c.isNull(1))
            }
        }
    }

    @Test
    fun migrate8To9KeepsTheWatchVersionOfAnAlert() {
        helper.createDatabase(DB, 8).use { db ->
            db.execSQL("INSERT INTO alerts (id, kind, atMs, bpm, windowCount, read) VALUES ('a', 'IRREGULAR_RHYTHM', 1, 90, 5, 0)")
        }
        helper.runMigrationsAndValidate(DB, 9, true, HeartlineDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT watchVersion FROM alerts").use { c ->
                c.moveToFirst()
                assertEquals(true, c.isNull(0))
            }
        }
    }

    @Test
    fun migrate9To10AddsBloodOxygenTries() {
        helper.createDatabase(DB, 9).use { db ->
            db.execSQL("INSERT INTO spo2_samples (tsMs, percent, context, confirmation) VALUES (1, 96, 'SLEEP', 0)")
        }
        helper.runMigrationsAndValidate(DB, 10, true, HeartlineDatabase.MIGRATION_9_10).use { db ->
            db.execSQL("INSERT INTO spo2_attempts (tsMs, outcome, percent, retryAtMs) VALUES (2, 'MOVING', NULL, 600002)")
            db.query("SELECT COUNT(*) FROM spo2_samples").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
