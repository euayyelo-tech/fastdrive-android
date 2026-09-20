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
    private lateinit var remoteDao: RemoteDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        baseDao = database.baseDao()
        remoteDao = database.remoteDao()
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
        val committed = mutableListOf<String>()
        val aborted = mutableListOf<String>()
        var trashSucceeds = true
        var moveSucceeds = true

        /** What [statLocal] returns for a given path — settable per test to simulate the REAL
         *  on-disk (size, mtime) a write left behind, distinct from whatever a remote entry claims.
         *  Defaults to nothing set, so existing tests that never configure this keep the pre-Finding-#2
         *  behavior of falling back to the remote entry's own claimed values in SyncOrchestrator. */
        val localStats = mutableMapOf<String, LocalStat>()

        override fun uriFor(path: String): Uri? = files[path]

        override fun openForWrite(path: String): OutputStream {
            val out = ByteArrayOutputStream()
            written[path] = out
            return out
        }

        override fun commitWrite(path: String) {
            committed.add(path)
        }

        override fun abortWrite(path: String) {
            aborted.add(path)
        }

        override fun statLocal(path: String): LocalStat? = localStats[path]

        override fun trash(path: String): Boolean {
            trashed.add(path)
            return trashSucceeds
        }

        override fun move(from: String, to: String): Boolean {
            moved.add(from to to)
            if (moveSucceeds) files[to] = files.remove(from) ?: Uri.parse("content://fake/$to")
            return moveSucceeds
        }

        override fun pruneTrash(retentionMs: Long): Int = 0
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
                request.url.encodedPath == "/api/files/id-conflict-remote/download" ->
                    200 to """{"url":"https://s3.example.com/get-conflict-remote","vault":false}"""
                request.url.toString() == "https://s3.example.com/get-conflict-remote" -> 200 to "conflict-remote-bytes"
                request.url.encodedPath == "/api/files/id-delrem" && request.method == "DELETE" -> 200 to "{}"
                request.url.encodedPath == "/api/files/id-move" && request.method == "PATCH" -> 200 to "{}"
                else -> 500 to """{"error":"unexpected ${request.method} ${request.url}"}"""
            }
            jsonResponse(request, code, respBody)
        }
        .build()

    private fun entry(path: String, size: Long, sha256: String?, mtime: String, id: String? = null, rev: String? = null) =
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
            // The drive's own copy at `conflict.txt` — what the Conflict action downloads back
            // under the original path once this machine's copy has stepped aside.
            "conflict.txt" to entry("conflict.txt", 20, "conflictremotesha", "2026-01-07T00:00:00Z", id = "id-conflict-remote", rev = "id-conflict-remote:1"),
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
            remoteDao = remoteDao,
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

        // DeleteRemote: sync_remote is mirrored to deleted=true too (not just sync_base dropped) —
        // otherwise a delayed/failed next pull would still show this id live at this path and the
        // next plan() would resurrect it with a spurious Download.
        assertEquals(true, remoteDao.findById("id-delrem")?.deleted)

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

        // MoveRemote: old path's base entry gone, new path's base entry present under the same id,
        // and sync_remote is mirrored to the new path too (not just sync_base).
        assertNull(base["old-name.txt"])
        assertEquals("id-move", base.getValue("new-name.txt").id)
        assertEquals("id-move:1", base.getValue("new-name.txt").rev)
        assertEquals("new-name.txt", remoteDao.findById("id-move")?.path)

        // MoveLocal: local file store told to move, old path's base entry gone, new one mirrors remote.
        // (Conflict, below, also calls localStore.move() — MoveLocal is ordered before it.)
        assertEquals(listOf("remote-old.txt" to "remote-new.txt"), localStore.moved.take(1))
        assertNull(base["remote-old.txt"])
        assertEquals("id-movelocal:1", base.getValue("remote-new.txt").rev)

        // Conflict: BOTH copies now exist on disk. This machine's copy was moved aside to
        // `renamed` and uploaded fresh; the drive's copy came down under the ORIGINAL `path`.
        assertEquals(listOf("conflict.txt" to "conflict (conflicted copy, Pixel).txt"), localStore.moved.drop(1))
        assertEquals("conflict-remote-bytes", localStore.written.getValue("conflict.txt").toString(Charsets.UTF_8))
        assertEquals("id-conflict-remote", base.getValue("conflict.txt").id)
        assertEquals("conflictremotesha", base.getValue("conflict.txt").sha256)
        assertEquals("id-conflict-remote:1", base.getValue("conflict.txt").rev)
        assertEquals("id-conflict-uploaded", base.getValue("conflict (conflicted copy, Pixel).txt").id)
        assertEquals("conflictsha", base.getValue("conflict (conflicted copy, Pixel).txt").sha256)
        assertEquals("id-conflict-uploaded:1", base.getValue("conflict (conflicted copy, Pixel).txt").rev)
        assertEquals("conflict (conflicted copy, Pixel).txt", remoteDao.findById("id-conflict-uploaded")?.path)

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
            fileAccess = fileAccess, localStore = localStore, baseDao = baseDao, remoteDao = remoteDao,
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
            fileAccess = fileAccess, localStore = localStore, baseDao = baseDao, remoteDao = remoteDao,
            skipped = listOf(".hidden", "a/name:with*bad?chars"),
        )

        assertEquals(listOf(".hidden", "a/name:with*bad?chars"), result.skipped)
        assertTrue(result.failed.isEmpty())
        assertTrue(baseDao.getAll().isEmpty())
    }

    @Test
    fun `an upload is skipped, not failed, when the file is still being written`() = runBlocking {
        val client = fakeServer()
        val api = DriveApi(baseUrl = "https://example.com", client = client)
        val localStore = FakeLocalFileStore()
        val uploadUri = Uri.parse("content://fake/upload.txt")
        localStore.files["upload.txt"] = uploadUri
        // The scan recorded this file at 11 bytes ("hello world"), but re-stat'ing it right before
        // upload finds only 5 bytes on disk right now — it's still being written to since the scan
        // ran. fakeServer() has no route for upload-url/PUT here, so if the guard didn't fire and
        // the upload proceeded anyway, this would blow up with the interceptor's 500 fallback
        // instead of quietly skipping.
        val fileAccess = FakeFileAccess(
            content = mapOf(uploadUri to "howdy".toByteArray()),
            names = mapOf(uploadUri to "upload.txt"),
        )
        val local = mapOf("upload.txt" to entry("upload.txt", 11, "uploadsha", "2026-01-01T00:00:00Z"))

        val result = SyncOrchestrator.executePlan(
            actions = listOf(Action.Upload("upload.txt", replaceId = null)),
            local = local, remote = emptyMap(), api = api, httpClient = client,
            fileAccess = fileAccess, localStore = localStore, baseDao = baseDao, remoteDao = remoteDao,
        )

        // Not a failure — a benign, silent defer to the next pass.
        assertTrue("expected no failures, got ${result.failed}", result.failed.isEmpty())
        assertEquals(0, result.uploaded)
        assertTrue("no base row should be written for a skipped upload", baseDao.getAll().isEmpty())
    }

    @Test
    fun `a 401 partway through a plan aborts the whole pass instead of recording a failure`() = runBlocking {
        val localStore = FakeLocalFileStore()
        val downloadUri = Uri.parse("content://fake/download.txt")
        val fileAccess = FakeFileAccess(content = emptyMap())

        // download.txt succeeds first (order 2). Deletes are ordered LAST (order 3, actionOrder()),
        // so both DeleteRemote actions run after it, in their original relative order: the first
        // hits a 401, and the second — which would otherwise succeed — must never run because the
        // whole pass stops dead right there.
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val (code, respBody) = when {
                    request.url.encodedPath == "/api/files/id-download/download" ->
                        200 to """{"url":"https://s3.example.com/get-download","vault":false}"""
                    request.url.toString() == "https://s3.example.com/get-download" -> 200 to "downloaded-bytes"
                    request.url.encodedPath == "/api/files/id-delrem-1" && request.method == "DELETE" ->
                        401 to """{"error":"unauthorized"}"""
                    request.url.encodedPath == "/api/files/id-delrem-2" && request.method == "DELETE" -> 200 to "{}"
                    else -> 500 to """{"error":"unexpected ${request.method} ${request.url}"}"""
                }
                jsonResponse(request, code, respBody)
            }
            .build()
        val api = DriveApi(baseUrl = "https://example.com", client = client)

        val actions = listOf(
            Action.Download("download.txt", id = "id-download"),
            Action.DeleteRemote("delete-remote-1.txt", id = "id-delrem-1"),
            Action.DeleteRemote("delete-remote-2.txt", id = "id-delrem-2"),
        )
        val remote = mapOf(
            "download.txt" to entry("download.txt", 16, "downloadsha", "2026-01-02T00:00:00Z", id = "id-download", rev = "id-download:1"),
        )

        var thrown: Exception? = null
        try {
            SyncOrchestrator.executePlan(
                actions = actions, local = emptyMap(), remote = remote, api = api, httpClient = client,
                fileAccess = fileAccess, localStore = localStore, baseDao = baseDao, remoteDao = remoteDao,
            )
        } catch (e: Exception) {
            thrown = e
        }

        assertTrue("expected the 401 to propagate out of executePlan", thrown is app.fastdrive.android.api.ApiException)
        assertEquals(401, (thrown as app.fastdrive.android.api.ApiException).status)
        // The action before the 401 completed and was recorded...
        assertEquals("id-download", baseDao.getAll().singleOrNull { it.path == "download.txt" }?.id)
        // ...but the one after the 401 (in execution order) never ran — the whole pass stopped
        // dead at the 401 rather than recording it as one failed action and carrying on: its
        // mirror() call (which only happens after a successful api.deleteFile) never fired.
        assertNull(remoteDao.findById("id-delrem-2"))
    }

    /**
     * The regression test for both Criticals: a full pass (an [Action.Upload] and an
     * [Action.Conflict]), then feed [plan] the state [executePlan] actually left behind — the
     * SAME `sync_base`/`sync_remote` rows it wrote, and an unchanged-since-the-pass `local`
     * snapshot standing in for "nothing changed on disk between the two passes". If [executePlan]
     * settled base/remote consistently, [plan] on that state has nothing left to do.
     *
     * Before the fix, this fails two different ways:
     *  - Critical 1 (upload never recorded mtime/sha256, `base.rev` left null): the SECOND
     *    `plan()` call sees `up.txt`'s base still carrying the pre-upload remote identity (nothing
     *    mirrored `sync_remote`), so `remote` still looks like the OLD bytes — a spurious
     *    `Action.Download` (or `Upload` again) comes back instead of an empty list.
     *  - Critical 2 (conflict never actually moved the local file aside or downloaded the drive's
     *    copy): the local disk state this test asserts against (both `conf.txt`'s drive copy AND
     *    the aside copy present) never came to be, and `sync_base`'s two rows don't correspond to
     *    real bytes at those two paths, so the second `plan()` again returns non-empty actions.
     */
    @Test
    fun `a second plan against the state a pass left behind has nothing left to do`() = runBlocking {
        val machine = "TestPixel"

        // up.txt: changed locally only -> Action.Upload (replacing the existing remote file).
        // conf.txt: changed on BOTH sides, differently -> Action.Conflict.
        val base1 = mapOf(
            "up.txt" to entry("up.txt", 5, "oldsha", "2026-01-01T00:00:00Z", id = "id-up", rev = "id-up:1"),
            "conf.txt" to entry("conf.txt", 5, "oldsha2", "2026-01-01T00:00:00Z", id = "id-conf", rev = "id-conf:1"),
        )
        val local1 = mapOf(
            "up.txt" to entry("up.txt", 9, "newlocalsha", "2026-02-01T00:00:00Z"),
            "conf.txt" to entry("conf.txt", 7, "localeditsha", "2026-02-01T00:00:00Z"),
        )
        val remote1 = mapOf(
            "up.txt" to entry("up.txt", 5, "oldsha", "2026-01-01T00:00:00Z", id = "id-up", rev = "id-up:1"),
            "conf.txt" to entry("conf.txt", 8, "remoteeditsha", "2026-02-02T00:00:00Z", id = "id-conf-v2", rev = "id-conf-v2:2"),
        )
        val actions1 = plan(base1, local1, remote1, machine)
        val conflictName = actions1.filterIsInstance<Action.Conflict>().single().renamed

        // sync_remote as it would really stand before this pass: the pull that built `remote1`
        // already wrote these rows.
        remoteDao.upsertAll(
            listOf(
                RemoteEntry(id = "id-up", path = "up.txt", size = 5, sha256 = "oldsha", mtime = "2026-01-01T00:00:00Z", version = 1, changedAt = "2026-01-01T00:00:00Z", deleted = false),
                RemoteEntry(id = "id-conf-v2", path = "conf.txt", size = 8, sha256 = "remoteeditsha", mtime = "2026-02-02T00:00:00Z", version = 2, changedAt = "2026-02-02T00:00:00Z", deleted = false),
            ),
        )

        val localStore = FakeLocalFileStore()
        val upUri = Uri.parse("content://fake/up.txt")
        val confUri = Uri.parse("content://fake/conf.txt")
        localStore.files["up.txt"] = upUri
        localStore.files["conf.txt"] = confUri
        val fileAccess = FakeFileAccess(
            content = mapOf(upUri to ByteArray(9), confUri to ByteArray(7)),
            names = mapOf(upUri to "up.txt", confUri to "conf.txt"),
        )

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val body = bodyString(request)
                val (code, respBody) = when {
                    // A replace keeps the SAME file id server-side, unlike a brand-new upload.
                    request.url.encodedPath == "/api/files/upload-url" && body.contains("\"name\":\"up.txt\"") ->
                        200 to """{"id":"id-up","key":"k","url":"https://s3.example.com/put-up","headers":{},"vault":false}"""
                    request.url.encodedPath == "/api/files/upload-url" && body.contains("\"name\":\"$conflictName\"") ->
                        200 to """{"id":"id-conf-aside","key":"k","url":"https://s3.example.com/put-conf-aside","headers":{},"vault":false}"""
                    request.url.toString() == "https://s3.example.com/put-up" -> 200 to "{}"
                    request.url.toString() == "https://s3.example.com/put-conf-aside" -> 200 to "{}"
                    request.url.encodedPath == "/api/files/id-up/confirm" -> 200 to "{}"
                    request.url.encodedPath == "/api/files/id-conf-aside/confirm" -> 200 to "{}"
                    request.url.encodedPath == "/api/files/id-conf-v2/download" ->
                        200 to """{"url":"https://s3.example.com/get-conf","vault":false}"""
                    request.url.toString() == "https://s3.example.com/get-conf" -> 200 to "remote-conf-bytes"
                    else -> 500 to """{"error":"unexpected ${request.method} ${request.url}"}"""
                }
                jsonResponse(request, code, respBody)
            }
            .build()
        val api = DriveApi(baseUrl = "https://example.com", client = client)

        val result = SyncOrchestrator.executePlan(
            actions = actions1, local = local1, remote = remote1, api = api, httpClient = client,
            fileAccess = fileAccess, localStore = localStore, baseDao = baseDao, remoteDao = remoteDao,
        )
        assertTrue("expected no failures, got ${result.failed}", result.failed.isEmpty())
        assertEquals(1, result.uploaded)
        assertEquals(1, result.conflicts)

        // The state a second pass would actually see: `sync_base`/`sync_remote` exactly as this
        // pass left them, and a local snapshot standing in for "the disk hasn't changed since" —
        // `up.txt` still holding its new bytes, `conf.txt` now holding what came down from the
        // drive, and the aside copy holding this machine's original edit.
        val base2 = baseDao.getAll().toBaseSnapshot()
        val remote2 = remoteDao.getAll().toRemoteSnapshot()
        val local2 = mapOf(
            "up.txt" to entry("up.txt", 9, "newlocalsha", "2026-02-01T00:00:00Z"),
            "conf.txt" to entry("conf.txt", 8, "remoteeditsha", "2026-02-02T00:00:00Z"),
            conflictName to entry(conflictName, 7, "localeditsha", "2026-02-01T00:00:00Z"),
        )

        val actions2 = plan(base2, local2, remote2, machine)
        assertTrue("expected an empty second plan, got $actions2", actions2.isEmpty())
    }

    /**
     * Finding #2's regression test: a remote entry with `sha256 == null` (e.g. an un-indexed
     * photo/video the server has never hashed) downloaded for the first time. Android's SAF has
     * no way to set the downloaded file's on-disk mtime to match the remote's claimed mtime
     * (unlike desktop's `utimes()` call), so the REAL local mtime after the write lands wherever
     * the OS put it — simulated here via [FakeLocalFileStore.localStats] returning a value far
     * from the remote's claimed "2026-01-01" mtime. Before the fix, `sync_base` was written from
     * the remote entry's claimed mtime, which would never again match what a real re-scan finds
     * on disk (no sha256 to fall back on for `same()`), so EVERY future pass would see the local
     * copy as "changed" and re-upload the whole file. After the fix, base is recorded from the
     * REAL local stat, so a second `plan()` — fed a local snapshot standing in for a real re-scan
     * finding that same real mtime — has nothing left to do.
     */
    @Test
    fun `a downloaded file with no remote sha256 does not spuriously re-upload on the next pass`() = runBlocking {
        val machine = "TestPixel"
        val remote1 = mapOf(
            "photo.jpg" to entry("photo.jpg", 1000, sha256 = null, mtime = "2026-01-01T00:00:00Z", id = "id-photo", rev = "id-photo:1"),
        )
        val actions1 = plan(emptyMap(), emptyMap(), remote1, machine)
        assertEquals(listOf(Action.Download("photo.jpg", "id-photo")), actions1)

        val localStore = FakeLocalFileStore()
        localStore.localStats["photo.jpg"] = LocalStat(size = 1000, mtime = "2026-09-20T12:00:00Z")
        val fileAccess = FakeFileAccess(content = emptyMap())

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val (code, body) = when {
                    request.url.encodedPath == "/api/files/id-photo/download" ->
                        200 to """{"url":"https://s3.example.com/get-photo","vault":false}"""
                    request.url.toString() == "https://s3.example.com/get-photo" -> 200 to "x".repeat(1000)
                    else -> 500 to """{"error":"unexpected ${request.method} ${request.url}"}"""
                }
                jsonResponse(request, code, body)
            }
            .build()
        val api = DriveApi(baseUrl = "https://example.com", client = client)

        val result = SyncOrchestrator.executePlan(
            actions = actions1, local = emptyMap(), remote = remote1, api = api, httpClient = client,
            fileAccess = fileAccess, localStore = localStore, baseDao = baseDao, remoteDao = remoteDao,
        )
        assertTrue("expected no failures, got ${result.failed}", result.failed.isEmpty())
        assertEquals(1, result.downloaded)
        assertEquals(listOf("photo.jpg"), localStore.committed)

        // Base was recorded from the REAL local stat, not the remote's claimed mtime.
        val base = baseDao.getAll().single { it.path == "photo.jpg" }
        assertEquals("2026-09-20T12:00:00Z", base.mtime)
        assertEquals("id-photo:1", base.rev)

        // A real re-scan would find the file at its actual on-disk mtime -- feeding that (plus the
        // base/remote state this pass left behind) back into plan() must yield nothing further.
        val base2 = baseDao.getAll().toBaseSnapshot()
        val remote2 = remoteDao.getAll().toRemoteSnapshot().ifEmpty { remote1 }
        val local2 = mapOf("photo.jpg" to entry("photo.jpg", 1000, sha256 = null, mtime = "2026-09-20T12:00:00Z"))

        val actions2 = plan(base2, local2, remote2, machine)
        assertTrue("expected an empty second plan, got $actions2", actions2.isEmpty())
    }
}
