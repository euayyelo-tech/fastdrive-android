package app.fastdrive.android.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Ports the three working-state tables of `fastdrive-app/desktop/src/engine/index-db.ts` to Room:
 *   sync_base    what this machine last knew both sides agreed on, per path (the "base" of the
 *                three-way merge in [MergeEngine]), with the server id
 *   sync_remote  the drive as the change feed has told it so far, by id
 *   sync_hashes  sha256 of local files by (path, size, mtime) so a rescan does not read every
 *                byte again
 *
 * Separate from Phase 1-2's `CachedFile` browsing cache (a UI list of what a folder currently
 * shows) — these three are the sync engine's own private bookkeeping, read and written once per
 * sync pass by whatever orchestrates [MergeEngine].
 */

@Entity(tableName = "sync_base")
data class BaseEntry(
    @PrimaryKey val path: String,
    val id: String?,
    val size: Long,
    val sha256: String?,
    val mtime: String?,
    val rev: String?,
)

@Entity(tableName = "sync_remote", indices = [Index("path")])
data class RemoteEntry(
    @PrimaryKey val id: String,
    val path: String,
    val size: Long,
    val sha256: String?,
    val mtime: String?,
    val version: Int,
    val changedAt: String,
    val deleted: Boolean = false,
)

@Entity(tableName = "sync_hashes")
data class HashEntry(
    @PrimaryKey val path: String,
    val size: Long,
    val mtimeMs: Long,
    val sha256: String,
)

/** `sync_remote`'s `rev` is derived, not stored — mirrors desktop's `revOf(id, version)`. */
fun revOf(id: String, version: Int): String = "$id:$version"

/**
 * Builds the [Snapshot] `plan()` consumes: keyed by path, base's own natural key.
 *
 * Named distinctly from [toRemoteSnapshot] rather than overloaded: both take a `List<T>` receiver,
 * which erases to the same JVM signature and the Kotlin compiler rejects as a platform
 * declaration clash.
 */
fun List<BaseEntry>.toBaseSnapshot(): Snapshot = associate { e ->
    e.path to Entry(path = e.path, size = e.size, sha256 = e.sha256, mtime = e.mtime, id = e.id, rev = e.rev)
}

/**
 * Builds the remote [Snapshot]: live files only (deleted rows are excluded by the DAO query, same
 * as desktop's `remoteSnapshot`), re-keyed from `id` (the table's primary key) to `path` (the
 * key every [Snapshot] uses).
 */
fun List<RemoteEntry>.toRemoteSnapshot(): Snapshot = associate { e ->
    e.path to Entry(path = e.path, size = e.size, sha256 = e.sha256, mtime = e.mtime, id = e.id, rev = revOf(e.id, e.version))
}

@Dao
interface BaseDao {
    @Query("SELECT * FROM sync_base")
    fun observeAll(): Flow<List<BaseEntry>>

    @Query("SELECT * FROM sync_base")
    suspend fun getAll(): List<BaseEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: BaseEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<BaseEntry>)

    @Query("DELETE FROM sync_base WHERE path = :path")
    suspend fun deleteByPath(path: String)

    /** Wipes the whole table — used by [app.fastdrive.android.auth.handleUnauthorized] on
     *  sign-out so a stale base from the previous account never gets reconciled against a new
     *  account's remote snapshot. */
    @Query("DELETE FROM sync_base")
    suspend fun deleteAll()
}

@Dao
interface RemoteDao {
    @Query("SELECT * FROM sync_remote WHERE deleted = 0")
    fun observeAll(): Flow<List<RemoteEntry>>

    @Query("SELECT * FROM sync_remote WHERE deleted = 0")
    suspend fun getAll(): List<RemoteEntry>

    /** Unlike [getAll], not filtered by `deleted` — the counterpart of desktop's `remoteById`,
     *  used by [SyncOrchestrator]'s `mirror()` to read back whatever this table currently has for
     *  an id (any state) before folding a patch into it. */
    @Query("SELECT * FROM sync_remote WHERE id = :id")
    suspend fun findById(id: String): RemoteEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RemoteEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<RemoteEntry>)

    @Query("DELETE FROM sync_remote WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Wipes the whole table — see [BaseDao.deleteAll]'s doc comment; the same sign-out reasoning
     *  applies to `sync_remote`. */
    @Query("DELETE FROM sync_remote")
    suspend fun deleteAll()
}

@Dao
interface HashDao {
    @Query("SELECT * FROM sync_hashes")
    fun observeAll(): Flow<List<HashEntry>>

    @Query("SELECT * FROM sync_hashes")
    suspend fun getAll(): List<HashEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: HashEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<HashEntry>)

    @Query("DELETE FROM sync_hashes WHERE path = :path")
    suspend fun deleteByPath(path: String)

    /** Lets the orchestration layer skip re-hashing a file whose (size, mtime) haven't changed. */
    @Query("SELECT * FROM sync_hashes WHERE path = :path AND size = :size AND mtimeMs = :mtimeMs")
    suspend fun findHash(path: String, size: Long, mtimeMs: Long): HashEntry?

    /** Wipes the whole table — see [BaseDao.deleteAll]'s doc comment; a stale hash cache from a
     *  previous account is harmless on its own (it's just a re-hash optimization) but is cleared
     *  alongside the other two tables for a clean account boundary. */
    @Query("DELETE FROM sync_hashes")
    suspend fun deleteAll()
}
