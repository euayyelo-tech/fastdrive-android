package app.fastdrive.android.sync

import android.net.Uri
import app.fastdrive.android.upload.FileAccess
import app.fastdrive.android.upload.PickedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Exercises [LocalScanner.walk] directly against a hand-built [FakeTreeNode]/[FakeHashDao]/
 * [FakeFileAccess] trio rather than a real `DocumentFile` tree — no fake `DocumentsProvider`
 * needed, mirroring how [MergeEngineTest] drives the pure engine with plain data. Still runs
 * under Robolectric (as [ContentResolverFileAccessTest] and [SyncSettingsTest] do) because
 * `android.net.Uri.parse` is a framework stub outside it.
 */
@RunWith(RobolectricTestRunner::class)
class LocalScannerTest {

    /** `content://fake/<path>` — unique per path so the fakes can key off it like a real tree would. */
    private fun uriFor(path: String): Uri = Uri.parse("content://fake/$path")

    private class FakeTreeNode(
        override val name: String,
        override val isDirectory: Boolean,
        override val length: Long = 0,
        override val lastModified: Long = 0,
        private val kids: List<FakeTreeNode> = emptyList(),
        uri: Uri = Uri.parse("content://fake/$name"),
    ) : TreeNode {
        override val uri: Uri = uri
        override fun children(): List<TreeNode> = kids
    }

    private fun file(name: String, size: Long, mtimeMs: Long, uri: Uri): FakeTreeNode =
        FakeTreeNode(name = name, isDirectory = false, length = size, lastModified = mtimeMs, uri = uri)

    private fun dir(name: String, vararg kids: FakeTreeNode): FakeTreeNode =
        FakeTreeNode(name = name, isDirectory = true, kids = kids.toList())

    private class FakeHashDao : HashDao {
        val store = mutableMapOf<String, HashEntry>()
        val upserts = mutableListOf<HashEntry>()

        override fun observeAll() = MutableStateFlow(store.values.toList())
        override suspend fun getAll(): List<HashEntry> = store.values.toList()
        override suspend fun upsert(entry: HashEntry) {
            store[entry.path] = entry
            upserts.add(entry)
        }
        override suspend fun upsertAll(entries: List<HashEntry>) {
            entries.forEach { store[it.path] = it }
        }
        override suspend fun deleteByPath(path: String) {
            store.remove(path)
        }
        override suspend fun deleteAll() {
            store.clear()
        }
        override suspend fun findHash(path: String, size: Long, mtimeMs: Long): HashEntry? {
            val hit = store[path] ?: return null
            return if (hit.size == size && hit.mtimeMs == mtimeMs) hit else null
        }
    }

    private class FakeFileAccess(private val bytes: Map<Uri, ByteArray>) : FileAccess {
        val opened = mutableListOf<Uri>()
        override fun stat(uri: Uri): PickedFile = throw UnsupportedOperationException("not needed for scanning")
        override fun openStream(uri: Uri): InputStream {
            opened.add(uri)
            return ByteArrayInputStream(bytes.getValue(uri))
        }
    }

    // sha256("hello world") — computed independently of LocalScanner to check its output.
    private val helloWorldSha = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"

    @Test
    fun `unchanged file reuses the cached hash without re-reading bytes`() = runBlocking {
        val uri = uriFor("report.pdf")
        val hashDao = FakeHashDao().apply {
            store["report.pdf"] = HashEntry(path = "report.pdf", size = 11, mtimeMs = 1_000, sha256 = "cached-hash")
        }
        val fileAccess = FakeFileAccess(mapOf(uri to "hello world".toByteArray()))
        val root = dir("root", file("report.pdf", size = 11, mtimeMs = 1_000, uri = uri))

        val result = LocalScanner.walk(root, hashDao, fileAccess)

        assertEquals("cached-hash", result.snapshot.getValue("report.pdf").sha256)
        assertTrue("should not open the stream when the cache hit matches", fileAccess.opened.isEmpty())
        assertTrue("should not upsert when nothing changed", hashDao.upserts.isEmpty())
    }

