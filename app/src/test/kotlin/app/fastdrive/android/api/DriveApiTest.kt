package app.fastdrive.android.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses a ChangesPage response`() {
        val sample = """
            {"files":[{"id":"f1","folder":"/","name":"a.txt","size":10,"contentType":"text/plain","sha256":null,"mtime":null,"version":1,"changedAt":"2026-09-19T00:00:00Z","deletedAt":null}],"gone":[],"cursor":{"at":"2026-09-19T00:00:00Z","id":"f1"},"more":false,"now":"2026-09-19T00:00:01Z"}
        """.trimIndent()
        val page = json.decodeFromString<ChangesPage>(sample)
        assertEquals(1, page.files.size)
        assertEquals("a.txt", page.files[0].name)
        assertEquals(false, page.more)
    }

    @Test
    fun `parses a DeviceCodeResponse`() {
        val sample = """
            {"id":"d1","code":"ABCD-1234","secret":"s3cr3t","expiresAt":"2026-09-19T00:10:00Z","link":"https://fastdrive.app/link/d1"}
        """.trimIndent()
        val res = json.decodeFromString<DeviceCodeResponse>(sample)
        assertEquals("ABCD-1234", res.code)
        assertEquals("https://fastdrive.app/link/d1", res.link)
    }

    @Test
    fun `parses a DevicePollResponse when approved`() {
        val sample = """{"status":"approved","token":"tok_abc","owner":"me@example.com"}"""
        val res = json.decodeFromString<DevicePollResponse>(sample)
        assertEquals("approved", res.status)
        assertEquals("tok_abc", res.token)
    }

    @Test
    fun `parses a WhoamiResponse`() {
        val sample = """
            {"you":{"actor":"me@example.com","drive":"drv_1","role":"owner"},"plan":{"id":"pro","name":"Pro","maxFileBytes":5368709120},"used":1024,"quota":10737418240}
        """.trimIndent()
        val res = json.decodeFromString<WhoamiResponse>(sample)
        assertEquals("owner", res.you.role)
        assertEquals("Pro", res.plan.name)
        assertEquals(1024L, res.used)
    }

    @Test
    fun `parses an UploadUrlResponse for a single-PUT (non-multipart) upload`() {
        val sample = """
            {"id":"f1","key":"owners/o1/f1","url":"https://s3.example.com/put","headers":{"x-amz-server-side-encryption":"AES256"},"vault":false}
        """.trimIndent()
        val res = json.decodeFromString<UploadUrlResponse>(sample)
        assertEquals("f1", res.id)
        assertEquals("owners/o1/f1", res.key)
        assertEquals("https://s3.example.com/put", res.url)
        assertEquals(false, res.multipart)
        assertNull(res.uploadId)
        assertNull(res.partSize)
        assertNull(res.parts)
        assertEquals(false, res.vault)
    }

    @Test
    fun `parses an UploadUrlResponse for a multipart upload`() {
        val sample = """
            {"id":"f1","key":"owners/o1/f1","multipart":true,"uploadId":"up_1","partSize":8388608,"parts":4,"vault":true}
        """.trimIndent()
        val res = json.decodeFromString<UploadUrlResponse>(sample)
        assertEquals(true, res.multipart)
        assertEquals("up_1", res.uploadId)
        assertEquals(8388608L, res.partSize)
        assertEquals(4, res.parts)
        assertEquals(true, res.vault)
        assertNull(res.url)
    }

    @Test
    fun `parses a PartsResponse`() {
        val sample = """{"urls":{"1":"https://s3.example.com/part1","2":"https://s3.example.com/part2"}}"""
        val res = json.decodeFromString<PartsResponse>(sample)
        assertEquals("https://s3.example.com/part1", res.urls["1"])
        assertEquals(2, res.urls.size)
    }

    @Test
    fun `uploadAbort swallows a failing HTTP response instead of throwing`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("""{"error":"boom"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val api = DriveApi(baseUrl = "https://example.com", client = client)

        // Must not throw, even though the underlying call fails with a 500.
        api.uploadAbort("f1", "up_1")
    }

    /**
     * Confirms the tokenProvider-based constructor still deserializes real responses correctly,
     * and — the actual point of the refactor — reads the token fresh on every call rather than
     * caching whatever was current when DriveApi was built. An interceptor stands in for the
     * network: it never calls `chain.proceed`, so no real request goes out, and it records the
     * Authorization header it was sent so the test can assert on it.
     */
    @Test
    fun `tokenProvider is read fresh on every call, not cached at construction`() = runBlocking {
        var currentToken: String? = "first-token"
        val seenAuthHeaders = mutableListOf<String?>()
        val body = """
            {"files":[],"gone":[],"cursor":null,"more":false,"now":"2026-09-19T00:00:00Z"}
        """.trimIndent()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                seenAuthHeaders.add(chain.request().header("Authorization"))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val api = DriveApi(baseUrl = "https://example.com", tokenProvider = { currentToken }, client = client)

        val firstPage = api.changes(null)
        currentToken = "second-token"
        val secondPage = api.changes(null)

        assertEquals(false, firstPage.more)
        assertEquals(0, secondPage.files.size)
        assertEquals(listOf("Bearer first-token", "Bearer second-token"), seenAuthHeaders)

        currentToken = null
        api.changes(null)
        assertNull(seenAuthHeaders.last())
    }
}
