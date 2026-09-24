package com.heartline.wear

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heartline.wear.data.WatchDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WatchDatabase::class.java)

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
}
