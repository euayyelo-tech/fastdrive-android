package app.fastdrive.android.sync

import android.content.Context
import android.net.Uri
import androidx.work.WorkManager
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
 * Why sync is currently paused (Phase 4 Task 3). `null` from [SyncSettings.getPauseCondition]
 * means "not paused" — there is no separate "paused" boolean, this sealed class's presence/absence
 * IS the pause state. [SyncSettings.isPaused] is just `getPauseCondition() != null`.
 *
 * [Timer] and [SpecificWifi] carry the data needed to auto-resume (Task 3's [PauseResumeWorker] for
 * the former, Tasks 4-5's Wi-Fi watcher for the latter); [AnyWifi] and [Manual] carry none.
 */
sealed class PauseCondition {
    data class Timer(val resumeAtMillis: Long) : PauseCondition()
    object AnyWifi : PauseCondition()
    data class SpecificWifi(val ssid: String) : PauseCondition()
    object Manual : PauseCondition()
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
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("fastdrive_sync_prefs", Context.MODE_PRIVATE)
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
        // Tasks 7/8 wire the actual WorkManager periodic-work / InstantSyncService start/stop at
        // the call sites (SyncSettingsScreen, MainActivity startup) via
        // PeriodicSyncWorker.applySettings — this class only persists the choice.
    }

    /** The sync engine's own change-feed cursor (Task 6's orchestration reads/writes this around
     *  [RemoteSnapshotBuilder.buildRemoteSnapshot]) — never the file-browsing screen's cursor. */
    fun getRemoteCursor(): Cursor? = prefs.getString(KEY_REMOTE_CURSOR, null)?.let {
        runCatching { json.decodeFromString<Cursor>(it) }.getOrNull()
    }

    fun setRemoteCursor(cursor: Cursor) {
        prefs.edit().putString(KEY_REMOTE_CURSOR, json.encodeToString(cursor)).apply()
    }

    /**
     * `null` = not paused. Backed by a type-tag string plus the one field ([KEY_PAUSE_RESUME_AT]
     * or [KEY_PAUSE_SSID]) that condition needs, matching this class's existing plain-value
     * storage convention (no JSON library for a value this simple — [getRemoteCursor] above is the
     * one exception, because [Cursor] is already `@Serializable` for the API layer it comes from).
     */
    fun getPauseCondition(): PauseCondition? {
        val condition = getStoredPauseCondition()
        // Finding #5 (Phase 4 fix round): a Timer whose resumeAtMillis has already passed must
        // never be trusted as still "paused" — e.g. if the PauseResumeWorker job that was supposed
        // to clear it was somehow lost (WorkManager DB wiped, an OEM battery killer, etc). Placed
        // here, the one place every "is sync paused right now?" caller (the UI, isPaused(), and
        // through it both applySettings() gates) reads the pause condition through, so the guard
        // applies everywhere without duplicating it at each call site.
        //
        // Round 2 (Bug 1): this guard used to ALSO clear the stored value as a side effect of
        // being read. That silently broke timer auto-resume: PauseResumeWorker.doWork() fires at
        // (or after) resumeAtMillis and read the condition through this same function, so the
        // self-heal had always already nulled it out by the time the worker checked `is Timer`,
        // the worker's re-apply branch never ran, and the periodic work stayed cancelled / the
        // instant service stayed stopped until the user reopened the app. The self-heal is now a
        // pure read — it changes what callers SEE, never what is stored. Clearing stored state is
        // left to the two things that actually own a resume (PauseResumeWorker.doWork and
        // resumeNow), so nothing can race a read against a write here.
        if (condition is PauseCondition.Timer && condition.resumeAtMillis <= System.currentTimeMillis()) {
            return null
        }
        return condition
    }

    /**
     * The pause condition exactly as stored, with no expiry interpretation — an elapsed
     * [PauseCondition.Timer] still comes back as that `Timer`.
     *
     * Only [PauseResumeWorker] should use this: it is the timer-resume worker itself, so "a Timer
     * whose deadline has passed" is precisely the state it exists to act on, and it must be able
     * to see it rather than having [getPauseCondition]'s expiry guard hide it first. Every other
     * caller wants [getPauseCondition] / [isPaused] — "is sync paused right now".
     */
    fun getStoredPauseCondition(): PauseCondition? {
        return when (prefs.getString(KEY_PAUSE_TYPE, null)) {
            PAUSE_TYPE_TIMER -> {
                val resumeAtMillis = prefs.getLong(KEY_PAUSE_RESUME_AT, -1L)
                if (resumeAtMillis < 0) null else PauseCondition.Timer(resumeAtMillis)
            }
            PAUSE_TYPE_ANY_WIFI -> PauseCondition.AnyWifi
            PAUSE_TYPE_SPECIFIC_WIFI -> {
                val ssid = prefs.getString(KEY_PAUSE_SSID, null)
                if (ssid == null) null else PauseCondition.SpecificWifi(ssid)
            }
            PAUSE_TYPE_MANUAL -> PauseCondition.Manual
            else -> null
        }
    }

    fun setPauseCondition(condition: PauseCondition?) {
        val editor = prefs.edit()
        when (condition) {
            null -> editor.remove(KEY_PAUSE_TYPE).remove(KEY_PAUSE_RESUME_AT).remove(KEY_PAUSE_SSID)
            is PauseCondition.Timer -> editor.putString(KEY_PAUSE_TYPE, PAUSE_TYPE_TIMER)
                .putLong(KEY_PAUSE_RESUME_AT, condition.resumeAtMillis)
                .remove(KEY_PAUSE_SSID)
            is PauseCondition.AnyWifi -> editor.putString(KEY_PAUSE_TYPE, PAUSE_TYPE_ANY_WIFI)
                .remove(KEY_PAUSE_RESUME_AT)
                .remove(KEY_PAUSE_SSID)
            is PauseCondition.SpecificWifi -> editor.putString(KEY_PAUSE_TYPE, PAUSE_TYPE_SPECIFIC_WIFI)
                .putString(KEY_PAUSE_SSID, condition.ssid)
                .remove(KEY_PAUSE_RESUME_AT)
            is PauseCondition.Manual -> editor.putString(KEY_PAUSE_TYPE, PAUSE_TYPE_MANUAL)
                .remove(KEY_PAUSE_RESUME_AT)
                .remove(KEY_PAUSE_SSID)
        }
        editor.apply()
    }

    /** [PeriodicSyncWorker.applySettings] and [InstantSyncService.applySettings] both gate on this
     *  first, regardless of [getSyncMode] — see their own doc comments. */
    fun isPaused(): Boolean = getPauseCondition() != null

    /**
     * Called from the shared sign-out path ([app.fastdrive.android.auth.handleUnauthorized]) to
     * wipe everything here that was configured in the context of the account that just signed
     * out: the folder pick, this engine's own remote cursor, and Phase 1's separate
     * `changes_cursor` (`FileListViewModel`'s own key, but the SAME underlying prefs file — see
     * this class's own doc comment).
     *
     * The folder pick is, in principle, account-agnostic (it is just a path on disk), but it was
     * chosen while a specific account was signed in. Clearing it here is the safer of two
     * reasonable choices: it forces a fresh pick + a fresh full sync the next time ANY account
     * signs in, with no risk of a new account's remote snapshot being reconciled (via a stale
     * `sync_base`, cleared separately by [handleUnauthorized] itself) against a folder that may
     * hold a completely different account's files. The alternative (keep the folder, only wipe
     * the sync tables/cursors) would save the next sign-in a folder re-pick, but silently reuses
     * a folder chosen for someone else — not worth the ambiguity.
     *
     * Finding #2 (Phase 4 fix round): a pause condition set by the previous account used to
     * survive sign-out untouched, so a brand-new account signing in on the same device could
     * inherit a stale "paused until connected to HomeWifi" (or an indefinite manual pause) with no
     * visible cause. This now clears the three pause keys too and cancels any pending
     * [PauseResumeWorker] timer job — [WorkManager] is reached directly via [appContext] rather
     * than adding a `Context` parameter to the shared `handleUnauthorized()` call this is invoked
     * from, which would also touch [app.fastdrive.android.sync.SyncOrchestrator]'s own call site.
     * A leftover [WifiResumeWatcher] registration (Activity-scoped, unreachable from here) is torn
     * down separately at the UI's sign-out observation point.
     */
    fun clearAccountState() {
        prefs.edit()
            .remove(KEY_FOLDER_URI)
            .remove(KEY_REMOTE_CURSOR)
            .remove(KEY_CHANGES_CURSOR_SHARED_WITH_FILE_LIST_VIEW_MODEL)
            .remove(KEY_PAUSE_TYPE)
            .remove(KEY_PAUSE_RESUME_AT)
            .remove(KEY_PAUSE_SSID)
            .apply()
        WorkManager.getInstance(appContext).cancelUniqueWork(PauseResumeWorker.PAUSE_RESUME_WORK_NAME)
    }

    private companion object {
        const val KEY_FOLDER_URI = "sync_folder_uri"
        const val KEY_SYNC_MODE = "sync_mode"
        const val KEY_REMOTE_CURSOR = "sync_remote_cursor"
        const val KEY_PAUSE_TYPE = "sync_pause_type"
        const val KEY_PAUSE_RESUME_AT = "sync_pause_resume_at"
        const val KEY_PAUSE_SSID = "sync_pause_ssid"

        const val PAUSE_TYPE_TIMER = "TIMER"
        const val PAUSE_TYPE_ANY_WIFI = "ANY_WIFI"
        const val PAUSE_TYPE_SPECIFIC_WIFI = "SPECIFIC_WIFI"
        const val PAUSE_TYPE_MANUAL = "MANUAL"

        /** `FileListViewModel`'s own private `CURSOR_KEY` constant, duplicated here (not
         *  imported — it's `private` and lives in the `ui` package) because both classes agree by
         *  convention on the same underlying prefs file, `"fastdrive_sync_prefs"`. */
        const val KEY_CHANGES_CURSOR_SHARED_WITH_FILE_LIST_VIEW_MODEL = "changes_cursor"
    }
}
