package app.fastdrive.android.sync

import android.content.Context
import android.net.Uri

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
 * tree [Uri] and the chosen [SyncMode]. Neither value is sensitive, so this deliberately doesn't
 * reuse [app.fastdrive.android.auth.TokenStore]'s `EncryptedSharedPreferences` — that's reserved
 * for the auth token.
 */
class SyncSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fastdrive_sync_prefs", Context.MODE_PRIVATE)

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
        // TODO(Tasks 7-8): switching modes here should also start/stop the corresponding trigger
        // mechanism (WorkManager periodic work for BATTERY_FRIENDLY vs. a foreground service for
        // INSTANT). This task only persists the choice; the actual mechanism swap is out of scope.
    }

    private companion object {
        const val KEY_FOLDER_URI = "sync_folder_uri"
        const val KEY_SYNC_MODE = "sync_mode"
    }
}
