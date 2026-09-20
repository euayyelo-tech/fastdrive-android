package app.fastdrive.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fastdrive.android.upload.retryable
import java.util.concurrent.TimeUnit

/**
 * WorkManager's "battery-friendly" trigger for Task 6's [SyncOrchestrator.runOnePass]: a
 * `CoroutineWorker` that runs one full sync pass every [PERIODIC_INTERVAL_MINUTES] minutes (the
 * Android minimum for periodic work) while [SyncSettings.getSyncMode] is [SyncMode.BATTERY_FRIENDLY].
 *
 * [runPass] is a test seam — [SyncOrchestrator.runOnePass] itself needs a real `Context` wired to
 * `AppDatabase`/`DriveApi`/a real `DocumentTreeFileStore`, none of which a unit test wants to
 * exercise here; tests substitute a fake that returns a canned [SyncResult] instead.
 *
 * `runOnePass` never actually throws — every failure mode it can hit (no folder set, can't open/
 * read the folder, can't reach the server, a mid-pass exception) is already caught internally and
 * turned into a [SyncResult] with an entry in [SyncResult.failed] instead of propagating (see its
 * own doc comment). Per-action failures are recorded there too. None of that is fatal to this
 * worker's run — the next periodic tick five, ten, fifteen minutes from now is this codebase's own
 * retry cadence for exactly this kind of transient trouble, so [doWork] returns `Result.success()`
 * whenever [runPass] returns normally, whether or not [SyncResult.failed] is empty.
 *
 * The `catch` block below exists purely as defense-in-depth against something [runPass] was never
 * designed to let through (an `OutOfMemoryError`-adjacent `RuntimeException`, a future regression),
 * reusing Phase 2's [retryable] status-code rule from `UploadRetryPolicy` exactly the way
 * `UploadWorker` already does, so a transient/network-shaped failure gets WorkManager's own retry
 * with backoff instead of silently waiting for the next periodic tick.
 */
class PeriodicSyncWorker @JvmOverloads constructor(
    context: Context,
    params: WorkerParameters,
    private val runPass: suspend (Context) -> SyncResult = { SyncOrchestrator.runOnePass(it) },
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val result = runPass(applicationContext)
            // Finding #4: surface what this pass actually did — counts, every skipped path, every
            // failure's own message — rather than silently discarding the SyncResult.
            logSyncResult(result)
            postSyncNotification(applicationContext, result)
            Result.success()
        } catch (e: Exception) {
            if (retryable(statusOf(e))) Result.retry() else Result.failure()
        }
    }

    /** No HTTP client of its own to ask, so this only ever sees a raw network-shaped exception
     *  (its `message`/type carry no real status) — treated as `0`, [retryable]'s own code for
     *  "network-level failure with no response", same as a timeout/connection-refused would be. */
    private fun statusOf(e: Exception): Int = 0

    companion object {
        const val WORK_NAME = "battery_friendly_sync"
        const val PERIODIC_INTERVAL_MINUTES = 15L

        /**
         * Registers or cancels the periodic work to match [SyncSettings]'s current
         * `getSyncMode()`/`getFolderUri()` — called on app startup and every time either setting
         * changes (mode switch, folder picked/changed) so the schedule never drifts from what the
         * user last chose.
         *
         * [ExistingPeriodicWorkPolicy.KEEP] leaves an already-running schedule alone rather than
         * restarting its 15-minute window from zero on every redundant call (e.g. app startup
         * while already enrolled).
         */
        fun applySettings(context: Context, syncSettings: SyncSettings) {
            val workManager = WorkManager.getInstance(context)
            // Phase 4 Task 3: a pause overrides the selected sync mode entirely, same as if no
            // folder were set — checked first, ahead of the mode/folder decision below.
            if (syncSettings.isPaused()) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val shouldRun = syncSettings.getSyncMode() == SyncMode.BATTERY_FRIENDLY &&
                syncSettings.getFolderUri() != null
            if (shouldRun) {
                val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(
                    PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES,
                ).build()
                workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                workManager.cancelUniqueWork(WORK_NAME)
            }
        }
    }
}
