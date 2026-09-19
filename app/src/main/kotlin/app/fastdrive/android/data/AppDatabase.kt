package app.fastdrive.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CachedFile::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cachedFileDao(): CachedFileDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "fastdrive.db")
                .build()
                .also { instance = it }
        }
    }
}
