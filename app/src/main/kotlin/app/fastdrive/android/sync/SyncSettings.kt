package app.fastdrive.android.sync

import android.content.Context
import android.net.Uri
import app.fastdrive.android.api.Cursor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Which mechanism (Tasks 7-8) should drive background sync. Neither value wires up an actual
 * trigger here — this enum and [SyncSettings] only persist the user's choice for those later
 * tasks to read.
 */
enum class SyncMode {
    BATTERY_FRIENDLY,
    INSTANT,
}

/**
 * Plain (unencrypted) `SharedPreferences`-backed storage for sync settings: the synced folder's
 * tree [Uri], the chosen [SyncMode], and the sync engine's own change-feed [Cursor].
 *
 * The cursor here is deliberately separate from `FileListViewModel`'s own `changes_cursor` key
 * (used for the file-browsing screen's cache refresh): both are independent consumers of
 * `DriveApi.changes()` that may run on different schedules, so each keeps its own cursor rather
 * than sharing one. They happen to live in the same underlying prefs file
 * (`fastdrive_sync_prefs`, non-secret either way) but under distinct keys — [KEY_REMOTE_CURSOR]
 * here vs. `FileListViewModel`'s own `changes_cursor`.
 */
class SyncSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fastdrive_sync_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getFolderUri(): Uri? = prefs.getString(KEY_FOLDER_URI, null)?.let { Uri.parse(it) }

    fun setFolderUri(uri: Uri) {
        prefs.edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
    }

    fun getSyncMode(): SyncMode {
        val stored = prefs.getString(KEY_SYNC_MODE, null) ?: return SyncMode.BATTERY_FRIENDLY
        return runCatching { SyncMode.valueOf(stored) }.getOrDefault(SyncMode.BATTERY_FRIENDLY)
    }

    fun setSyncMode(mode: SyncMode) {
        prefs.edit().putString(KEY_SYNC_MODE, mode.name).apply()
        // Task 7 wires the actual WorkManager periodic-work start/stop at the call sites
        // (SyncSettingsScreen, MainActivity startup) via PeriodicSyncWorker.applySettings —
        // this class only persists the choice.
        // TODO(Task 8): start/stop the INSTANT-mode foreground service the same way.
    }

    /** The sync engine's own change-feed cursor (Task 6's orchestration reads/writes this around
     *  [RemoteSnapshotBuilder.buildRemoteSnapshot]) — never the file-browsing screen's cursor. */
    fun getRemoteCursor(): Cursor? = prefs.getString(KEY_REMOTE_CURSOR, null)?.let {
        runCatching { json.decodeFromString<Cursor>(it) }.getOrNull()
    }

    fun setRemoteCursor(cursor: Cursor) {
        prefs.edit().putString(KEY_REMOTE_CURSOR, json.encodeToString(cursor)).apply()
    }

    private companion object {
        const val KEY_FOLDER_URI = "sync_folder_uri"
        const val KEY_SYNC_MODE = "sync_mode"
        const val KEY_REMOTE_CURSOR = "sync_remote_cursor"
    }
}
