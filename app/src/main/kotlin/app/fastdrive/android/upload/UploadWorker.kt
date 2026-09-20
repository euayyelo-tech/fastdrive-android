package app.fastdrive.android.upload

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.api.PartETag
import app.fastdrive.android.api.UploadUrlResponse
import app.fastdrive.android.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream

/**
 * Uploads a single file picked via [FileAccess] to FastDrive.
 *
 * Files under [MULTIPART_THRESHOLD] go up as a single PUT ([uploadSingle]); larger files are
 * split into parts and uploaded concurrently with retries ([uploadMultipart]), mirroring
 * `fastdrive-app/lib/upload-rules.ts`'s protocol exactly.
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

        val picked = try {
            fileAccess.stat(uri)
        } catch (e: Exception) {
            return Result.failure()
        }
        if (picked.size < 0) return Result.failure() // size unknown — see Task 1's noted edge case

        val api = apiFactory(baseUrl, tokenProvider(), httpClient)

        // `start` is obtained outside the abort-guarded block below: nothing has been
        // created server-side yet if uploadUrl() itself fails, so there is nothing to abort.
        val start = try {
            val multipart = picked.size >= MULTIPART_THRESHOLD
            api.uploadUrl(picked.name, picked.contentType, picked.size, folder, multipart)
        } catch (e: Exception) {
            return Result.failure()
        }

        return try {
            if (!start.multipart) {
                uploadSingle(picked, start)
            } else {
                uploadMultipart(picked, start, api)
            }
            api.uploadConfirm(start.id)
            Result.success()
        } catch (e: Exception) {
            // Best-effort per DriveApi.uploadAbort's own contract — only meaningful for the
            // multipart path (a failed single PUT has nothing to abort server-side beyond not
            // calling confirm), but calling it whenever an uploadId exists is harmless.
            start.uploadId?.let { uploadId -> api.uploadAbort(start.id, uploadId) }
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

    /**
     * Splits the file into [PART_SIZE] chunks, requests signed URLs [PART_URL_BATCH] parts at a
     * time (the server won't hand back more than that in one call), and PUTs up to
     * [PARALLEL_PARTS] of them concurrently — capped with a [Semaphore], the idiomatic coroutine
     * primitive for this rather than a fixed-size dispatcher or a third-party rate limiter.
     */
    private suspend fun uploadMultipart(picked: PickedFile, start: UploadUrlResponse, api: DriveApi) {
        val partSize = start.partSize ?: PART_SIZE
        val totalParts = start.parts ?: error("multipart response missing parts count")
        val uploadId = start.uploadId ?: error("multipart response missing uploadId")
        val etags = mutableListOf<PartETag>()
        val semaphore = Semaphore(PARALLEL_PARTS)

        var partNumber = 1
        while (partNumber <= totalParts) {
            val batchEnd = minOf(partNumber + PART_URL_BATCH - 1, totalParts)
            val urls = api.uploadParts(start.id, uploadId, partNumber, batchEnd).urls

            val batchEtags = coroutineScope {
                (partNumber..batchEnd).map { p ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val url = urls[p.toString()] ?: error("no signed URL for part $p")
                            val offset = (p - 1).toLong() * partSize
                            val length = minOf(partSize, picked.size - offset)
                            PartETag(p, uploadPartWithRetry(url, picked, offset, length, start.headers))
                        }
                    }
                }.awaitAll()
            }
            etags.addAll(batchEtags)
            partNumber = batchEnd + 1
        }

        api.uploadComplete(start.id, uploadId, etags.sortedBy { it.PartNumber })
    }

    /**
     * Uploads a single byte range, retrying up to [MAX_TRIES] times. `content://` streams don't
     * reliably support random seeking, so each attempt re-opens a fresh stream from the start and
     * skips forward to [offset] rather than trying to seek an already-open one.
     */
    private fun uploadPartWithRetry(
        url: String, picked: PickedFile, offset: Long, length: Long, headers: Map<String, String>?,
    ): String {
        var lastError: Exception? = null
        repeat(MAX_TRIES) {
            try {
                val bytes = fileAccess.openStream(picked.uri).use { stream ->
                    skipFully(stream, offset)
                    readExactly(stream, length.toInt())
                }
                val requestBuilder = Request.Builder().url(url).put(bytes.toRequestBody())
                headers?.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("part PUT failed: ${response.code}")
                    return response.header("ETag")?.trim('"')
                        ?: throw IllegalStateException("no ETag returned for part PUT")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("part upload failed after $MAX_TRIES attempts")
    }

    // InputStream.readNBytes(int) would be the obvious one-liner here, but it's API 33+ and this
    // app's minSdk is 29 — using it would crash real uploads on API 29-32 devices. read() into a
    // fixed buffer works on every API level and single reads aren't guaranteed to fill the buffer
    // either, so this loops the same way skipFully does below.
    private fun readExactly(stream: InputStream, byteCount: Int): ByteArray {
        val buffer = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val read = stream.read(buffer, offset, byteCount - offset)
            if (read < 0) throw IllegalStateException("unexpected end of stream while reading part")
            offset += read
        }
        return buffer
    }

    // InputStream.skip() isn't guaranteed to skip the full requested amount in one call — for a
    // large offset on a content:// stream it routinely returns less, so this loops until either
    // the full offset has been skipped or the stream runs out.
    private fun skipFully(stream: InputStream, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (stream.read() < 0) {
                throw IllegalStateException("unexpected end of stream while skipping to offset")
            } else {
                remaining -= 1
            }
        }
    }

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
