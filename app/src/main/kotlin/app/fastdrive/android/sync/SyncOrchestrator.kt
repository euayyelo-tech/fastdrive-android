package app.fastdrive.android.sync

import android.content.Context
import android.os.Build
import app.fastdrive.android.BuildConfig
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.auth.TokenStore
import app.fastdrive.android.auth.handleUnauthorized
import app.fastdrive.android.auth.isUnauthorized
import app.fastdrive.android.data.AppDatabase
import app.fastdrive.android.download.FileDownloader
import app.fastdrive.android.upload.ContentResolverFileAccess
import app.fastdrive.android.upload.FileAccess
import app.fastdrive.android.upload.FileUploader
import java.time.Instant
import okhttp3.OkHttpClient

/**
 * Ties together Tasks 1-5 into one sync pass: read `base` (Task 2), scan the local folder (Task
 * 4), pull the remote change feed into a snapshot (Task 5), hand all three to [plan] (Task 1), and
 * carry out every [Action] it returns — reusing Phase 1/2's download/upload protocols
 * ([FileDownloader]/[FileUploader]) rather than duplicating them, and Task 2's [BaseDao] to keep
 * `sync_base` in step with what actually happened on disk and on the server.
 *
 * [runOnePass] is the real entry point Tasks 7-8 call. [executePlan] is the testable core — the
 * same read-entry-point/testable-core split [LocalScanner] and `RemoteSnapshotBuilder` already use
 * elsewhere in this codebase — driven directly against fakes for [DriveApi]'s network (an
 * interceptor-equipped `OkHttpClient`, `UploadWorkerTest`'s own technique) and a fake
 * [LocalFileStore], with no real `DocumentFile`/`Context` involved.
 */
object SyncOrchestrator {

    suspend fun runOnePass(context: Context): SyncResult {
        val syncSettings = SyncSettings(context)
        val folderUri = syncSettings.getFolderUri()
            ?: return SyncResult(failed = listOf(SyncFailure(path = "", message = "No folder is set to sync.")))

        val db = AppDatabase.get(context)
        val tokenStore = TokenStore(context.applicationContext)
        val httpClient = OkHttpClient()
        val api = DriveApi(baseUrl = BuildConfig.API_BASE_URL, tokenProvider = { tokenStore.getToken() }, client = httpClient)
        val fileAccess = ContentResolverFileAccess(context.contentResolver)

        val localStore = try {
            DocumentTreeFileStore(context, folderUri)
        } catch (e: Exception) {
            return SyncResult(failed = listOf(SyncFailure(path = "", message = e.message ?: "Couldn't open the sync folder.")))
        }

        // Only a failure in building the snapshots themselves (can't read the folder, can't reach
        // the server) aborts the whole pass early — per-action failures below are recorded and
        // skipped instead, so one bad file never blocks the rest of the pass.
        val scan = try {
            LocalScanner.scan(context, folderUri)
        } catch (e: Exception) {
            return SyncResult(failed = listOf(SyncFailure(path = "", message = e.message ?: "Couldn't read the sync folder.")))
        }

        val (remoteSnapshot, newCursor) = try {
            RemoteSnapshotBuilder.buildRemoteSnapshot(api, db.remoteDao(), syncSettings.getRemoteCursor())
        } catch (e: Exception) {
            return SyncResult(failed = listOf(SyncFailure(path = "", message = e.message ?: "Couldn't reach the server.")))
        }
        newCursor?.let { syncSettings.setRemoteCursor(it) }

        val baseSnapshot = db.baseDao().getAll().toBaseSnapshot()
        // Matches the real device model Phase 1's SignInScreen already uses as a machine
        // identifier, per this task's own instruction.
        val actions = plan(baseSnapshot, scan.snapshot, remoteSnapshot, machine = Build.MODEL)

        // `.fastdrive-trash/...` paths are correctly hidden from sync by `unsyncable()`, but that's
        // internal bookkeeping, not something the user did wrong — don't surface it as "couldn't
        // be synced".
        val reportableSkipped = scan.skipped.filterNot { it.startsWith("$TRASH_DIR/") }

        return try {
            executePlan(
                actions = actions,
                local = scan.snapshot,
                remote = remoteSnapshot,
                api = api,
                httpClient = httpClient,
                fileAccess = fileAccess,
                localStore = localStore,
                baseDao = db.baseDao(),
                remoteDao = db.remoteDao(),
                skipped = reportableSkipped,
            )
        } catch (e: Exception) {
            // A 401 abandons the whole pass rather than being recorded per-action and continued
            // past — the token is gone, so every remaining action would fail the same way. Same
            // sign-out path `UploadWorker`/`FileListViewModel` already use for a 401 elsewhere.
            if (isUnauthorized(e)) handleUnauthorized(tokenStore)
            SyncResult(failed = listOf(SyncFailure(path = "", message = e.message ?: "Sync was interrupted.")))
        }
    }

