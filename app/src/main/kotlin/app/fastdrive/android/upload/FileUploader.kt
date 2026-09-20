package app.fastdrive.android.upload

import android.net.Uri
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.api.PartETag
import app.fastdrive.android.api.UploadUrlResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream

/**
 * The upload protocol itself (upload-url -> PUT(s) -> confirm, with retries/abort), extracted from
 * [UploadWorker] so Task 6's `SyncOrchestrator` can upload a file directly — as part of a single
 * sync pass, not via a separate WorkManager job per file — without re-implementing or duplicating
 * this logic. [UploadWorker] now delegates here for the exact same behavior; nothing about the
 * wire protocol changed in this extraction, only where the code lives.
 *
 * Mirrors `fastdrive-app/lib/upload-rules.ts` exactly, same as before: files under
 * [UploadWorker.MULTIPART_THRESHOLD] go up as a single PUT ([uploadSingle]); larger files are
 * split into parts and uploaded concurrently with retries ([uploadMultipart]).
 */
object FileUploader {

    /** A raw (non-DriveApi) PUT to a signed storage URL came back with a non-2xx [status].
     *  Carrying the status lets the retry loop consult [retryable] the same way an
     *  `ApiException` does. Exposed (not private) so [UploadWorker.statusOf] can still special-case
     *  it after this extraction. */
    class PutFailedException(val status: Int) : IOException("PUT failed: $status")

    /**
     * Runs the full upload protocol for the file at [uri] into [folder] (optionally replacing
     * [replace], an existing remote file id) and returns the server's [UploadUrlResponse] — the
     * orchestrator needs its `id` to record in `sync_base`; [UploadWorker] itself has never needed
     * the return value.
     *
     * [nameOverride] lets a caller upload under a different name than [uri]'s own — needed by
     * `SyncOrchestrator`'s `Conflict` handling, which uploads the local file's bytes under the
     * "(conflicted copy, ...)" name, not the file's real on-disk name. Every other caller (a plain
     * `Upload`, and `UploadWorker` itself) leaves it null and gets [uri]'s own name, unchanged.
     *
     * [mtime]/[sha256] are the LOCAL entry's own values as `SyncOrchestrator`'s scan already knows
     * them — passed straight through to `DriveApi.uploadUrl`'s request body so the server (and,
     * after this call, `sync_remote`/`sync_base` once the caller mirrors the result) records the
     * same bytes-identity the local scan used to decide an upload was needed at all. Without this,
     * the next pass's remote snapshot has no `mtime`/`sha256` for the file, `same()` can't match it
     * against `local`, and the file re-uploads/re-downloads forever. `UploadWorker`'s plain
     * (non-sync) uploads have no such local-scan entry to draw from and leave both null, unchanged.
     *
     * On any failure, best-effort aborts the upload server-side (mirrors the old `UploadWorker`
     * behavior exactly) and rethrows, so callers keep their own retry/error-mapping policy.
     *
     * [expectedSize] is the size the caller's own scan expected this file to be (`Entry.size`) —
     * passed only by `SyncOrchestrator`, which has such an expectation; `UploadWorker`'s plain,
     * non-sync uploads leave it null and skip this check entirely. When it's given and the file's
     * CURRENT size (re-stat'd here, right before upload) no longer matches, the file is still being
     * written to since the scan ran: this returns null rather than uploading half-written bytes
     * under a now-stale hash, deferring to the next sync pass instead — the same guard the
     * reference engine's `upload()` applies (`fastdrive-app/desktop/src/engine/sync.ts:203`), which
     * also checks `mtime` where its stat call exposes one; [FileAccess.stat] doesn't return an
     * mtime, so size is the drift check available here. This is an expected, benign "file busy"
     * case, not a failure — callers must not log or report it as one.
     */
    suspend fun upload(
        fileAccess: FileAccess,
        httpClient: OkHttpClient,
        api: DriveApi,
        uri: Uri,
        folder: String,
        replace: String? = null,
        nameOverride: String? = null,
        mtime: String? = null,
        sha256: String? = null,
        expectedSize: Long? = null,
    ): UploadUrlResponse? {
        val picked = try {
            fileAccess.stat(uri)
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "Couldn't read the selected file.", e)
        }
        if (picked.size < 0) {
            // size unknown — see Task 1's noted edge case
            throw IllegalStateException("Couldn't determine the file's size.")
        }
        if (expectedSize != null && picked.size != expectedSize) {
            return null
        }

        val multipart = picked.size >= UploadWorker.MULTIPART_THRESHOLD
        val start = api.uploadUrl(
            nameOverride ?: picked.name, picked.contentType, picked.size, folder, multipart,
            mtime = mtime, sha256 = sha256, replace = replace,
        )

