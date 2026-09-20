package app.fastdrive.android.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

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
