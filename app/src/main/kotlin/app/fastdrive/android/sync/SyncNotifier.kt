package app.fastdrive.android.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.fastdrive.android.MainActivity

/** Same channel [DownloadWorker] posts its own notifications on (id "fastdrive_downloads", name
 *  "Downloads", `IMPORTANCE_DEFAULT`) — that constant is a private `const val` there, so the exact
 *  literal is duplicated here rather than inventing a second channel for what is, to the user,
 *  the same kind of background-file-activity notification. */
private const val CHANNEL_ID = "fastdrive_downloads"
private const val CHANNEL_NAME = "Downloads"

/** Distinct from [DownloadWorker]'s own per-file notification ids (`fileName.hashCode()`) and from
 *  [InstantSyncService.NOTIFICATION_ID] (1001, the persistent "FastDrive is syncing" notification)
 *  so this one never collides with either. */
private const val SYNC_NOTIFICATION_ID = 2001

/** Distinct from both ids above — Finding #4's (Phase 4 fix round) "couldn't auto-resume" notice. */
private const val COULD_NOT_AUTO_RESUME_NOTIFICATION_ID = 2002

/**
 * Whether a just-finished sync pass is worth telling the user about at all — a pass where nothing
 * moved and nothing failed (the common case on a battery-friendly tick with no changes on either
 * side) would just be noise.
 */
fun shouldNotify(result: SyncResult): Boolean {
    val hasActivity = result.uploaded > 0 || result.downloaded > 0 || result.deletedLocal > 0 ||
        result.deletedRemote > 0 || result.moved > 0 || result.conflicts > 0
    return hasActivity || result.failed.isNotEmpty()
}

/**
 * Renders [result] into the notification's body text. Pure and Android-framework-free so it's
 * directly unit-testable, per this task's own requirement.
 */
fun notificationText(result: SyncResult): String {
    val parts = mutableListOf<String>()
    if (result.uploaded > 0) parts.add("${result.uploaded} uploaded")
    if (result.downloaded > 0) parts.add("${result.downloaded} downloaded")
    if (result.deletedLocal + result.deletedRemote > 0) parts.add("${result.deletedLocal + result.deletedRemote} deleted")
    if (result.moved > 0) parts.add("${result.moved} moved")
    if (result.conflicts > 0) parts.add("${result.conflicts} conflict${if (result.conflicts == 1) "" else "s"}")
    val activity = if (parts.isEmpty()) null else parts.joinToString(", ")
    val failure = if (result.failed.isNotEmpty()) "${result.failed.size} couldn't sync" else null
    return listOfNotNull(activity, failure).joinToString(" — ")
}

/**
 * Posts a notification summarizing [result], but only when [shouldNotify] says it's worth
 * surfacing — called right after [logSyncResult] from both [PeriodicSyncWorker] and
 * [InstantSyncService] so either sync trigger reports the same way.
 */
fun postSyncNotification(context: Context, result: SyncResult) {
    if (!shouldNotify(result)) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }
    val text = notificationText(result)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("FastDrive sync")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(SYNC_NOTIFICATION_ID, notification)
}

/**
 * Finding #4 (Phase 4 fix round): when an auto-resume (a timer or Wi-Fi pause condition clearing)
 * calls [InstantSyncService.applySettings] -> [InstantSyncService.start] while the app is
 * backgrounded on API 31+, `startForegroundService()` can throw
 * `ForegroundServiceStartNotAllowedException`. That was previously only logged — sync silently
 * stayed off with no visible signal, contradicting the UI's own "not paused" status. This posts a
 * plain notice instead, reusing [DownloadWorker]'s own "fastdrive_downloads" channel (see this
 * file's own [CHANNEL_ID] doc comment) since this is, to the user, the same kind of background-
 * file-activity notice. Tapping it opens [MainActivity], whose own `onCreate` already re-asserts
 * the current sync mode/settings — simply reopening the app is enough to resume sync.
 *
 * Deliberately minimal per this fix's own scope: no retry loop, no WorkManager fallback enqueue,
 * just making the silent failure visible.
 */
fun postCouldNotAutoResumeNotification(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        openAppIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("Sync couldn't resume automatically")
        .setContentText("Open FastDrive to resume")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(COULD_NOT_AUTO_RESUME_NOTIFICATION_ID, notification)
}
