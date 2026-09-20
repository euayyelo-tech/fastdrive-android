package app.fastdrive.android.sync

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.data.AppDatabase
import app.fastdrive.android.upload.FileAccess
import app.fastdrive.android.upload.PickedFile
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Exercises [SyncOrchestrator.executePlan] — the testable core — directly against a hand-built
 * plan (bypassing [plan] itself, which [MergeEngineTest] already covers on its own) and fakes for
 * every I/O seam: a real [DriveApi] backed by a fake `OkHttpClient` interceptor (the same technique
 * [app.fastdrive.android.upload.UploadWorkerTest] uses), a fake [LocalFileStore], a fake
 * [FileAccess], and a real in-memory Room [BaseDao] (same approach as [SyncIndexTest]) so the
 * post-pass `sync_base` state can be asserted directly.
 */
@RunWith(RobolectricTestRunner::class)
class SyncOrchestratorTest {

    private lateinit var database: AppDatabase
    private lateinit var baseDao: BaseDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        baseDao = database.baseDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private class FakeLocalFileStore : LocalFileStore {
        val files = mutableMapOf<String, Uri>()
        val written = mutableMapOf<String, ByteArrayOutputStream>()
        val trashed = mutableListOf<String>()
        val moved = mutableListOf<Pair<String, String>>()
        var trashSucceeds = true
        var moveSucceeds = true

        override fun uriFor(path: String): Uri? = files[path]

        override fun openForWrite(path: String): OutputStream {
            val out = ByteArrayOutputStream()
            written[path] = out
            return out
        }

        override fun trash(path: String): Boolean {
            trashed.add(path)
            return trashSucceeds
        }

        override fun move(from: String, to: String): Boolean {
            moved.add(from to to)
            if (moveSucceeds) files[to] = files.remove(from) ?: Uri.parse("content://fake/$to")
            return moveSucceeds
        }
    }

    private class FakeFileAccess(private val content: Map<Uri, ByteArray>, private val names: Map<Uri, String> = emptyMap()) : FileAccess {
        override fun stat(uri: Uri): PickedFile {
            val bytes = content.getValue(uri)
            return PickedFile(uri, names[uri] ?: "file.txt", bytes.size.toLong(), "text/plain")
        }
        override fun openStream(uri: Uri): InputStream = ByteArrayInputStream(content.getValue(uri))
    }