    /**
     * Executes an already-computed [plan] against real or fake I/O. A single action's failure is
     * caught, recorded in [SyncResult.failed], and does not stop the remaining actions — `base` is
     * updated per-action, immediately after that action's own I/O succeeds, so a partial-pass
     * failure still leaves `sync_base` correctly reflecting whatever DID complete.
     */
    suspend fun executePlan(
        actions: List<Action>,
        local: Snapshot,
        remote: Snapshot,
        api: DriveApi,
        httpClient: OkHttpClient,
        fileAccess: FileAccess,
        localStore: LocalFileStore,
        baseDao: BaseDao,
        remoteDao: RemoteDao,
        skipped: List<String> = emptyList(),
    ): SyncResult {
        var uploaded = 0
        var downloaded = 0
        var deletedLocal = 0
        var deletedRemote = 0
        var moved = 0
        var conflicts = 0
        var settled = 0
        val failed = mutableListOf<SyncFailure>()

        // Matches the reference engine's carryOut() ordering exactly: moves first (so a later
        // action addressing the moved-to path sees it where it now is), then conflicts, then
        // plain transfers/settles, deletes last (so nothing that still needs the OLD path/bytes
        // for another action loses them to a delete run out of order).
        val sorted = actions.sortedBy { actionOrder(it) }

        for (action in sorted) {
            try {
                when (action) {
                    is Action.Upload -> {
                        val entry = local.getValue(action.path)
                        val uri = localStore.uriFor(action.path)
                            ?: error("local file for ${action.path} is missing")
                        val (folder, _) = splitPath(action.path)
                        val result = FileUploader.upload(
                            fileAccess, httpClient, api, uri, folder,
                            replace = action.replaceId, mtime = entry.mtime, sha256 = entry.sha256,
                        )
                        // Mirror what just went up into sync_remote directly (rather than waiting
                        // for the next pull) and derive base's rev from that SAME mirrored row, so
                        // base and remote agree on this file's identity right now — `same()` will
                        // see them match on the very next pass instead of re-uploading/downloading
                        // forever (the bug this fixes).
                        val row = mirror(
                            remoteDao, result.id, path = action.path, size = entry.size,
                            sha256 = entry.sha256, mtime = entry.mtime, deleted = false, bumpVersion = true,
                        )
                        baseDao.upsert(
                            BaseEntry(path = action.path, id = result.id, size = entry.size, sha256 = entry.sha256, mtime = entry.mtime, rev = revOf(result.id, row.version)),
                        )
                        uploaded++
                    }

                    is Action.Download -> {
                        val entry = remote.getValue(action.path)
                        FileDownloader.download(api, httpClient, action.id) { localStore.openForWrite(action.path) }
                        baseDao.upsert(
                            BaseEntry(path = action.path, id = entry.id, size = entry.size, sha256 = entry.sha256, mtime = entry.mtime, rev = entry.rev),
                        )
                        downloaded++
                    }

                    is Action.DeleteLocal -> {
                        // localStore.trash() itself now tolerates the file already being gone
                        // (returns true rather than failing), so this never retries forever over a
                        // file that isn't there to delete in the first place.
                        if (!localStore.trash(action.path)) error("couldn't move ${action.path} to trash")
                        baseDao.deleteByPath(action.path)
                        deletedLocal++
                    }

                    is Action.DeleteRemote -> {
                        api.deleteFile(action.id)
                        baseDao.deleteByPath(action.path)
                        deletedRemote++
                    }

                    is Action.MoveRemote -> {
                        // The local file already moved (that's *why* this action exists — plan()
                        // paired a vanished local path with a newly-appeared one by id/hash); the
                        // remote side has to be told to follow.
                        val entry = local.getValue(action.to)
                        val (folder, name) = splitPath(action.to)
                        api.updateFile(action.id, folder = folder, name = name)
                        // Mirror the new path into sync_remote so the next plan sees this id living
                        // at `to`, even if the next pull is delayed or fails.
                        val row = mirror(remoteDao, action.id, path = action.to)
                        baseDao.deleteByPath(action.from)
                        baseDao.upsert(
                            BaseEntry(path = action.to, id = action.id, size = entry.size, sha256 = entry.sha256, mtime = entry.mtime, rev = revOf(action.id, row.version)),
                        )
                        moved++
                    }

                    is Action.MoveLocal -> {
                        // The remote file already moved; the local copy has to follow.
                        if (!localStore.move(action.from, action.to)) error("couldn't move ${action.from} to ${action.to} locally")
                        val entry = remote.getValue(action.to)
                        baseDao.deleteByPath(action.from)
                        baseDao.upsert(
                            BaseEntry(path = action.to, id = entry.id, size = entry.size, sha256 = entry.sha256, mtime = entry.mtime, rev = entry.rev),
                        )
                        moved++
                    }

                    is Action.Conflict -> {
                        // Keep both, for real: this machine's copy steps aside on disk under
                        // `renamed` and goes up too; the drive's copy comes down under the
                        // ORIGINAL `path`. The local disk ends up holding BOTH files — that is what
                        // "keep both" means. (The previous version of this code never moved the
                        // local file and never downloaded the remote copy, so the aside copy could
                        // be uploaded and then the ONLY on-disk copy immediately overwritten by
                        // nothing — a straight-up data loss bug.)
                        val entry = local.getValue(action.path)
                        if (!localStore.move(action.path, action.renamed)) {
                            error("couldn't move ${action.path} aside to ${action.renamed}")
                        }
                        // The aside copy isn't safely synced until ITS upload (below) succeeds —
                        // don't claim base-settled state for it before that's true. If a base row
                        // already existed at `renamed` from something unrelated, drop it too.
                        baseDao.deleteByPath(action.renamed)

                        val remoteEntry = remote.getValue(action.path)
                        FileDownloader.download(api, httpClient, action.id) { localStore.openForWrite(action.path) }
                        baseDao.upsert(
                            BaseEntry(path = action.path, id = remoteEntry.id, size = remoteEntry.size, sha256 = remoteEntry.sha256, mtime = remoteEntry.mtime, rev = remoteEntry.rev),
                        )

                        val asideUri = localStore.uriFor(action.renamed)
                            ?: error("aside copy for ${action.path} is missing after moving it to ${action.renamed}")
                        val (renamedFolder, renamedName) = splitPath(action.renamed)
                        val result = FileUploader.upload(
                            fileAccess, httpClient, api, asideUri, renamedFolder, replace = null, nameOverride = renamedName,
                            mtime = entry.mtime, sha256 = entry.sha256,
                        )
                        val row = mirror(
                            remoteDao, result.id, path = action.renamed, size = entry.size,
                            sha256 = entry.sha256, mtime = entry.mtime, deleted = false, bumpVersion = true,
                        )
                        baseDao.upsert(
                            BaseEntry(path = action.renamed, id = result.id, size = entry.size, sha256 = entry.sha256, mtime = entry.mtime, rev = revOf(result.id, row.version)),
                        )
                        conflicts++
                    }

                    is Action.Settle -> {
                        // Both sides already agree — no file operation, just record it in `base`.
                        val entry = remote.getValue(action.path)
                        baseDao.upsert(
                            BaseEntry(path = action.path, id = entry.id, size = entry.size, sha256 = entry.sha256, mtime = entry.mtime, rev = entry.rev),
                        )
                        settled++
                    }
                }
            } catch (e: Exception) {
                // A 401 means the token is gone: abort the WHOLE pass immediately rather than
                // recording it as one failed action and carrying on to the next (every remaining
                // action would fail the exact same way). The caller (runOnePass) catches this to
                // drive the actual sign-out.
                if (isUnauthorized(e)) throw e
                failed.add(SyncFailure(path = pathOf(action), message = e.message ?: "Sync action failed."))
            }
        }

        return SyncResult(
            uploaded = uploaded,
            downloaded = downloaded,
            deletedLocal = deletedLocal,
            deletedRemote = deletedRemote,
            moved = moved,
            conflicts = conflicts,
            settled = settled,
            skipped = skipped,
            failed = failed,
        )
    }

