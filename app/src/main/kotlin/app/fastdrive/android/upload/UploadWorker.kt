package app.fastdrive.android.upload

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.api.UploadUrlResponse
import app.fastdrive.android.auth.TokenStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Uploads a single file picked via [FileAccess] to FastDrive.
 *
 * Only the single-PUT path (files under [MULTIPART_THRESHOLD]) is implemented here — the
 * multipart branch is a deliberate `TODO` for Task 4, which extends this same file rather than
 * adding a parallel one (mirroring Phase 1's Task 3 -> Task 5 pattern for `apply.mjs`).
 *
 * [tokenProvider] mirrors `DownloadWorker`'s reasoning: WorkManager persists `inputData` to its
 * own unencrypted database, so the auth token is deliberately never passed through it — it is
 * read fresh from `TokenStore` each time [doWork] actually runs.
 *
 * [httpClient] and [apiFactory] are both overridable seams so tests can fake the network (an
 * interceptor-equipped `OkHttpClient`, the same technique `DriveApiTest` already uses) instead of
 * hitting a real server, without needing a second fake abstraction layered on top of `DriveApi`.
 */
class UploadWorker @JvmOverloads constructor(
    context: Context,
    params: WorkerParameters,
    private val fileAccess: FileAccess = ContentResolverFileAccess(context.contentResolver),
    private val tokenProvider: () -> String? = { TokenStore(context.applicationContext).getToken() },
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val apiFactory: (baseUrl: String, token: String?, client: OkHttpClient) -> DriveApi =
        { baseUrl, token, client -> DriveApi(baseUrl, tokenProvider = { token }, client = client) },
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uriString = inputData.getString("uri") ?: return Result.failure()
        val folder = inputData.getString("folder") ?: "/"
        val baseUrl = inputData.getString("base_url") ?: return Result.failure()
        val uri = Uri.parse(uriString)

        return try {
            val picked = fileAccess.stat(uri)
            if (picked.size < 0) return Result.failure() // size unknown — see Task 1's noted edge case

            val api = apiFactory(baseUrl, tokenProvider(), httpClient)
            val multipart = picked.size >= MULTIPART_THRESHOLD
            val start = api.uploadUrl(picked.name, picked.contentType, picked.size, folder, multipart)

            if (!start.multipart) {
                uploadSingle(picked, start)
            } else {
                uploadMultipart(picked, start, api) // Task 4 implements this branch's body
            }
            api.uploadConfirm(start.id)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun uploadSingle(picked: PickedFile, start: UploadUrlResponse) {
        val bytes = fileAccess.openStream(picked.uri).use { it.readBytes() }
        val mediaType = picked.contentType.toMediaType()
        val requestBuilder = Request.Builder().url(start.url!!).put(bytes.toRequestBody(mediaType))
        start.headers?.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("upload PUT failed: ${response.code}")
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun uploadMultipart(picked: PickedFile, start: UploadUrlResponse, api: DriveApi) {
        TODO("Task 4 implements this")
    }

    companion object {
        const val MULTIPART_THRESHOLD = 32L * 1024 * 1024
    }
}
