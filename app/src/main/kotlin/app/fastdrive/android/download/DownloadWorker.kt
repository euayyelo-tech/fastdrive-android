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
import okhttp3.Request
import java.io.File

private const val CHANNEL_ID = "fastdrive_downloads"
private const val CHANNEL_NAME = "Downloads"

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
        { baseUrl, token -> DriveApi(baseUrl, token) },
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val fileId = inputData.getString("file_id") ?: return Result.failure()
        val fileName = inputData.getString("file_name") ?: return Result.failure()
        val baseUrl = inputData.getString("base_url") ?: return Result.failure()

        return try {
            val token = tokenProvider()
            val api = apiFactory(baseUrl, token)
            val downloadInfo = api.downloadUrl(fileId)
            if (downloadInfo.vault) {
                notifyFailure(fileName)
                return Result.failure()
            }

            val client = OkHttpClient()
            val request = Request.Builder().url(downloadInfo.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body == null) {
                    notifyFailure(fileName)
                    return Result.failure()
                }
                val downloadsDir = File(applicationContext.filesDir, "downloads").apply { mkdirs() }
                val destination = File(downloadsDir, fileName)
                response.body!!.byteStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
            Result.success()
        } catch (e: Exception) {
            notifyFailure(fileName)
            Result.failure()
        }
    }

    /**
     * Posts a plain notification via `NotificationManagerCompat` on failure. This is a short,
     * one-shot download rather than long-running work, so there's no need for the
     * `setForeground`/`setForegroundAsync` foreground-service promotion WorkManager's docs
     * describe for long-running workers — a regular post-hoc notification from within `doWork()`
     * is the documented pattern for this case.
     */
    private fun notifyFailure(fileName: String) {
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
            .setContentTitle("Download failed")
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(fileName.hashCode(), notification)
    }
}
