package app.fastdrive.android.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.fastdrive.android.data.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Same Robolectric-backed approach as `CachedFileDaoTest`: Room's generated DAO code needs a real
 * SQLite implementation, which Robolectric supplies without needing an emulator.
 *
 * Proves the round-trip Task 6's orchestration relies on: rows inserted into `sync_base` /
 * `sync_remote` / `sync_hashes` come back out, via the DAOs, as the [Snapshot] shape `plan()`
 * consumes — including `RemoteEntry`'s id-keyed table re-keying to path in the snapshot map.
 */
@RunWith(RobolectricTestRunner::class)
class SyncIndexTest {

    private lateinit var database: AppDatabase
    private lateinit var baseDao: BaseDao
    private lateinit var remoteDao: RemoteDao
    private lateinit var hashDao: HashDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        baseDao = database.baseDao()
        remoteDao = database.remoteDao()
        hashDao = database.hashDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun baseSnapshotRoundTripsByPath() = runBlocking {
        val entries = listOf(
            BaseEntry(path = "/a.txt", id = "id-a", size = 10, sha256 = "aaa", mtime = "2026-01-01T00:00:00Z", rev = "id-a:1"),
            BaseEntry(path = "/b.txt", id = null, size = 20, sha256 = null, mtime = null, rev = null),
        )

        baseDao.upsertAll(entries)

        val snapshot = baseDao.getAll().toSnapshot()
        assertEquals(2, snapshot.size)
        assertEquals(Entry(path = "/a.txt", size = 10, sha256 = "aaa", mtime = "2026-01-01T00:00:00Z", id = "id-a", rev = "id-a:1"), snapshot["/a.txt"])
        assertEquals(Entry(path = "/b.txt", size = 20, sha256 = null, mtime = null, id = null, rev = null), snapshot["/b.txt"])
    }

    @Test
    fun remoteSnapshotIsKeyedByPathNotId() = runBlocking {
        remoteDao.upsertAll(
            listOf(
                RemoteEntry(id = "id-1", path = "/docs/report.pdf", size = 100, sha256 = "hash1", mtime = "2026-01-01T00:00:00Z", version = 3, changedAt = "2026-01-01T00:00:00Z"),
            ),
        )

        val snapshot = remoteDao.getAll().toSnapshot()

        assertEquals(1, snapshot.size)
        val entry = snapshot["/docs/report.pdf"]
        assertEquals("id-1", entry?.id)
        assertEquals("id-1:3", entry?.rev)
        assertEquals(100L, entry?.size)
    }

    @Test
    fun remoteSnapshotExcludesDeletedRows() = runBlocking {
        remoteDao.upsertAll(
            listOf(
                RemoteEntry(id = "id-1", path = "/live.txt", size = 5, sha256 = null, mtime = null, version = 1, changedAt = "2026-01-01T00:00:00Z", deleted = false),
                RemoteEntry(id = "id-2", path = "/gone.txt", size = 5, sha256 = null, mtime = null, version = 2, changedAt = "2026-01-01T00:00:00Z", deleted = true),
            ),
        )

        val snapshot = remoteDao.getAll().toSnapshot()

        assertEquals(1, snapshot.size)
        assertEquals("/live.txt", snapshot.keys.single())
    }

    @Test
    fun hashLookupMatchesOnPathSizeAndMtime() = runBlocking {
        hashDao.upsert(HashEntry(path = "/a.txt", size = 10, mtimeMs = 12345L, sha256 = "deadbeef"))

        val hit = hashDao.findHash("/a.txt", 10, 12345L)
        val missSize = hashDao.findHash("/a.txt", 11, 12345L)
        val missMtime = hashDao.findHash("/a.txt", 10, 99999L)

        assertEquals("deadbeef", hit?.sha256)
        assertNull(missSize)
        assertNull(missMtime)
    }

    @Test
    fun deleteByPathRemovesOnlyThatRow() = runBlocking {
        baseDao.upsertAll(
            listOf(
                BaseEntry(path = "/keep.txt", id = "k", size = 1, sha256 = null, mtime = null, rev = null),
                BaseEntry(path = "/drop.txt", id = "d", size = 2, sha256 = null, mtime = null, rev = null),
            ),
        )

        baseDao.deleteByPath("/drop.txt")

        val remaining = baseDao.observeAll().first()
        assertEquals(listOf("/keep.txt"), remaining.map { it.path })
    }
}