    /** Matches the reference engine's `order()` exactly: moves first, then conflicts, then plain
     *  transfers/settles, deletes last. */
    private fun actionOrder(action: Action): Int = when (action) {
        is Action.MoveLocal, is Action.MoveRemote -> 0
        is Action.Conflict -> 1
        is Action.Download, is Action.Upload, is Action.Settle -> 2
        is Action.DeleteLocal, is Action.DeleteRemote -> 3
    }

    /**
     * Records what this pass just did to the drive directly into `sync_remote`, the same role
     * desktop's `mirror()` plays — so the very next plan (even before a fresh pull) sees a
     * `sync_remote` row consistent with the `sync_base` row [executePlan] is about to write next to
     * it, instead of `sync_base` racing ahead of a `sync_remote` that still holds stale
     * (pre-upload) data.
     *
     * Fields left null default to whatever [remoteDao] already has for [id] (a full replace, same
     * as desktop's `cur?.field` fallbacks), except [version]: [bumpVersion] increments whatever
     * version this id already had (or starts at 1 for a brand-new id) — used for an actual content
     * change (upload); a metadata-only change (a move) leaves the existing version untouched.
     */
    private suspend fun mirror(
        remoteDao: RemoteDao,
        id: String,
        path: String? = null,
        size: Long? = null,
        sha256: String? = null,
        mtime: String? = null,
        deleted: Boolean? = null,
        bumpVersion: Boolean = false,
    ): RemoteEntry {
        val cur = remoteDao.findById(id)
        val version = if (bumpVersion) (cur?.version ?: 0) + 1 else cur?.version ?: 1
        val row = RemoteEntry(
            id = id,
            path = path ?: cur?.path ?: "",
            size = size ?: cur?.size ?: 0,
            sha256 = sha256 ?: cur?.sha256,
            mtime = mtime ?: cur?.mtime,
            version = version,
            changedAt = Instant.now().toString(),
            deleted = deleted ?: cur?.deleted ?: false,
        )
        remoteDao.upsert(row)
        return row
    }

    private fun pathOf(action: Action): String = when (action) {
        is Action.Upload -> action.path
        is Action.Download -> action.path
        is Action.DeleteLocal -> action.path
        is Action.DeleteRemote -> action.path
        is Action.MoveRemote -> action.to
        is Action.MoveLocal -> action.to
        is Action.Conflict -> action.path
        is Action.Settle -> action.path
    }
}

/** One path [plan] tried to act on whose action failed; [message] is surfaced (not paraphrased)
 *  the same way `UploadWorker`'s failures already are. */
data class SyncFailure(val path: String, val message: String)

/**
 * What one [SyncOrchestrator.runOnePass] actually did: a count per [Action] type executed, every
 * path [LocalScanner] had to skip ([skipped]), and every action that was attempted but failed
 * ([failed]) — Tasks 7-8 both call [SyncOrchestrator.runOnePass] and surface this to the user/logs.
 */
data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedLocal: Int = 0,
    val deletedRemote: Int = 0,
    val moved: Int = 0,
    val conflicts: Int = 0,
    val settled: Int = 0,
    val skipped: List<String> = emptyList(),
    val failed: List<SyncFailure> = emptyList(),
)
