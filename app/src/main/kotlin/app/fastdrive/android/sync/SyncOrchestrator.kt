package app.fastdrive.android.sync

import android.content.Context
import android.os.Build
import app.fastdrive.android.BuildConfig
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.auth.TokenStore
import app.fastdrive.android.data.AppDatabase
import app.fastdrive.android.download.FileDownloader
import app.fastdrive.android.upload.ContentResolverFileAccess
import app.fastdrive.android.upload.FileAccess
import app.fastdrive.android.upload.FileUploader
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

        return executePlan(
            actions = actions,
            local = scan.snapshot,
            remote = remoteSnapshot,
            api = api,
            httpClient = httpClient,
            fileAccess = fileAccess,
            localStore = localStore,
            baseDao = db.baseDao(),
            skipped = scan.skipped,
        )
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

        for (action in actions) {
            try {
                when (action) {
                    is Action.Upload -> {
                        val entry = local.getValue(action.path)
                        val uri = localStore.uriFor(action.path)
                            ?: error("local file for ${action.path} is missing")
                        val (folder, _) = splitPath(action.path)
                        val result = FileUploader.upload(fileAccess, httpClient, api, uri, folder, replace = action.replaceId)
                        baseDao.upsert(
                            BaseEntry(path = action.path, id = result.id, size = entry.size, sha256 = entry.sha256, mtime = entry.mtime, rev = null),
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
                        baseDao.deleteByPath(action.from)
                        baseDao.upsert(
                            BaseEntry(path = action.to, id = action.id, size = entry.size, sha256 = entry.sha256, mtime = entry.mtime, rev = null),
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
                        // Both sides changed differently: nothing is dropped. The remote copy at
                        // `action.path` (`action.id`) is left exactly as it is — this action never
                        // touches it. The LOCAL copy is uploaded fresh, under `action.renamed`, as
                        // a brand-new remote file (never a replace).
                        val entry = local.getValue(action.path)
                        val uri = localStore.uriFor(action.path)
                            ?: error("local file for ${action.path} is missing")
                        val (renamedFolder, renamedName) = splitPath(action.renamed)
                        val result = FileUploader.upload(
                            fileAccess, httpClient, api, uri, renamedFolder, replace = null, nameOverride = renamedName,
                        )
                        // `base[path]` is settled against the LOCAL bytes (not the remote's) so this
                        // pass doesn't re-flag the exact same conflict forever. A later, ordinary
                        // pass will then see local==base but remote (still `action.id`'s untouched
                        // entry) differ, and issue a plain Download to reconcile `path` with
                        // whichever copy the drive considers the original.
                        baseDao.upsert(
                            BaseEntry(path = action.path, id = action.id, size = entry.size, sha256 = entry.sha256, mtime = entry.mtime, rev = null),
                        )
                        baseDao.upsert(
                            BaseEntry(path = action.renamed, id = result.id, size = entry.size, sha256 = entry.sha256, mtime = entry.mtime, rev = null),
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
