// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heartline.wear.data.WatchDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WatchMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WatchDatabase::class.java, emptyList(), windowsSafeOpenFactory())

    @Test
    fun migrate1To2KeepsOutbox() {
        helper.createDatabase("w.db", 1).use { db ->
            db.execSQL("INSERT INTO records (id, metaJson, startedAtMs, wavePath, delivered) VALUES ('a', '{}', 1, NULL, 0)")
        }
        helper.runMigrationsAndValidate("w.db", 2, true, WatchDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT COUNT(*) FROM records WHERE delivered = 0").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
    }

    private companion object {
        // Upstream SupportSQLiteDriver.open() splits file paths only on '/', failing on Windows when
        // Room MigrationTestHelper passes context.getDatabasePath(...).absolutePath with '\'.
        fun windowsSafeOpenFactory(): SupportSQLiteOpenHelper.Factory {
            val delegate = FrameworkSQLiteOpenHelperFactory()
            return object : SupportSQLiteOpenHelper.Factory {
                override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                    val helper = delegate.create(configuration)
                    if (File.separatorChar == '/') return helper
                    return object : SupportSQLiteOpenHelper by helper {
                        override val databaseName: String?
                            get() = helper.databaseName?.let { configuration.context.getDatabasePath(it).absolutePath }
                    }
                }
            }
        }
    }
}