        try {
            if (!start.multipart) {
                uploadSingle(fileAccess, httpClient, picked, start)
            } else {
                uploadMultipart(fileAccess, httpClient, picked, start, api)
            }
            api.uploadConfirm(start.id)
            return start
        } catch (e: Exception) {
            // Best-effort per DriveApi.uploadAbort's own contract — only meaningful for the
            // multipart path, but calling it whenever an uploadId exists is harmless.
            start.uploadId?.let { uploadId -> api.uploadAbort(start.id, uploadId) }
            throw e
        }
    }

    /** Same classification `UploadWorker.failureFor` used before this extraction — kept here so
     *  both `UploadWorker` and any other caller judge retryability identically. */
    fun statusOf(e: Exception): Int = when (e) {
        is app.fastdrive.android.api.ApiException -> e.status
        is PutFailedException -> e.status
        is IOException -> 0
        else -> -1
    }

    private suspend fun uploadSingle(fileAccess: FileAccess, httpClient: OkHttpClient, picked: PickedFile, start: UploadUrlResponse) {
        retrying { _ ->
            val bytes = fileAccess.openStream(picked.uri).use { it.readBytes() }
            val mediaType = picked.contentType.toMediaType()
            val requestBuilder = Request.Builder().url(start.url!!).put(bytes.toRequestBody(mediaType))
            start.headers?.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) throw PutFailedException(response.code)
            }
        }
    }

    /**
     * Splits the file into [UploadWorker.PART_SIZE] chunks, requests signed URLs
     * [UploadWorker.PART_URL_BATCH] parts at a time, and PUTs up to [UploadWorker.PARALLEL_PARTS]
     * of them concurrently — capped with a [Semaphore].
     */
    private suspend fun uploadMultipart(fileAccess: FileAccess, httpClient: OkHttpClient, picked: PickedFile, start: UploadUrlResponse, api: DriveApi) {
        val partSize = start.partSize ?: UploadWorker.PART_SIZE
        val totalParts = start.parts ?: error("multipart response missing parts count")
        val uploadId = start.uploadId ?: error("multipart response missing uploadId")
        val etags = mutableListOf<PartETag>()
        val semaphore = Semaphore(UploadWorker.PARALLEL_PARTS)

        var partNumber = 1
        while (partNumber <= totalParts) {
            val batchEnd = minOf(partNumber + UploadWorker.PART_URL_BATCH - 1, totalParts)
            val urls = api.uploadParts(start.id, uploadId, partNumber, batchEnd).urls

            val batchEtags = coroutineScope {
                (partNumber..batchEnd).map { p ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val url = urls[p.toString()] ?: error("no signed URL for part $p")
                            val offset = (p - 1).toLong() * partSize
                            val length = minOf(partSize, picked.size - offset)
                            PartETag(p, uploadPartWithRetry(fileAccess, httpClient, url, picked, offset, length, start.headers))
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
     * Uploads a single byte range, retrying via [retrying]. `content://` streams don't reliably
     * support random seeking, so each attempt re-opens a fresh stream from the start and skips
     * forward to [offset] rather than trying to seek an already-open one.
     */
    private suspend fun uploadPartWithRetry(
        fileAccess: FileAccess, httpClient: OkHttpClient, url: String, picked: PickedFile, offset: Long, length: Long, headers: Map<String, String>?,
    ): String {
        var result: String? = null
        retrying { _ ->
            val bytes = fileAccess.openStream(picked.uri).use { stream ->
                skipFully(stream, offset)
                readExactly(stream, length.toInt())
            }
            val requestBuilder = Request.Builder().url(url).put(bytes.toRequestBody())
            headers?.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) throw PutFailedException(response.code)
                result = response.header("ETag")?.trim('"')
                    ?: throw IllegalStateException("no ETag returned for part PUT")
            }
        }
        return result ?: throw IllegalStateException("part upload produced no ETag")
    }

    /**
     * Runs [attemptBlock] up to [UploadWorker.MAX_TRIES] times, exactly like `upload-rules.ts`'s
     * "`MAX_TRIES` per part or per small file" rule.
     */
    private suspend fun retrying(attemptBlock: suspend (attempt: Int) -> Unit) {
        var lastError: Exception? = null
        for (attempt in 1..UploadWorker.MAX_TRIES) {
            try {
                attemptBlock(attempt)
                return
            } catch (e: Exception) {
                lastError = e
                val status = statusOf(e)
                if (!retryable(status) || attempt == UploadWorker.MAX_TRIES) throw e
                delay(retryDelayMs(attempt))
            }
        }
        throw lastError ?: IllegalStateException("upload failed after ${UploadWorker.MAX_TRIES} attempts")
    }

    // InputStream.readNBytes(int) would be the obvious one-liner here, but it's API 33+ and this
    // app's minSdk is 29 — read() into a fixed buffer works on every API level.
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

    // InputStream.skip() isn't guaranteed to skip the full requested amount in one call.
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
}
