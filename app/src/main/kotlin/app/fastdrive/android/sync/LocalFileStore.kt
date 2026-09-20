package app.fastdrive.android.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.OutputStream

/** Where [DocumentTreeFileStore.trash] parks a machine-deleted file, and the prefix
 *  [SyncOrchestrator] filters out of the scan's reported `skipped` list — it's an internal
 *  bookkeeping folder [unsyncable] correctly hides from sync, not something the user needs to be
 *  told "couldn't be synced". */
const val TRASH_DIR = ".fastdrive-trash"

/**
 * The write operations [SyncOrchestrator] needs against the user's picked sync folder — the
 * counterpart to [LocalScanner]'s read-only [TreeNode] seam over the same tree. [SyncOrchestrator]
 * drives this to execute [Action.Download]/[Action.DeleteLocal]/[Action.MoveLocal]; tests drive it
 * against a hand-built fake instead of [DocumentTreeFileStore] (see `SyncOrchestratorTest`), the
 * same split [LocalScanner.scan]/[LocalScanner.walk] and `RemoteSnapshotBuilder`'s two entry points
 * already use elsewhere in this codebase.
 */
interface LocalFileStore {
    /** The existing file's content [Uri] at [path], or null if nothing is there. */
    fun uriFor(path: String): Uri?

    /** Opens a stream to (over)write [path], creating parent folders and the file itself as
     *  needed. Caller closes it. */
    fun openForWrite(path: String): OutputStream

    /**
     * Moves the file at [path] out of the way of future scans/plans rather than deleting it for
     * real — see [DocumentTreeFileStore]'s doc comment for why trash lives where it does. Returns
     * whether it moved.
     */
    fun trash(path: String): Boolean

    /** Moves/renames the file at [from] to [to], creating intermediate folders under [to].
     *  Returns whether it moved. */
    fun move(from: String, to: String): Boolean
}

/**
 * A tree picked via `ACTION_OPEN_DOCUMENT_TREE` grants access only inside that tree — there is no
 * arbitrary filesystem path outside it a deleted file could move to, so a real OS trash/recycle
 * bin isn't reachable here. [trash] instead moves a deleted file to a `.fastdrive-trash/` folder
 * AT THE ROOT of the synced tree: guaranteed writable (same grant as everything else), and
 * [unsyncable] already rejects any path with a dot-prefixed segment, so [LocalScanner] never
 * re-discovers a trashed file as a "new" local file on a later pass. Each trashed file is
 * timestamped so deleting the same path twice doesn't collide.
 *
 * [move] prefers `DocumentsContract.moveDocument` (a same-provider move with no byte copy) and
 * falls back to a copy-then-delete for a provider that doesn't support it — not every SAF
 * provider opts into the move flag, and this needs to work on all of them.
 */
class DocumentTreeFileStore(private val context: Context, rootUri: Uri) : LocalFileStore {
    private val resolver = context.contentResolver
    private val root: DocumentFile = requireNotNull(DocumentFile.fromTreeUri(context, rootUri)) {
        "sync folder $rootUri no longer resolves"
    }

    override fun uriFor(path: String): Uri? = resolve(path)?.uri

    override fun openForWrite(path: String): OutputStream {
        val (dirPath, name) = splitPath(path)
        val parent = resolveOrCreateDirs(dirPath) ?: throw IOException("can't create folder for $path")
        val target = parent.findFile(name) ?: parent.createFile(guessMimeType(name), name)
            ?: throw IOException("can't create file $path")
        return resolver.openOutputStream(target.uri, "wt") ?: throw IOException("can't open $path for write")
    }

    override fun trash(path: String): Boolean {
        // Already gone (e.g. the drive told us to delete a file this machine had itself already
        // lost, or a previous pass's trash of the same path raced with this one): tolerate it as
        // success rather than a failure that would otherwise be retried forever, the same way the
        // reference implementation treats an ENOENT on its rename as "nothing to do".
        if (resolve(path) == null) return true
        val flat = path.replace('/', '_')
        return move(path, "$TRASH_DIR/${System.currentTimeMillis()}_$flat")
    }

    override fun move(from: String, to: String): Boolean {
        val source = resolve(from) ?: return false
        val (fromDirPath, _) = splitPath(from)
        val (toDirPath, toName) = splitPath(to)
        val fromParent = resolveDir(fromDirPath) ?: return false
        val toParent = resolveOrCreateDirs(toDirPath) ?: return false

        if (fromParent.uri == toParent.uri) {
            return runCatching { source.renameTo(toName) }.getOrDefault(false)
        }

        val movedUri = runCatching {
            DocumentsContract.moveDocument(resolver, source.uri, fromParent.uri, toParent.uri)
        }.getOrNull()
        if (movedUri != null) {
            if (toName != source.name) {
                // DocumentFile.fromSingleUri(...).renameTo(...) is a no-op/failure here — androidx's
                // SingleDocumentFile doesn't support renaming a bare content:// document the way a
                // TreeDocumentFile does. DocumentsContract.renameDocument() is the correct API for
                // renaming a content:// document URI directly, moved-to-URI or not.
                val renamed = runCatching { DocumentsContract.renameDocument(resolver, movedUri, toName) }.getOrNull()
                if (renamed == null) return false
            }
            return true
        }

        // Fallback for a provider that doesn't support DocumentsContract.moveDocument: copy the
        // bytes to the destination, then delete the source.
        return runCatching {
            val newFile = toParent.createFile(guessMimeType(toName), toName) ?: return@runCatching false
            val copied = resolver.openInputStream(source.uri)?.use { input ->
                resolver.openOutputStream(newFile.uri)?.use { output -> input.copyTo(output); true } ?: false
            } ?: false
            if (!copied) return@runCatching false
            source.delete()
            true
        }.getOrDefault(false)
    }

    private fun resolve(path: String): DocumentFile? {
        if (path.isEmpty()) return root
        var current = root
        for (segment in path.split('/')) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun resolveDir(path: String): DocumentFile? = if (path.isEmpty()) root else resolve(path)

    private fun resolveOrCreateDirs(path: String): DocumentFile? {
        if (path.isEmpty()) return root
        var current = root
        for (segment in path.split('/')) {
            current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
        }
        return current
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "")
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
    }
}
