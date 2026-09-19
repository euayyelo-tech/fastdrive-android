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

class DriveApi(
    private val baseUrl: String,
    private var token: String? = null,
    private val client: OkHttpClient = OkHttpClient(),
) : DownloadUrlProvider {
    private val json = Json { ignoreUnknownKeys = true }

    fun setToken(t: String?) { token = t }

    private inline fun <reified T> call(path: String, method: String = "GET", body: String? = null): T {
        val requestBuilder = Request.Builder().url("$baseUrl$path")
        token?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
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
}
