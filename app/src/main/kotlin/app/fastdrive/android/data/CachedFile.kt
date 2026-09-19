package app.fastdrive.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_files")
data class CachedFile(
    @PrimaryKey val id: String,
    val folder: String,
    val name: String,
    val size: Long,
    val contentType: String,
    val changedAt: String,
)
