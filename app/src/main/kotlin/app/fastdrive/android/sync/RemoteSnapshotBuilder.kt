package app.fastdrive.android.sync

import app.fastdrive.android.api.ChangesPage
import app.fastdrive.android.api.Cursor
import app.fastdrive.android.api.DriveApi

/**
 * Builds the `remote` [Snapshot] `plan()` compares against, from `DriveApi.changes()` into the
 * NEW `sync_remote` table (Task 2) — independent of Phase 1's `CachedFile`-based file-browsing
 * cache, which reads the very same change feed for a different purpose (populating the file list
 * UI) on its own schedule, via its own cursor (`FileListViewModel`'s `changes_cursor` prefs key).
 *
 * The sync engine's cursor is [SyncSettings.getRemoteCursor]/[SyncSettings.setRemoteCursor] — a
 * distinct prefs key from that browsing cursor, so the two consumers never stomp on each other's
 * paging state even if they run concurrently.
 */
object RemoteSnapshotBuilder {

    /** Task 6's real orchestration entry point: wires the real [DriveApi]. */
    suspend fun buildRemoteSnapshot(api: DriveApi, remoteDao: RemoteDao, storedCursor: Cursor?): Pair<Snapshot, Cursor?> =
        buildRemoteSnapshot(remoteDao, storedCursor, api::changes)

    /**
     * The testable core, separated out the same way [LocalScanner.scan]/[LocalScanner.walk] and
     * `FileListViewModel.refresh()` split a real-API entry point from a fake-fetcher-driven one.
     *
     * Reuses `FileListViewModel.refresh()`'s exact pagination pattern (loop while `page.more`,
     * upsert `page.files`, remove `page.gone`), but against [RemoteDao]/`sync_remote` instead of
     * `CachedFileDao`/`CachedFile`.
     */
    suspend fun buildRemoteSnapshot(
        remoteDao: RemoteDao,
        storedCursor: Cursor?,
        changesFetcher: suspend (Cursor?) -> ChangesPage,
    ): Pair<Snapshot, Cursor?> {
        var cursor = storedCursor
        var more: Boolean
        do {
            val page = changesFetcher(cursor)
            if (page.files.isNotEmpty()) {
                remoteDao.upsertAll(
                    page.files.map { remote ->
                        RemoteEntry(
                            id = remote.id,
                            path = joinPath(remote.folder, remote.name),
                            size = remote.size,
                            sha256 = remote.sha256,
                            mtime = remote.mtime,
                            version = remote.version,
                            changedAt = remote.changedAt,
                            deleted = false,
                        )
                    },
                )
            }
            for (gone in page.gone) {
                remoteDao.deleteById(gone.id)
            }
            if (page.cursor != null) {
                cursor = page.cursor
            }
            more = page.more
        } while (more)

        return remoteDao.getAll().toRemoteSnapshot() to cursor
    }
}
