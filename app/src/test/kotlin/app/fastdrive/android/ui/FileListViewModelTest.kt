package app.fastdrive.android.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.fastdrive.android.api.ApiException
import app.fastdrive.android.api.ChangesPage
import app.fastdrive.android.api.Cursor
import app.fastdrive.android.api.GoneEntry
import app.fastdrive.android.api.RemoteFile
import app.fastdrive.android.auth.TokenAccess
import app.fastdrive.android.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A plain in-memory fake, so tests don't need a real TokenStore — Robolectric's JVM has no
 *  AndroidKeyStore provider, so constructing EncryptedSharedPreferences there throws. */
private class FakeTokenAccess(initial: String? = null) : TokenAccess {
    private var token: String? = initial
    override fun getToken(): String? = token
    override fun setToken(token: String?) { this.token = token }
    override fun clear() { token = null }
}

/**
 * Room's DAO needs a real Android environment (see CachedFileDaoTest), so this runs under
 * Robolectric like the other data-layer tests. The actual HTTP layer is swapped out for a plain
 * fake `changesFetcher` function, and TokenStore for a `FakeTokenAccess`, so pagination/upsert/
 * delete/cursor-advance and the error paths can be exercised without a real DriveApi, TokenStore,
 * or network stack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FileListViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var tokenStore: FakeTokenAccess
    private lateinit var context: Context
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tokenStore = FakeTokenAccess()
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun remoteFile(id: String, changedAt: String) = RemoteFile(
        id = id,
        folder = "/",
        name = "$id.txt",
        size = 1,
        contentType = "text/plain",
        version = 1,
        changedAt = changedAt,
    )

    @Test
    fun `refresh follows more across pages until the last one`() = runTest(dispatcher) {
        val page1 = ChangesPage(
            files = listOf(remoteFile("a", "2026-01-01T00:00:00Z")),
            gone = emptyList(),
            cursor = Cursor(at = "2026-01-01T00:00:00Z", id = "a"),
            more = true,
            now = "2026-01-01T00:00:01Z",
        )
        val page2 = ChangesPage(
            files = listOf(remoteFile("b", "2026-01-01T00:00:02Z")),
            gone = listOf(GoneEntry(id = "a", changedAt = "2026-01-01T00:00:02Z")),
            cursor = Cursor(at = "2026-01-01T00:00:02Z", id = "b"),
            more = false,
            now = "2026-01-01T00:00:03Z",
        )
        var calls = 0
        val viewModel = FileListViewModel(database, tokenStore, context) { _ ->
            calls++
            if (calls == 1) page1 else page2
        }

        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, calls)
        val remaining = database.cachedFileDao().observeAll().first()
        assertEquals(listOf("b.txt"), remaining.map { it.name })
        assertNull(viewModel.refreshError.value)
        assertEquals(false, viewModel.signedOut.value)
    }

    @Test
    fun `a 401 clears the token and signals signed out`() = runTest(dispatcher) {
        tokenStore.setToken("some-token")
        val viewModel = FileListViewModel(database, tokenStore, context) { _ ->
            throw ApiException(401, "unauthorized")
        }

        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.signedOut.value)
        assertNull(tokenStore.getToken())
        assertNull(viewModel.refreshError.value)
    }

    @Test
    fun `a non-auth failure surfaces refreshError and leaves the cache alone`() = runTest(dispatcher) {
        database.cachedFileDao().upsertAll(
            listOf(
                app.fastdrive.android.data.CachedFile(
                    id = "existing",
                    folder = "/",
                    name = "existing.txt",
                    size = 5,
                    contentType = "text/plain",
                    changedAt = "2026-01-01T00:00:00Z",
                ),
            ),
        )
        val viewModel = FileListViewModel(database, tokenStore, context) { _ ->
            throw java.io.IOException("network down")
        }

        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.signedOut.value)
        assertEquals("network down", viewModel.refreshError.value)
        val remaining = database.cachedFileDao().observeAll().first()
        assertEquals(listOf("existing.txt"), remaining.map { it.name })
    }
}
