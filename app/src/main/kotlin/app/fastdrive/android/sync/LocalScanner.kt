package app.fastdrive.android.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.fastdrive.android.data.AppDatabase
import app.fastdrive.android.upload.ContentResolverFileAccess
import app.fastdrive.android.upload.FileAccess
import java.time.Instant

/**
 * Recursively walks the folder the user picked (Task 3's [SyncSettings]) into a [Snapshot] for
 * [plan] to compare against — the `local` side of the three-way merge. Reuses [FileAccess]
 * (Phase 2's seam over `ContentResolver`, already proven in the upload path) to stream each
 * file's bytes for hashing, and Task 2's [HashDao] to skip re-hashing a file whose (size, mtime)
 * haven't changed since the last scan.
 */

/**
 * The handful of `DocumentFile` members a scan actually needs, so tests can walk a hand-built
 * tree instead of standing up a fake `DocumentsProvider` — the same role [FileAccess] plays for
 * `ContentResolver` in the upload path. [DocumentFileNode] is the real implementation `scan()`
 * uses; tests supply their own.
 */
interface TreeNode {
    val name: String
    val uri: Uri
    val isDirectory: Boolean
    val length: Long
    val lastModified: Long
    fun children(): List<TreeNode>
}

class DocumentFileNode(private val doc: DocumentFile) : TreeNode {
    override val name: String get() = doc.name ?: ""
    override val uri: Uri get() = doc.uri
    override val isDirectory: Boolean get() = doc.isDirectory
    override val length: Long get() = doc.length()
    override val lastModified: Long get() = doc.lastModified()
    override fun children(): List<TreeNode> = doc.listFiles().map { DocumentFileNode(it) }
}

/**
 * `snapshot` is what [plan] consumes; `skipped` is every relative path (file or whole directory)
 * that failed [unsyncable], so the orchestration layer (Task 6) can surface "some files couldn't
 * be synced" instead of silently dropping them.
 */
data class ScanResult(val snapshot: Snapshot, val skipped: List<String>)

object LocalScanner {

    /**
     * Task 6's orchestration entry point: resolves the picked tree, wires the real
     * [ContentResolverFileAccess] and [AppDatabase]'s [HashDao], and walks it. Returns an empty
     * result (nothing to compare, nothing skipped) if the tree URI no longer resolves — e.g. the
     * user revoked permission or the folder was deleted since it was picked.
     */
    suspend fun scan(context: Context, folderUri: Uri): ScanResult {
        val root = DocumentFile.fromTreeUri(context, folderUri) ?: return ScanResult(emptyMap(), emptyList())
        val hashDao = AppDatabase.get(context).hashDao()
        val fileAccess = ContentResolverFileAccess(context.contentResolver)
        return walk(DocumentFileNode(root), hashDao, fileAccess)
    }

    /**
     * The pure-ish walk, separated from [scan] so tests drive it directly against a fake
     * [TreeNode]/[HashDao]/[FileAccess] with no Android framework involved.
     */
    suspend fun walk(root: TreeNode, hashDao: HashDao, fileAccess: FileAccess): ScanResult {
        val entries = mutableMapOf<String, Entry>()
        val skipped = mutableListOf<String>()

        suspend fun visit(node: TreeNode, relativePath: String) {
            for (child in node.children()) {
                val childPath = if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"
                if (unsyncable(childPath) != null) {
                    skipped.add(childPath)
                    continue
                }
                if (child.isDirectory) {
                    visit(child, childPath)
                    continue
                }
                // Finding #7: reading/hashing THIS one file (a permissions hiccup, a flaky
                // provider, ...) must never abort the whole scan — that would abort every future
                // sync pass too, since SyncOrchestrator treats a scan() throwing as fatal to the
                // pass. Reuse the same `skipped` mechanism unsyncable() paths already use instead
                // of propagating, and keep walking the rest of the tree.
                try {
                    val size = child.length
                    val mtimeMs = child.lastModified
                    val cached = hashDao.findHash(childPath, size, mtimeMs)
                    val sha256 = if (cached != null) {
                        cached.sha256
                    } else {
                        val hash = sha256Hex(fileAccess, child.uri)
                        hashDao.upsert(HashEntry(path = childPath, size = size, mtimeMs = mtimeMs, sha256 = hash))
                        hash
                    }
                    entries[childPath] = Entry(
                        path = childPath,
                        size = size,
                        sha256 = sha256,
                        mtime = Instant.ofEpochMilli(mtimeMs).toString(),
                    )
                } catch (e: Exception) {
                    skipped.add(childPath)
                }
            }
        }

        visit(root, "")
        return ScanResult(entries, skipped)
    }

    private fun sha256Hex(fileAccess: FileAccess, uri: Uri): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fileAccess.openStream(uri).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
