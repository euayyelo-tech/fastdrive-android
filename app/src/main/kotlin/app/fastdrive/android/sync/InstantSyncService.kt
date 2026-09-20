package app.fastdrive.android.sync

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "fastdrive_instant_sync"
private const val CHANNEL_NAME = "Instant sync"
private const val NOTIFICATION_ID = 1001

/**
 * "Instant" mode's trigger for Task 6's [SyncOrchestrator.runOnePass]: a foreground `Service` that
 * shows a persistent notification ("FastDrive is syncing") for as long as it runs, and loops
 * calling [runPass] every [SYNC_INTERVAL_MS] via a coroutine `delay()` loop rather than a
 * busy-loop or WorkManager's own (15-minute-minimum) periodic scheduling — that minimum is exactly
 * why Task 7's [PeriodicSyncWorker] can't offer this mode itself.
 *
 * [runPass] is a test seam, the same pattern [PeriodicSyncWorker] already uses for the same
 * reason: [SyncOrchestrator.runOnePass] needs a real `Context` wired to `AppDatabase`/`DriveApi`/a
 * real `DocumentTreeFileStore`, none of which a unit test wants to exercise here.
 *
 * A plain (non-Lifecycle) `Service` is deliberately used here rather than `LifecycleService` —
 * nothing in this codebase's existing services (`DownloadWorker`, `PeriodicSyncWorker`, both
 * `CoroutineWorker`s under WorkManager) needs lifecycle-scoped coroutines, and this service's own
 * loop is already scoped to a manually-owned [SupervisorJob] cancelled in [onDestroy], so
 * `LifecycleService`'s extra `lifecycleScope` machinery would add a dependency without a use.
 *
 * Targeting API 35+ (this project's targetSdk is 37) puts a 6-hour-per-24-hours system limit on a
 * running `dataSync` foreground service; [onTimeout] is the system's required hook for that limit
 * and just stops the service (matching the periodic worker's own retry-on-next-tick philosophy —
 * the user can simply be re-prompted or the app re-started to resume instant mode, rather than
 * this service trying to fight the platform limit).
 */
class InstantSyncService : Service() {

    // Overridable by tests without touching the real SyncOrchestrator/Context wiring.
    var runPass: suspend (Context) -> SyncResult = { SyncOrchestrator.runOnePass(it) }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        scope.launch {
            while (true) {
                // Finding #6: an uncaught exception from runPass() used to kill this loop
                // coroutine silently while the "FastDrive is syncing" notification stayed up,
                // misleading the user into thinking sync was still happening. A real cancellation
                // (the service stopping) must still propagate normally; everything else is logged
                // and the loop keeps going — the next tick, 30s from now, is this mechanism's own
                // retry, same philosophy as PeriodicSyncWorker's periodic tick.
                try {
                    val result = runPass(applicationContext)
                    // Finding #4: surface what this pass actually did instead of discarding it.
                    logSyncResult(result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(SYNC_LOG_TAG, "instant sync pass failed", e)
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky: if the system kills this process under memory pressure while instant mode is
        // still the user's chosen setting, restart the loop rather than silently going quiet
        // (there's no separate "still enabled?" re-check needed — SyncSettingsScreen/MainActivity
        // are the only callers that start this service, and both only do so while INSTANT mode is
        // the active setting).
        return START_STICKY
    }

    /**
     * The API-35+ 6-hour-per-24-hours system limit on a running `dataSync` foreground service
     * (see the class doc comment). The system gives a running service only a few seconds after
     * this call to actually stop before it is torn down uncleanly, so this does nothing but that.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FastDrive is syncing")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        /** Matches desktop's own instant-mode sync interval per the spec. */
        const val SYNC_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, InstantSyncService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // Finding #9: API 31+ can refuse a foreground-service start under certain
                // conditions (e.g. no active foreground exemption while starting from the
                // background). This is reachable from MainActivity.onCreate() re-asserting a
                // previously-chosen INSTANT mode on a cold start — better to log and leave instant
                // sync not running than crash the whole app on startup.
                Log.w(SYNC_LOG_TAG, "couldn't start the instant sync foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InstantSyncService::class.java))
        }

        /**
         * [PeriodicSyncWorker.applySettings]'s counterpart for this mode: starts/stops this
         * service to match [SyncSettings]'s current `getSyncMode()`/`getFolderUri()` — called
         * from the same two places (app startup, the settings screen) so the two mechanisms never
         * drift out of sync with each other or with the user's chosen mode. Together the pair
         * keeps the two modes mutually exclusive: whichever of [SyncMode.INSTANT] /
         * [SyncMode.BATTERY_FRIENDLY] is NOT current always gets torn down here or in
         * [PeriodicSyncWorker.applySettings], never left running alongside the other.
         */
        fun applySettings(context: Context, syncSettings: SyncSettings) {
            val shouldRun = syncSettings.getSyncMode() == SyncMode.INSTANT &&
                syncSettings.getFolderUri() != null
            if (shouldRun) start(context) else stop(context)
        }
    }
}
