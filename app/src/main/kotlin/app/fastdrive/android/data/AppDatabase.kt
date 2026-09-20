package app.fastdrive.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.fastdrive.android.sync.BaseDao
import app.fastdrive.android.sync.BaseEntry
import app.fastdrive.android.sync.HashDao
import app.fastdrive.android.sync.HashEntry
import app.fastdrive.android.sync.RemoteDao
import app.fastdrive.android.sync.RemoteEntry

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cached_files ADD COLUMN sha256 TEXT")
        db.execSQL("ALTER TABLE cached_files ADD COLUMN mtime TEXT")
    }
}

/**
 * Adds the sync engine's own working state: three brand-new tables (not column additions), so
 * this migration is three CREATE TABLEs rather than Phase 2's ALTER TABLE. Mirrors
 * `fastdrive-app/desktop/src/engine/index-db.ts`'s `base`/`remote`/`hashes` tables — see
 * `sync/SyncIndex.kt` for the entity definitions these statements must match column-for-column.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_base` (" +
                "`path` TEXT NOT NULL, `id` TEXT, `size` INTEGER NOT NULL, `sha256` TEXT, " +
                "`mtime` TEXT, `rev` TEXT, PRIMARY KEY(`path`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_remote` (" +
                "`id` TEXT NOT NULL, `path` TEXT NOT NULL, `size` INTEGER NOT NULL, `sha256` TEXT, " +
                "`mtime` TEXT, `version` INTEGER NOT NULL, `changedAt` TEXT NOT NULL, " +
                "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_remote_path` ON `sync_remote` (`path`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_hashes` (" +
                "`path` TEXT NOT NULL, `size` INTEGER NOT NULL, `mtimeMs` INTEGER NOT NULL, " +
                "`sha256` TEXT NOT NULL, PRIMARY KEY(`path`))",
        )
    }
}

@Database(
    entities = [CachedFile::class, BaseEntry::class, RemoteEntry::class, HashEntry::class],
    version = 3,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cachedFileDao(): CachedFileDao
    abstract fun baseDao(): BaseDao
    abstract fun remoteDao(): RemoteDao
    abstract fun hashDao(): HashDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "fastdrive.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
