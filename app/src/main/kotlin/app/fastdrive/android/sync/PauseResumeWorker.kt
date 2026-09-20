package app.fastdrive.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Phase 4 Task 3's timer-based auto-resume: a one-shot `CoroutineWorker` scheduled by
 * [pauseForDuration] (and, via it, [pauseUntilTomorrowMorning]) to fire once the paused duration
 * elapses.
 *
 * It only ever clears a still-active [PauseCondition.Timer] — if the pause condition changed to
 * something else before this fired (the user picked a different pause condition, or called
 * [resumeNow] and this job's cancellation lost a race with its own firing), doing nothing here is
 * correct: whatever is currently set is the user's latest intent, not this stale timer's.
 */
class PauseResumeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val syncSettings = SyncSettings(applicationContext)
        val condition = syncSettings.getPauseCondition()
        if (condition is PauseCondition.Timer) {
            syncSettings.setPauseCondition(null)
            PeriodicSyncWorker.applySettings(applicationContext, syncSettings)
            InstantSyncService.applySettings(applicationContext, syncSettings)
        }
        return Result.success()
    }

    companion object {
        /** Stable unique work name so [resumeNow] (and a later timer re-pause) can cancel a
         *  still-pending timer via `WorkManager.cancelUniqueWork`. */
        const val PAUSE_RESUME_WORK_NAME = "pause_resume_timer"

        /**
         * Pauses sync for [durationMillis] from now: stores a [PauseCondition.Timer], immediately
         * stops both trigger mechanisms (via [PeriodicSyncWorker.applySettings] /
         * [InstantSyncService.applySettings], both of which now gate on [SyncSettings.isPaused]),
         * and enqueues this worker to fire once the duration elapses and auto-resume.
         *
         * [ExistingWorkPolicy.REPLACE] means re-pausing (e.g. picking a new duration before the
         * previous one elapsed) always reschedules from the new duration rather than leaving the
         * old timer to fire early.
         */
        fun pauseForDuration(context: Context, syncSettings: SyncSettings, durationMillis: Long) {
            syncSettings.setPauseCondition(PauseCondition.Timer(System.currentTimeMillis() + durationMillis))
            PeriodicSyncWorker.applySettings(context, syncSettings)
            InstantSyncService.applySettings(context, syncSettings)

            val request = OneTimeWorkRequestBuilder<PauseResumeWorker>()
                .setInitialDelay(durationMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(PAUSE_RESUME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Pauses until the next 7:00 AM local time (today's 7 AM if it hasn't passed yet,
         *  otherwise tomorrow's) via the same [pauseForDuration] mechanism. */
        fun pauseUntilTomorrowMorning(context: Context, syncSettings: SyncSettings) {
            pauseForDuration(context, syncSettings, delayUntilNextSevenAmMillis())
        }

        private fun delayUntilNextSevenAmMillis(clock: Calendar = Calendar.getInstance()): Long {
            val now = clock
            val target = now.clone() as Calendar
            target.set(Calendar.HOUR_OF_DAY, 7)
            target.set(Calendar.MINUTE, 0)
            target.set(Calendar.SECOND, 0)
            target.set(Calendar.MILLISECOND, 0)
            if (!target.after(now)) {
                target.add(Calendar.DAY_OF_MONTH, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }

        /**
         * Resumes sync immediately, regardless of which [PauseCondition] is currently set:
         * cancels any pending timer worker, clears the stored condition, and re-applies settings
         * to both trigger mechanisms so they pick back up per the user's chosen [SyncMode].
         *
         * [onNetworkWatcherTeardown] is a seam for Task 4's Wi-Fi-based auto-resume: that task
         * registers a network callback while a [PauseCondition.AnyWifi]/[PauseCondition.SpecificWifi]
         * condition is active, and a manual resume needs to unregister it too. It is a no-arg
         * callback rather than this function reaching into Task 4's state directly, so this file
         * doesn't need to know that watcher's type.
         */
        fun resumeNow(
            context: Context,
            syncSettings: SyncSettings,
            onNetworkWatcherTeardown: (() -> Unit)? = null,
        ) {
            WorkManager.getInstance(context).cancelUniqueWork(PAUSE_RESUME_WORK_NAME)
            onNetworkWatcherTeardown?.invoke()
            syncSettings.setPauseCondition(null)
            PeriodicSyncWorker.applySettings(context, syncSettings)
            InstantSyncService.applySettings(context, syncSettings)
        }
    }
}
