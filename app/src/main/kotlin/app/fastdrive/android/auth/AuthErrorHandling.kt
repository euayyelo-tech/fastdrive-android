package app.fastdrive.android.auth

import app.fastdrive.android.api.ApiException
import app.fastdrive.android.data.AppDatabase
import app.fastdrive.android.sync.SyncSettings

/**
 * True when [e] is the server telling us the current auth token is no longer valid.
 *
 * Shared by [app.fastdrive.android.ui.FileListViewModel.refresh], [app.fastdrive.android.upload.UploadWorker.doWork],
 * and [app.fastdrive.android.sync.SyncOrchestrator.runOnePass] so a 401 is recognized the same way
 * in every place instead of copies of the same `is ApiException && status == 401` check drifting
 * apart.
 */
fun isUnauthorized(e: Throwable): Boolean = e is ApiException && e.status == 401

/**
 * The shared sign-out path: clears the stored token so the app falls back to sign-in the next
 * time anything checks it, AND wipes every piece of state that was scoped to the account that
 * just got signed out of — the sync engine's own working tables (`sync_base`/`sync_remote`/
 * `sync_hashes`) and both of this app's independent change-feed cursors (Phase 1's
 * `changes_cursor`, Phase 3's own `sync_remote_cursor` — see [SyncSettings]'s own doc comment on
 * why there are two), plus the synced folder pick itself (see [SyncSettings.clearAccountState]'s
 * own doc comment for why clearing it, not keeping it, is the safer default).
 *
 * Without this, a stale `sync_base` from the PREVIOUS account survives a sign-out. If a
 * different account then signs in and picks a folder, [app.fastdrive.android.sync.plan] would see
 * `base != null` (this machine's old idea of "settled") / `local != null` (real files still on
 * disk) / `remote == null` (the new account's snapshot has never heard of these files) for every
 * previously-synced path, and emit an [app.fastdrive.android.sync.Action.DeleteLocal] for the
 * entire folder's contents — deleting a brand-new user's files (or the previous account's,
 * relocated locally) the very first time sync runs for them.
 *
 * [TokenStore] always reads/writes the same underlying "fastdrive_secure_prefs" file regardless of
 * which component constructed the instance, so a clear from a background [android.content.Context]
 * (e.g. inside `UploadWorker`) is visible to the foreground `TokenStore` instance the UI holds too
 * — same reasoning applies to [AppDatabase] (a process-wide singleton) and [SyncSettings]'s prefs
 * file, so calling this from any of the three call sites reaches all of them.
 */
suspend fun handleUnauthorized(tokenStore: TokenAccess, database: AppDatabase, syncSettings: SyncSettings) {
    tokenStore.clear()
    database.baseDao().deleteAll()
    database.remoteDao().deleteAll()
    database.hashDao().deleteAll()
    syncSettings.clearAccountState()
}
