package app.fastdrive.android.upload

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.auth.TokenAccess
import app.fastdrive.android.auth.TokenStore
import app.fastdrive.android.auth.handleUnauthorized
import app.fastdrive.android.auth.isUnauthorized
import app.fastdrive.android.data.AppDatabase
import app.fastdrive.android.sync.SyncSettings
import okhttp3.OkHttpClient

/**
 * Uploads a single file picked via [FileAccess] to FastDrive.
 *
 * The actual upload protocol (upload-url -> PUT(s) -> confirm, multipart splitting, retries,
 * abort-on-failure) lives in [FileUploader] — extracted so Task 6's `SyncOrchestrator` can drive
 * the exact same protocol directly during a sync pass, without going through a WorkManager job.
 * This class now just wires [FileUploader] to WorkManager's lifecycle: read `inputData`, call it,
 * translate the outcome into a [Result].
 *
 * [tokenProvider] mirrors `DownloadWorker`'s reasoning: WorkManager persists `inputData` to its
 * own unencrypted database, so the auth token is deliberately never passed through it — it is
 * read fresh from `TokenStore` each time [doWork] actually runs. [tokenStore] is the same
 * underlying store: on a 401 this worker clears it directly (mirroring
 * `FileListViewModel.refresh()`'s own 401 handling via the shared `auth.isUnauthorized`/
 * `handleUnauthorized` helpers) since a background worker has no way to navigate the UI itself —
 * `FileListScreen` picks that up by checking the failed `WorkInfo`'s `auth_error` output flag and
 * calling `FileListViewModel.notifySignedOutFromBackground()`.
 *
 * [httpClient] and [apiFactory] are both overridable seams so tests can fake the network (an
 * interceptor-equipped `OkHttpClient`, the same technique `DriveApiTest` already uses) instead of
 * hitting a real server, without needing a second fake abstraction layered on top of `DriveApi`.
 */
class UploadWorker @JvmOverloads constructor(
    context: Context,
    params: WorkerParameters,
    private val fileAccess: FileAccess = ContentResolverFileAccess(context.contentResolver),
    private val tokenStore: TokenAccess = TokenStore(context.applicationContext),
    private val tokenProvider: () -> String? = { tokenStore.getToken() },
    // Only needed to hand to the shared handleUnauthorized() sign-out path (Finding #5) on a 401.
    private val database: AppDatabase = AppDatabase.get(context.applicationContext),
    private val syncSettings: SyncSettings = SyncSettings(context.applicationContext),
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val apiFactory: (baseUrl: String, token: String?, client: OkHttpClient) -> DriveApi =
        { baseUrl, token, client -> DriveApi(baseUrl, tokenProvider = { token }, client = client) },
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uriString = inputData.getString("uri") ?: return Result.failure()
        val folder = inputData.getString("folder") ?: "/"
        val baseUrl = inputData.getString("base_url") ?: return Result.failure()
        val uri = Uri.parse(uriString)
        val api = apiFactory(baseUrl, tokenProvider(), httpClient)

        return try {
            FileUploader.upload(fileAccess, httpClient, api, uri, folder)
            Result.success()
        } catch (e: Exception) {
            failureFor(e)
        }
    }

    /**
     * Turns a caught exception into the right terminal [Result]: a 401 clears the shared token
     * store and flags `auth_error` so the UI signs out; a retryable network/5xx/408/429 failure
     * becomes `Result.retry()` so WorkManager's own backoff schedules another attempt; anything
     * else is a permanent failure whose message is surfaced verbatim (never paraphrased) via
     * `outputData` so the UI can show the server's actual rejection reason.
     */
    private suspend fun failureFor(e: Exception): Result {
        if (isUnauthorized(e)) {
            handleUnauthorized(tokenStore, database, syncSettings)
            return Result.failure(workDataOf("error" to "You've been signed out.", "auth_error" to true))
        }
        if (retryable(statusOf(e))) {
            return Result.retry()
        }
        val message = e.message ?: "Upload failed."
        return Result.failure(workDataOf("error" to message))
    }

    private fun statusOf(e: Exception): Int = FileUploader.statusOf(e)

    companion object {
        const val MULTIPART_THRESHOLD = 32L * 1024 * 1024
        /** Matches fastdrive-app/lib/upload-rules.ts's PART_SIZE exactly. */
        const val PART_SIZE = 16L * 1024 * 1024
        /** Matches fastdrive-app/lib/upload-rules.ts's PARALLEL_PARTS exactly. */
        const val PARALLEL_PARTS = 4
        /** Matches fastdrive-app/lib/upload-rules.ts's PART_URL_BATCH exactly. */
        const val PART_URL_BATCH = 32
        /** Matches fastdrive-app/lib/upload-rules.ts's MAX_TRIES exactly. */
        const val MAX_TRIES = 4
    }
}
