package app.fastdrive.android.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies MIGRATION_1_2 against a hand-built v1 schema, rather than through Room's
 * MigrationTestHelper.
 *
 * MigrationTestHelper is documented (as of Room 2.8.x) as an androidTest-only tool: it resolves
 * exported schema JSON files through an instrumentation Context's assets, and its artifact
 * (androidx.room:room-testing) ships an androidTest-flavoured API surface built around
 * `Instrumentation`/`InstrumentationRegistry`. This project's Room tests run under Robolectric as
 * plain JVM unit tests (see CachedFileDaoTest's class doc), with no `room.schemaLocation` export
 * configured and no androidTest source set at all — so wiring up MigrationTestHelper here would
 * mean adding schema export plumbing to validate a two-column ADD COLUMN migration. Instead, this
 * test builds the exact v1 table by hand with a raw SupportSQLiteOpenHelper (the same
 * framework-backed SQLite implementation Room itself uses under the hood), inserts a row, runs
 * MIGRATION_1_2.migrate(...) directly, and confirms the row survives with both new columns null.
 */
@RunWith(RobolectricTestRunner::class)
class CachedFileMigrationTest {

    @Test
    fun migrate1To2AddsNullableColumnsWithoutLosingExistingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-test-${System.nanoTime()}.db"

        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cached_files` (" +
                        "`id` TEXT NOT NULL, `folder` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`size` INTEGER NOT NULL, `contentType` TEXT NOT NULL, " +
                        "`changedAt` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
            }

            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // Not exercised: this test drives the upgrade manually via MIGRATION_1_2.
            }
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO cached_files (id, folder, name, size, contentType, changedAt) " +
                    "VALUES ('a', '/', 'a.txt', 10, 'text/plain', '2026-01-01T00:00:00Z')",
            )

            MIGRATION_1_2.migrate(db)

            db.query("SELECT * FROM cached_files WHERE id = 'a'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val sha256Index = cursor.getColumnIndexOrThrow("sha256")
                val mtimeIndex = cursor.getColumnIndexOrThrow("mtime")
                assertTrue(cursor.isNull(sha256Index))
                assertTrue(cursor.isNull(mtimeIndex))
                assertEquals("a.txt", cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }
}
