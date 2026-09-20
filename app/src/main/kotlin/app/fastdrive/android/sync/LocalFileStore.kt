package app.fastdrive.android.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/** Where [DocumentTreeFileStore.trash] parks a machine-deleted file, and the prefix
 *  [SyncOrchestrator] filters out of the scan's reported `skipped` list — it's an internal
 *  bookkeeping folder [unsyncable] correctly hides from sync, not something the user needs to be
 *  told "couldn't be synced". */
const val TRASH_DIR = ".fastdrive-trash"

/** Where [DocumentTreeFileStore.openForWrite] stages a download's bytes before [LocalFileStore.commitWrite]
 *  moves them into place (Finding #3) — a dot-prefixed folder alongside [TRASH_DIR], hidden from
 *  [LocalScanner] by the exact same [unsyncable] mechanism, for the exact same reason: this is
 *  internal bookkeeping, not a real file the user put there. */
const val TMP_DIR = ".fastdrive-tmp"

/** The real, current (size, mtime) of a file already on local disk — as opposed to what a remote
 *  entry merely CLAIMS its mtime is. See [LocalFileStore.statLocal]'s doc comment (Finding #2). */
data class LocalStat(val size: Long, val mtime: String)

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

    /**
     * Opens a stream to write [path]'s bytes, creating parent folders as needed. Caller closes it.
     *
     * Finding #3: this does NOT write directly to the real target — the real implementation
     * stages bytes in a temp file under [TMP_DIR] instead, so an interruption partway through
     * (a sync-mode switch mid-download, process death, a network drop) leaves the real path
     * untouched rather than truncated (which the next scan would otherwise misdetect as a local
     * edit and raise a spurious conflict over). The caller MUST follow a successful write with
     * [commitWrite] to actually move the bytes into place, or [abortWrite] to discard them on
     * failure — this method alone does not touch the real file.
     */
    fun openForWrite(path: String): OutputStream

    /** Moves whatever [openForWrite] most recently staged for [path] into the real target path,
     *  atomically (a same-provider move/rename, or a copy+delete fallback — see
     *  [DocumentTreeFileStore.move]), overwriting anything already there. Throws if there's
     *  nothing staged for [path] or the move itself fails. */
    fun commitWrite(path: String)

    /** Discards whatever [openForWrite] staged for [path] without touching the real target —
     *  called on a failed/aborted write instead of [commitWrite]. A no-op if nothing was staged. */
    fun abortWrite(path: String)

    /** The real, current (size, mtime) of the file actually on disk at [path], or null if nothing
     *  is there. Finding #2: Android's Storage Access Framework has no equivalent of desktop's
     *  `utimes()` call — there is no way to set a downloaded file's on-disk mtime to match the
     *  remote entry's claimed mtime, so it stays whatever the OS assigned it ("now"), forever
     *  disagreeing with a `sync_base` row written from the remote's claim. Callers that just wrote
     *  a file (`Download`, `Conflict`'s remote-copy download, `MoveLocal`) must re-stat via this
     *  and record `sync_base` from THESE real values instead, mirroring the reference engine's own
     *  re-stat-then-set-base settle pattern. */
    fun statLocal(path: String): LocalStat?

    /**
     * Moves the file at [path] out of the way of future scans/plans rather than deleting it for
     * real — see [DocumentTreeFileStore]'s doc comment for why trash lives where it does. Returns
     * whether it moved.
     */
    fun trash(path: String): Boolean

    /** Moves/renames the file at [from] to [to], creating intermediate folders under [to].
     *  Returns whether it moved. */
    fun move(from: String, to: String): Boolean

    /** Finding #8: deletes every entry under [TRASH_DIR] whose own timestamp prefix (see
     *  [DocumentTreeFileStore.trash]) is older than [retentionMs]. Returns how many were pruned.
     *  Not a full "empty trash" UI — just automatic, unattended pruning so the folder doesn't grow
     *  forever unnoticed. */
    fun pruneTrash(retentionMs: Long): Int
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

    /** Real path -> temp path currently staged for it by [openForWrite], pending [commitWrite]/
     *  [abortWrite]. One [DocumentTreeFileStore] lives for exactly one sync pass (built fresh in
     *  [SyncOrchestrator.runOnePass]), so this never needs to survive past that. */
    private val pendingWrites = mutableMapOf<String, String>()

    override fun uriFor(path: String): Uri? = resolve(path)?.uri

    override fun openForWrite(path: String): OutputStream {
        val tempPath = "$TMP_DIR/${UUID.randomUUID()}"
        val (dirPath, name) = splitPath(tempPath)
        val parent = resolveOrCreateDirs(dirPath) ?: throw IOException("can't create tmp folder for $path")
        val target = parent.createFile(guessMimeType(splitPath(path).second), name)
            ?: throw IOException("can't create tmp file for $path")
        pendingWrites[path] = tempPath
        return resolver.openOutputStream(target.uri, "wt") ?: throw IOException("can't open tmp file for $path")
    }

    override fun commitWrite(path: String) {
        val tempPath = pendingWrites.remove(path) ?: throw IOException("no pending write for $path")
        // Clear out whatever's already at `path` first: move()'s same-parent branch uses
        // DocumentFile.renameTo(), which fails outright if a file of that name already exists,
        // and its cross-provider fallback would otherwise leave BOTH the old and new bytes
        // sitting under slightly different names rather than replacing the old one.
        resolve(path)?.delete()
        if (!move(tempPath, path)) {
            throw IOException("couldn't move the downloaded bytes for $path into place")
        }
    }

    override fun abortWrite(path: String) {
        val tempPath = pendingWrites.remove(path) ?: return
        resolve(tempPath)?.delete()
    }

    override fun statLocal(path: String): LocalStat? {
        val doc = resolve(path) ?: return null
        return LocalStat(size = doc.length(), mtime = Instant.ofEpochMilli(doc.lastModified()).toString())
    }

    override fun pruneTrash(retentionMs: Long): Int {
        val trash = resolveDir(TRASH_DIR) ?: return 0
        val cutoff = System.currentTimeMillis() - retentionMs
        var pruned = 0
        for (child in trash.listFiles()) {
            // DocumentTreeFileStore.trash() names every entry "<epochMillis>_<original path, '/'
            // replaced with '_'>" — the timestamp this file was binned at is always the leading
            // segment up to the first underscore.
            val name = child.name ?: continue
            val trashedAtMs = name.substringBefore('_').toLongOrNull() ?: continue
            if (trashedAtMs < cutoff && child.delete()) pruned++
        }
        return pruned
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
