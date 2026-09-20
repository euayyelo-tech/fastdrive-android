package app.fastdrive.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cached_files ADD COLUMN sha256 TEXT")
        db.execSQL("ALTER TABLE cached_files ADD COLUMN mtime TEXT")
    }
}

@Database(entities = [CachedFile::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cachedFileDao(): CachedFileDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "fastdrive.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