    private fun bodyString(request: okhttp3.Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun jsonResponse(request: okhttp3.Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    /**
     * One fake server standing in for every DriveApi call [executePlan] can make across all eight
     * [Action] types in a single pass: upload-url/PUT/confirm (twice — a plain upload and a
     * conflict's), download-url/GET, DELETE, and PATCH.
     */
    private fun fakeServer(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val body = bodyString(request)
            val (code, respBody) = when {
                request.url.encodedPath == "/api/files/upload-url" && body.contains("\"name\":\"upload.txt\"") ->
                    200 to """{"id":"id-upload-new","key":"k","url":"https://s3.example.com/put-upload","headers":{},"vault":false}"""
                request.url.encodedPath == "/api/files/upload-url" && body.contains("\"name\":\"conflict (conflicted copy, Pixel).txt\"") ->
                    200 to """{"id":"id-conflict-uploaded","key":"k","url":"https://s3.example.com/put-conflict","headers":{},"vault":false}"""
                request.url.toString() == "https://s3.example.com/put-upload" -> 200 to "{}"
                request.url.toString() == "https://s3.example.com/put-conflict" -> 200 to "{}"
                request.url.encodedPath == "/api/files/id-upload-new/confirm" -> 200 to "{}"
                request.url.encodedPath == "/api/files/id-conflict-uploaded/confirm" -> 200 to "{}"
                request.url.encodedPath == "/api/files/id-download/download" ->
                    200 to """{"url":"https://s3.example.com/get-download","vault":false}"""
                request.url.toString() == "https://s3.example.com/get-download" -> 200 to "downloaded-bytes"
                request.url.encodedPath == "/api/files/id-delrem" && request.method == "DELETE" -> 200 to "{}"
                request.url.encodedPath == "/api/files/id-move" && request.method == "PATCH" -> 200 to "{}"
                else -> 500 to """{"error":"unexpected ${request.method} ${request.url}"}"""
            }
            jsonResponse(request, code, respBody)
        }
        .build()

    private fun entry(path: String, size: Long, sha256: String, mtime: String, id: String? = null, rev: String? = null) =
        Entry(path = path, size = size, sha256 = sha256, mtime = mtime, id = id, rev = rev)

    @Test
    fun `a pass with one of each action type executes correctly and updates base`() = runBlocking {
        val client = fakeServer()
        val api = DriveApi(baseUrl = "https://example.com", client = client)
        val localStore = FakeLocalFileStore()

        val uploadUri = Uri.parse("content://fake/upload.txt")
        val conflictUri = Uri.parse("content://fake/conflict.txt")
        localStore.files["upload.txt"] = uploadUri
        localStore.files["conflict.txt"] = conflictUri
        localStore.files["remote-old.txt"] = Uri.parse("content://fake/remote-old.txt")

        val fileAccess = FakeFileAccess(
            content = mapOf(
                uploadUri to "hello world".toByteArray(),
                conflictUri to "confl6".toByteArray(),
            ),
            names = mapOf(uploadUri to "upload.txt", conflictUri to "conflict.txt"),
        )

        val local = mapOf(
            "upload.txt" to entry("upload.txt", 11, "uploadsha", "2026-01-01T00:00:00Z"),
            "new-name.txt" to entry("new-name.txt", 7, "movesha", "2026-01-03T00:00:00Z"),
            "conflict.txt" to entry("conflict.txt", 6, "conflictsha", "2026-01-04T00:00:00Z"),
        )
        val remote = mapOf(
            "download.txt" to entry("download.txt", 16, "downloadsha", "2026-01-02T00:00:00Z", id = "id-download", rev = "id-download:1"),
            "remote-new.txt" to entry("remote-new.txt", 8, "movelocalsha", "2026-01-05T00:00:00Z", id = "id-movelocal", rev = "id-movelocal:1"),
            "settle.txt" to entry("settle.txt", 9, "settlesha", "2026-01-06T00:00:00Z", id = "id-settle", rev = "id-settle:2"),
        )

        val actions = listOf(
            Action.Upload("upload.txt", replaceId = null),
            Action.Download("download.txt", id = "id-download"),
            Action.DeleteLocal("delete-local.txt"),
            Action.DeleteRemote("delete-remote.txt", id = "id-delrem"),
            Action.MoveRemote(from = "old-name.txt", to = "new-name.txt", id = "id-move"),
            Action.MoveLocal(from = "remote-old.txt", to = "remote-new.txt"),
            Action.Conflict(path = "conflict.txt", id = "id-conflict-remote", renamed = "conflict (conflicted copy, Pixel).txt"),
            Action.Settle(path = "settle.txt", id = "id-settle"),
        )

        val result = SyncOrchestrator.executePlan(
            actions = actions,
            local = local,
            remote = remote,
            api = api,
            httpClient = client,
            fileAccess = fileAccess,
            localStore = localStore,
            baseDao = baseDao,
            skipped = listOf("bad/.hidden-file"),
        )

        assertTrue("expected no failures, got ${result.failed}", result.failed.isEmpty())
        assertEquals(1, result.uploaded)
        assertEquals(1, result.downloaded)
        assertEquals(1, result.deletedLocal)
        assertEquals(1, result.deletedRemote)
        assertEquals(2, result.moved)
        assertEquals(1, result.conflicts)
        assertEquals(1, result.settled)
        assertEquals(listOf("bad/.hidden-file"), result.skipped)

        // Upload: base picks up the server's real id, entry data from the local snapshot.
        val base = baseDao.getAll().associateBy { it.path }
        assertEquals("id-upload-new", base.getValue("upload.txt").id)
        assertEquals(11L, base.getValue("upload.txt").size)

        // Download: written to the right local path, base mirrors the remote entry exactly.
        assertEquals("downloaded-bytes", localStore.written.getValue("download.txt").toString(Charsets.UTF_8))
        assertEquals("id-download:1", base.getValue("download.txt").rev)

        // DeleteLocal/DeleteRemote: trashed (not hard-deleted) locally, base entry gone for both.
        assertEquals(listOf("delete-local.txt"), localStore.trashed)
        assertNull(base["delete-local.txt"])
        assertNull(base["delete-remote.txt"])

        // MoveRemote: old path's base entry gone, new path's base entry present under the same id.
        assertNull(base["old-name.txt"])
        assertEquals("id-move", base.getValue("new-name.txt").id)

        // MoveLocal: local file store told to move, old path's base entry gone, new one mirrors remote.
        assertEquals(listOf("remote-old.txt" to "remote-new.txt"), localStore.moved)
        assertNull(base["remote-old.txt"])
        assertEquals("id-movelocal:1", base.getValue("remote-new.txt").rev)

        // Conflict: the remote's own id is untouched at `path`, but base[path] is settled against
        // the LOCAL bytes (not remote's) so this exact conflict isn't re-flagged next pass. The
        // uploaded copy lands at `renamed` under the NEW id the fake server returned.
        assertEquals("id-conflict-remote", base.getValue("conflict.txt").id)
        assertEquals("conflictsha", base.getValue("conflict.txt").sha256)
        assertEquals("id-conflict-uploaded", base.getValue("conflict (conflicted copy, Pixel).txt").id)

        // Settle: no file operation, base just mirrors the remote entry (including its rev).
        assertEquals("id-settle:2", base.getValue("settle.txt").rev)
    }

    @Test
    fun `a failed action is recorded but does not abort the rest of the pass`() = runBlocking {
        val client = fakeServer()
        val api = DriveApi(baseUrl = "https://example.com", client = client)
        val localStore = FakeLocalFileStore()
        // Deliberately never registers "missing-upload.txt" in localStore.files, so uriFor()
        // returns null and the Upload action throws before touching the network.
        val fileAccess = FakeFileAccess(content = emptyMap())

        val local = mapOf("missing-upload.txt" to entry("missing-upload.txt", 3, "sha", "2026-01-01T00:00:00Z"))
        val remote = mapOf("download.txt" to entry("download.txt", 16, "downloadsha", "2026-01-02T00:00:00Z", id = "id-download", rev = "id-download:1"))

        val actions = listOf(
            Action.Upload("missing-upload.txt", replaceId = null),
            Action.Download("download.txt", id = "id-download"),
        )

        val result = SyncOrchestrator.executePlan(
            actions = actions, local = local, remote = remote, api = api, httpClient = client,
            fileAccess = fileAccess, localStore = localStore, baseDao = baseDao,
        )

        assertEquals(1, result.failed.size)
        assertEquals("missing-upload.txt", result.failed[0].path)
        assertEquals(0, result.uploaded)
        // The second action still ran and succeeded despite the first one failing.
        assertEquals(1, result.downloaded)
        assertEquals("downloaded-bytes", localStore.written.getValue("download.txt").toString(Charsets.UTF_8))
        assertEquals("id-download", baseDao.getAll().single { it.path == "download.txt" }.id)
        // No base entry was written for the path whose action failed.
        assertTrue(baseDao.getAll().none { it.path == "missing-upload.txt" })
    }

    @Test
    fun `skipped paths from the scan are surfaced in the result untouched`() = runBlocking {
        val client = fakeServer()
        val api = DriveApi(baseUrl = "https://example.com", client = client)
        val localStore = FakeLocalFileStore()
        val fileAccess = FakeFileAccess(content = emptyMap())

        val result = SyncOrchestrator.executePlan(
            actions = emptyList(), local = emptyMap(), remote = emptyMap(), api = api, httpClient = client,
            fileAccess = fileAccess, localStore = localStore, baseDao = baseDao,
            skipped = listOf(".hidden", "a/name:with*bad?chars"),
        )

        assertEquals(listOf(".hidden", "a/name:with*bad?chars"), result.skipped)
        assertTrue(result.failed.isEmpty())
        assertTrue(baseDao.getAll().isEmpty())
    }
}