    @Test
    fun `changed file is re-hashed and the cache is updated`() = runBlocking {
        val uri = uriFor("report.pdf")
        val hashDao = FakeHashDao().apply {
            // Stale entry: same path, but size/mtime no longer match the file on disk.
            store["report.pdf"] = HashEntry(path = "report.pdf", size = 999, mtimeMs = 1, sha256 = "stale-hash")
        }
        val fileAccess = FakeFileAccess(mapOf(uri to "hello world".toByteArray()))
        val root = dir("root", file("report.pdf", size = 11, mtimeMs = 2_000, uri = uri))

        val result = LocalScanner.walk(root, hashDao, fileAccess)

        assertEquals(helloWorldSha, result.snapshot.getValue("report.pdf").sha256)
        assertEquals(listOf(uri), fileAccess.opened)
        assertEquals(1, hashDao.upserts.size)
        assertEquals(HashEntry("report.pdf", 11, 2_000, helloWorldSha), hashDao.upserts.single())
    }

    @Test
    fun `an unsyncable path is excluded from the snapshot and recorded as skipped`() = runBlocking {
        val goodUri = uriFor("notes.txt")
        val hashDao = FakeHashDao()
        val fileAccess = FakeFileAccess(mapOf(goodUri to "hi".toByteArray()))
        val root = dir(
            "root",
            file("notes.txt", size = 2, mtimeMs = 500, uri = goodUri),
            // A leading dot makes this file name unsyncable (see MergeEngine.unsyncable).
            file(".hidden", size = 4, mtimeMs = 500, uri = uriFor(".hidden")),
        )

        val result = LocalScanner.walk(root, hashDao, fileAccess)

        assertTrue(result.snapshot.containsKey("notes.txt"))
        assertNull(result.snapshot[".hidden"])
        assertEquals(listOf(".hidden"), result.skipped)
    }

    @Test
    fun `an unsyncable directory is skipped whole, without descending into it`() = runBlocking {
        val hashDao = FakeHashDao()
        val fileAccess = FakeFileAccess(emptyMap())
        val root = dir(
            "root",
            dir(".git", file("config", size = 1, mtimeMs = 1, uri = uriFor(".git/config"))),
        )

        val result = LocalScanner.walk(root, hashDao, fileAccess)

        assertTrue(result.snapshot.isEmpty())
        assertEquals(listOf(".git"), result.skipped)
    }

    @Test
    fun `a file that throws while being read is skipped, not fatal to the scan`() = runBlocking {
        // Finding #7: a permissions hiccup or a flaky provider reading/hashing ONE file must not
        // abort the whole scan (which SyncOrchestrator would otherwise treat as fatal to the
        // entire pass, forever, until that one file's problem somehow resolves itself).
        val goodUri = uriFor("good.txt")
        val badUri = uriFor("bad.txt")
        val hashDao = FakeHashDao()
        val fileAccess = object : FileAccess {
            override fun stat(uri: Uri): PickedFile = throw UnsupportedOperationException("not needed for scanning")
            override fun openStream(uri: Uri): InputStream {
                if (uri == badUri) throw java.io.IOException("permission denied")
                return ByteArrayInputStream("hi".toByteArray())
            }
        }
        val root = dir(
            "root",
            file("bad.txt", size = 4, mtimeMs = 500, uri = badUri),
            file("good.txt", size = 2, mtimeMs = 500, uri = goodUri),
        )

        val result = LocalScanner.walk(root, hashDao, fileAccess)

        assertTrue(result.snapshot.containsKey("good.txt"))
        assertNull(result.snapshot["bad.txt"])
        assertEquals(listOf("bad.txt"), result.skipped)
    }

    @Test
    fun `builds relative paths for nested folders`() = runBlocking {
        val uri = uriFor("sub/deep.txt")
        val hashDao = FakeHashDao()
        val fileAccess = FakeFileAccess(mapOf(uri to "x".toByteArray()))
        val root = dir("root", dir("sub", file("deep.txt", size = 1, mtimeMs = 10, uri = uri)))

        val result = LocalScanner.walk(root, hashDao, fileAccess)

        val entry = result.snapshot.getValue("sub/deep.txt")
        assertEquals("sub/deep.txt", entry.path)
        assertNull(entry.id)
        assertNull(entry.rev)
    }
}
