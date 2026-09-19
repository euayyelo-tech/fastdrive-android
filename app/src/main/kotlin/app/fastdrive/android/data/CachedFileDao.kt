package app.fastdrive.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedFileDao {
    @Query("SELECT * FROM cached_files ORDER BY name ASC")
    fun observeAll(): Flow<List<CachedFile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<CachedFile>)

    @Query("DELETE FROM cached_files WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
