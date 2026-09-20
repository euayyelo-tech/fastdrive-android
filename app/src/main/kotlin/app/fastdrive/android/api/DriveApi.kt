package app.fastdrive.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val status: Int, message: String) : Exception(message)

/**
 * Seam for [DriveApi.downloadUrl] so callers (namely `DownloadWorker`) can be unit-tested with a
 * fake implementation instead of a real network client.
 */
interface DownloadUrlProvider {
    suspend fun downloadUrl(fileId: String): DownloadUrlResponse
}

/**
 * [tokenProvider] is read fresh on every call instead of caching a token field, so `DriveApi`
 * always sees whatever `TokenStore` currently holds with no separate sync step — e.g. after
 * sign-in, sign-out, or a 401-triggered `TokenStore.clear()`, the very next call already sees the
 * new value. `DownloadWorker` already follows this pattern; this brings `DriveApi` in line with it.
 */
class DriveApi(
    private val baseUrl: String,
    private val tokenProvider: () -> String? = { null },
    private val client: OkHttpClient = OkHttpClient(),
) : DownloadUrlProvider {
    private val json = Json { ignoreUnknownKeys = true }

    private inline fun <reified T> call(path: String, method: String = "GET", body: String? = null): T {
        val requestBuilder = Request.Builder().url("$baseUrl$path")
        tokenProvider()?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        val mediaType = "application/json".toMediaType()
        when (method) {
            "POST" -> requestBuilder.post((body ?: "{}").toRequestBody(mediaType))
            "GET" -> requestBuilder.get()
            else -> throw IllegalArgumentException("unsupported method $method")
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            val text = response.body?.string() ?: "{}"
            if (!response.isSuccessful) throw ApiException(response.code, "$path said ${response.code}: $text")
            return json.decodeFromString(text)
        }
    }

    suspend fun deviceCode(name: String, platform: String = "android"): DeviceCodeResponse =
        withContext(Dispatchers.IO) {
            call("/api/devices/code", "POST", json.encodeToString(mapOf("name" to name, "platform" to platform)))
        }

    suspend fun devicePoll(id: String, secret: String): DevicePollResponse =
        withContext(Dispatchers.IO) {
            call("/api/devices/poll", "POST", json.encodeToString(mapOf("id" to id, "secret" to secret)))
        }

    suspend fun changes(cursor: Cursor?, limit: Int = 500): ChangesPage =
        withContext(Dispatchers.IO) {
            val query = buildString {
                append("?limit=$limit")
                cursor?.let { append("&since=${it.at}&after=${it.id}") }
            }
            call("/api/changes$query")
        }

    override suspend fun downloadUrl(fileId: String): DownloadUrlResponse =
        withContext(Dispatchers.IO) {
            call("/api/files/$fileId/download")
        }

    // The desktop client's whoami() hits `/api/files?folder=` (there is no dedicated
    // whoami route server-side) and reads back { you, plan, used, quota }. Kept as a
    // GET with no body, matching desktop/src/engine/api.ts exactly.
    suspend fun whoami(): WhoamiResponse =
        withContext(Dispatchers.IO) {
            call("/api/files?folder=")
        }

    // Upload protocol, mirroring app/api/files/{upload-url,[id]/parts,[id]/complete,
    // [id]/confirm,[id]/abort}/route.ts field-for-field (verified by reading those
    // route handlers directly, not transcribed from a plan document).
    suspend fun uploadUrl(
        name: String, contentType: String, size: Long, folder: String,
        multipart: Boolean, mtime: String? = null, sha256: String? = null, replace: String? = null,
    ): UploadUrlResponse = withContext(Dispatchers.IO) {
        // A typed request body, not a Map<String, String> — see UploadUrlRequest's doc comment in
        // Models.kt for why the old buildMap approach silently broke every multipart upload.
        val body = UploadUrlRequest(
            name = name, contentType = contentType, size = size, folder = folder,
            multipart = multipart, mtime = mtime, sha256 = sha256, replace = replace,
        )
        call("/api/files/upload-url", "POST", json.encodeToString(body))
    }

    suspend fun uploadParts(id: String, uploadId: String, from: Int, to: Int): PartsResponse =
        withContext(Dispatchers.IO) {
            call("/api/files/$id/parts", "POST", json.encodeToString(PartsRequest(uploadId, from, to)))
        }

    suspend fun uploadComplete(id: String, uploadId: String, parts: List<PartETag>) {
        withContext(Dispatchers.IO) {
            // A plain mapOf(String to Any) here (mixing the uploadId string with the
            // parts list) has no kotlinx.serialization serializer for `Any` and fails
            // at runtime, so this request body needs its own @Serializable class
            // rather than the mapOf shortcut the other four upload calls use.
            call<Unit>("/api/files/$id/complete", "POST", json.encodeToString(CompleteRequest(uploadId, parts)))
        }
    }

    suspend fun uploadConfirm(id: String) {
        withContext(Dispatchers.IO) { call<Unit>("/api/files/$id/confirm", "POST") }
    }

    // Best-effort by design (matches [id]/abort/route.ts, which itself always
    // returns { ok: true } even for an unknown file/uploadId): a failed abort call
    // is swallowed here so every caller gets that behavior for free, rather than
    // needing to remember to wrap it themselves.
    suspend fun uploadAbort(id: String, uploadId: String) {
        withContext(Dispatchers.IO) {
            runCatching { call<Unit>("/api/files/$id/abort", "POST", json.encodeToString(mapOf("uploadId" to uploadId))) }
        }
    }
}
