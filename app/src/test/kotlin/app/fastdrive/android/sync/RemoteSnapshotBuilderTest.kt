package app.fastdrive.android.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.fastdrive.android.api.ChangesPage
import app.fastdrive.android.api.Cursor
import app.fastdrive.android.api.GoneEntry
import app.fastdrive.android.api.RemoteFile
import app.fastdrive.android.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Same Robolectric-backed real-Room approach as [SyncIndexTest]: proves
 * [RemoteSnapshotBuilder.buildRemoteSnapshot] against a real `sync_remote` table, driven by a fake
 * `changesFetcher` sequence instead of a real [app.fastdrive.android.api.DriveApi]/network stack —
 * the same seam `FileListViewModel`'s tests use for `DriveApi.changes()`.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteSnapshotBuilderTest {

    private lateinit var database: AppDatabase
    private lateinit var remoteDao: RemoteDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        remoteDao = database.remoteDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun remoteFile(
        id: String,
        folder: String = "",
        name: String,
        size: Long = 10,
        version: Int = 1,
        changedAt: String = "2026-01-01T00:00:00Z",
        sha256: String? = null,
        mtime: String? = null,
    ) = RemoteFile(
        id = id, folder = folder, name = name, size = size, contentType = "text/plain",
        sha256 = sha256, mtime = mtime, version = version, changedAt = changedAt,
    )

    @Test
    fun `fresh sync with no prior cursor builds a full snapshot`() = runBlocking {
        val cursor1 = Cursor(at = "2026-01-01T00:00:00Z", id = "id-1")
        val page = ChangesPage(
            files = listOf(
                remoteFile(id = "id-1", folder = "docs", name = "a.txt"),
                remoteFile(id = "id-2", folder = "", name = "b.txt"),
            ),
            gone = emptyList(),
            cursor = cursor1,
            more = false,
            now = "2026-01-01T00:00:00Z",
        )

        val (snapshot, newCursor) = RemoteSnapshotBuilder.buildRemoteSnapshot(remoteDao, storedCursor = null) { cursor ->
            assertNull(cursor)
            page
        }

        assertEquals(2, snapshot.size)
        assertEquals("id-1", snapshot["docs/a.txt"]?.id)
        assertEquals("id-2", snapshot["b.txt"]?.id)
        assertEquals(cursor1, newCursor)
    }

    @Test
    fun `incremental update upserts changed files and removes gone ones`() = runBlocking {
        // Simulates a prior run having left this row in sync_remote.
        remoteDao.upsert(
            RemoteEntry(id = "id-old", path = "old.txt", size = 5, sha256 = null, mtime = null, version = 1, changedAt = "2026-01-01T00:00:00Z"),
        )
        remoteDao.upsert(
            RemoteEntry(id = "id-changed", path = "changed.txt", size = 5, sha256 = null, mtime = null, version = 1, changedAt = "2026-01-01T00:00:00Z"),
        )
        val storedCursor = Cursor(at = "2026-01-01T00:00:00Z", id = "id-changed")
        val newCursor = Cursor(at = "2026-01-02T00:00:00Z", id = "id-changed")

        val page = ChangesPage(
            files = listOf(remoteFile(id = "id-changed", name = "changed.txt", size = 99, version = 2)),
            gone = listOf(GoneEntry(id = "id-old", changedAt = "2026-01-02T00:00:00Z")),
            cursor = newCursor,
            more = false,
            now = "2026-01-02T00:00:00Z",
        )

        val (snapshot, resultCursor) = RemoteSnapshotBuilder.buildRemoteSnapshot(remoteDao, storedCursor) { cursor ->
            assertEquals(storedCursor, cursor)
            page
        }

        assertEquals(1, snapshot.size)
        assertEquals(99L, snapshot["changed.txt"]?.size)
        assertTrue("old.txt" !in snapshot)
        assertEquals(newCursor, resultCursor)
    }

    @Test
    fun `pagination across multiple pages works`() = runBlocking {
        val cursor1 = Cursor(at = "2026-01-01T00:00:00Z", id = "id-1")
        val cursor2 = Cursor(at = "2026-01-02T00:00:00Z", id = "id-2")
        val page1 = ChangesPage(
            files = listOf(remoteFile(id = "id-1", name = "a.txt")),
            gone = emptyList(),
            cursor = cursor1,
            more = true,
            now = "2026-01-01T00:00:00Z",
        )
        val page2 = ChangesPage(
            files = listOf(remoteFile(id = "id-2", name = "b.txt")),
            gone = emptyList(),
            cursor = cursor2,
            more = false,
            now = "2026-01-02T00:00:00Z",
        )
        val pages = ArrayDeque(listOf(page1, page2))
        val seenCursors = mutableListOf<Cursor?>()

        val (snapshot, resultCursor) = RemoteSnapshotBuilder.buildRemoteSnapshot(remoteDao, storedCursor = null) { cursor ->
            seenCursors.add(cursor)
            pages.removeFirst()
        }

        assertEquals(2, snapshot.size)
        assertEquals(listOf("a.txt", "b.txt").sorted(), snapshot.keys.sorted())
        assertEquals(listOf(null, cursor1), seenCursors)
        assertEquals(cursor2, resultCursor)
    }
}
