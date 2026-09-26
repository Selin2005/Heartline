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

    private companion object {
        const val DB = "migration-test.db"
    }
}
