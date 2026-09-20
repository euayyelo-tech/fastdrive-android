package app.fastdrive.android.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.fastdrive.android.api.DownloadUrlProvider
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.auth.TokenStore
import okhttp3.OkHttpClient
import java.io.File

private const val CHANNEL_ID = "fastdrive_downloads"
private const val CHANNEL_NAME = "Downloads"

/**
 * Strips any path components from a server-supplied file name before it is used to build a local
 * [File], so a crafted name like "../../elsewhere" or "/etc/passwd" can't escape the intended
 * download directory. Pulled out as a top-level function so it can be unit-tested directly,
 * without Robolectric or a real Context.
 */
internal fun sanitizeFileName(raw: String): String =
    raw.substringAfterLast('/').substringAfterLast('\\')

/**
 * Downloads a single file to `filesDir/downloads/<name>` in the background, surviving the app
 * being backgrounded or the process dying (WorkManager persists and retries the request).
 *
 * Deliberately does NOT accept an auth token via `inputData`: WorkManager persists `inputData`
 * to its own internal (unencrypted) SQLite database so the request survives process death, which
 * would mean a token lived somewhere other than `TokenStore`'s `EncryptedSharedPreferences`, even
 * briefly. Instead, [tokenProvider] reads the token fresh from `TokenStore` each time [doWork]
 * actually runs. [tokenProvider] and [apiFactory] are both overridable so tests can substitute a
 * fake without touching the Android Keystore or the network.
 */
class DownloadWorker @JvmOverloads constructor(
    context: Context,
    params: WorkerParameters,
    private val tokenProvider: () -> String? = { TokenStore(context.applicationContext).getToken() },
    private val apiFactory: (baseUrl: String, token: String?) -> DownloadUrlProvider =
        { baseUrl, token -> DriveApi(baseUrl, tokenProvider = { token }) },
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val fileId = inputData.getString("file_id") ?: return Result.failure()
        val rawFileName = inputData.getString("file_name") ?: return Result.failure()
        val baseUrl = inputData.getString("base_url") ?: return Result.failure()

        // `rawFileName` is server data relayed through WorkManager's inputData. Strip any path
        // components so a crafted name like "../../elsewhere" can't escape `downloadsDir`.
        val fileName = sanitizeFileName(rawFileName)
        if (fileName.isBlank()) {
            notifyFailure(rawFileName, "Download failed")
            return Result.failure()
        }

        return try {
            val token = tokenProvider()
            val api = apiFactory(baseUrl, token)

            val downloadsDir = File(applicationContext.filesDir, "downloads").apply { mkdirs() }
            val destination = File(downloadsDir, fileName)
            // Belt-and-braces: confirm the resolved path is still inside downloadsDir before
            // writing anything, in case some other trick got a "/" or ".." past the strip above.
            val downloadsDirCanonical = downloadsDir.canonicalFile
            val destinationCanonical = destination.canonicalFile
            if (!destinationCanonical.path.startsWith(downloadsDirCanonical.path + File.separator)) {
                notifyFailure(fileName, "Download failed")
                return Result.failure()
            }

            try {
                FileDownloader.download(api, OkHttpClient(), fileId) { destination.outputStream() }
            } catch (e: VaultFileException) {
                notifyFailure(fileName, "Vault files can't be downloaded yet")
                return Result.failure()
            }
            Result.success()
        } catch (e: Exception) {
            notifyFailure(fileName, "Download failed")
            Result.failure()
        }
    }

    /**
     * Posts a plain notification via `NotificationManagerCompat` on failure. This is a short,
     * one-shot download rather than long-running work, so there's no need for the
     * `setForeground`/`setForegroundAsync` foreground-service promotion WorkManager's docs
     * describe for long-running workers — a regular post-hoc notification from within `doWork()`
     * is the documented pattern for this case.
     *
     * [reason] distinguishes a vault-file refusal ("Vault files can't be downloaded yet") from a
     * genuine network/IO failure ("Download failed"), so the user isn't told a vault file "failed"
     * when it was never attempted.
     */
    private fun notifyFailure(fileName: String, reason: String) {
        val context = applicationContext
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(reason)
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(fileName.hashCode(), notification)
    }
}
