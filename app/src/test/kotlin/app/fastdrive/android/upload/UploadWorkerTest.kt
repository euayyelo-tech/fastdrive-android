package app.fastdrive.android.upload

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.auth.TokenAccess
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class UploadWorkerTest {

    private class FakeFileAccess(private val picked: PickedFile, private val bytes: ByteArray) : FileAccess {
        override fun stat(uri: Uri): PickedFile = picked
        override fun openStream(uri: Uri): InputStream = ByteArrayInputStream(bytes)
    }

    /** A plain in-memory fake, so tests don't need a real TokenStore — Robolectric's JVM has no
     *  AndroidKeyStore provider, so constructing EncryptedSharedPreferences there throws. Mirrors
     *  FileListViewModelTest's FakeTokenAccess. */
    private class FakeTokenAccess(initial: String? = null) : TokenAccess {
        private var token: String? = initial
        override fun getToken(): String? = token
        override fun setToken(token: String?) { this.token = token }
        override fun clear() { token = null }
    }

    /** Reads an OkHttp request body into a string so a test can assert on the actual serialized
     *  JSON sent over the wire, not just the request's method+URL — the gap that let the
     *  multipart-as-a-JSON-string bug ship undetected in the first place. */
    private fun bodyString(request: okhttp3.Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    /**
     * Records every request the worker sends and answers each leg of the single-file upload
     * protocol (upload-url -> PUT -> confirm) without touching a real network — the same
     * interceptor-based faking `DriveApiTest` already uses for `DriveApi`, extended here to also
     * cover the raw PUT that `UploadWorker` issues directly (not through `DriveApi`).
     */
    private fun fakeClient(requestsSeen: MutableList<String>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestsSeen.add("${request.method} ${request.url}")
                val (code, body) = when {
                    request.url.encodedPath == "/api/files/upload-url" ->
                        200 to """{"id":"f1","key":"owners/o1/f1","url":"https://s3.example.com/put","headers":{},"vault":false}"""
                    request.url.toString() == "https://s3.example.com/put" -> 200 to "{}"
                    request.url.encodedPath == "/api/files/f1/confirm" -> 200 to "{}"
                    else -> 500 to """{"error":"unexpected request"}"""
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "error")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    private fun buildWorker(
        fileAccess: FileAccess,
        client: OkHttpClient,
        tokenStore: TokenAccess = FakeTokenAccess(),
    ): UploadWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestListenableWorkerBuilder<UploadWorker>(context)
            .setInputData(
                workDataOf(
                    "uri" to "content://fake/1",
                    "folder" to "/",
                    "base_url" to "https://example.com",
                ),
            )
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        UploadWorker(
                            appContext,
                            workerParameters,
                            fileAccess = fileAccess,
                            tokenStore = tokenStore,
                            tokenProvider = { tokenStore.getToken() },
                            httpClient = client,
                            apiFactory = { baseUrl, token, httpClient ->
                                DriveApi(baseUrl, tokenProvider = { token }, client = httpClient)
                            },
                        )
                },
            )
            .build()
    }

    @Test
    fun `single-file upload calls upload-url with multipart false, PUTs the bytes, then confirms`() = runBlocking {
        val picked = PickedFile(Uri.parse("content://fake/1"), "small.txt", 5L, "text/plain")
        val fileAccess = FakeFileAccess(picked, "hello".toByteArray())
        val requestsSeen = mutableListOf<String>()
        val worker = buildWorker(fileAccess, fakeClient(requestsSeen))

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            listOf(
                "POST https://example.com/api/files/upload-url",
                "PUT https://s3.example.com/put",
                "POST https://example.com/api/files/f1/confirm",
            ),
            requestsSeen,
        )
    }

    @Test
    fun `unknown file size fails without calling the network`() = runBlocking {
        val picked = PickedFile(Uri.parse("content://fake/1"), "small.txt", -1L, "text/plain")
        val fileAccess = FakeFileAccess(picked, ByteArray(0))
        val requestsSeen = mutableListOf<String>()
        val worker = buildWorker(fileAccess, fakeClient(requestsSeen))

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(requestsSeen.isEmpty())
    }

    @Test
    fun `a 401 from upload-url clears the token store and fails with the auth_error flag set`() = runBlocking {
        val tokenStore = FakeTokenAccess(initial = "some-token")
        val picked = PickedFile(Uri.parse("content://fake/1"), "small.txt", 5L, "text/plain")
        val fileAccess = FakeFileAccess(picked, "hello".toByteArray())
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                jsonResponse(chain.request(), 401, """{"error":"unauthorized"}""")
            }
            .build()
        val worker = buildWorker(fileAccess, client, tokenStore)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData
        assertEquals(true, output.getBoolean("auth_error", false))
        assertTrue(tokenStore.getToken() == null)
    }

    // ── Request-body assertions (Task 7) ────────────────────────────────────
    //
    // Nothing in this suite previously asserted what JSON was actually sent over the wire — only
    // response parsing and request method+URL strings — which is exactly the gap that let
    // `multipart: true` ship as the JSON string "true" instead of the boolean `true` (Critical
    // finding #1). These lock the real serialized body in place.

    @Test
    fun `upload-url request body encodes multipart as a real JSON boolean, not a string`() = runBlocking {
        var lastUploadUrlBody: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.encodedPath == "/api/files/upload-url") {
                    lastUploadUrlBody = bodyString(request)
                }
                // 500 short-circuits the worker right after the request we care about is sent —
                // this test only needs the request body, not a full successful upload.
                jsonResponse(request, 500, """{"error":"stop here, only the request body matters"}""")
            }
            .build()

        val bigPicked = PickedFile(
            Uri.parse("content://fake/1"), "big.bin", UploadWorker.MULTIPART_THRESHOLD, "application/octet-stream",
        )
        buildWorker(FakeFileAccess(bigPicked, ByteArray(0)), client).doWork()
        assertTrue(
            "expected \"multipart\":true in $lastUploadUrlBody",
            lastUploadUrlBody!!.contains("\"multipart\":true"),
        )

        val smallPicked = PickedFile(Uri.parse("content://fake/1"), "small.txt", 5L, "text/plain")
        buildWorker(FakeFileAccess(smallPicked, "hello".toByteArray()), client).doWork()
        assertTrue(
            "expected \"multipart\":false in $lastUploadUrlBody",
            lastUploadUrlBody!!.contains("\"multipart\":false"),
        )
    }

    @Test
    fun `complete request body lists parts ordered by PartNumber with the correct ETags`() = runBlocking {
        var completeBody: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                when {
                    request.url.encodedPath == "/api/files/upload-url" -> jsonResponse(
                        request, 200,
                        """{"id":"f1","key":"k","multipart":true,"uploadId":"up1","partSize":8,"parts":3,"vault":false}""",
                    )
                    request.url.encodedPath == "/api/files/f1/parts" -> jsonResponse(
                        request, 200,
                        """{"urls":{"1":"https://s3.example.com/part1","2":"https://s3.example.com/part2","3":"https://s3.example.com/part3"}}""",
                    )
                    request.url.toString() == "https://s3.example.com/part1" ->
                        jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag1\""))
                    request.url.toString() == "https://s3.example.com/part2" ->
                        jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag2\""))
                    request.url.toString() == "https://s3.example.com/part3" ->
                        jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag3\""))
                    request.url.encodedPath == "/api/files/f1/complete" -> {
                        completeBody = bodyString(request)
                        jsonResponse(request, 200, "{}")
                    }
                    request.url.encodedPath == "/api/files/f1/confirm" -> jsonResponse(request, 200, "{}")
                    else -> jsonResponse(request, 500, """{"error":"unexpected request"}""")
                }
            }
            .build()
        val worker = buildWorker(FakeFileAccess(multipartPicked, multipartBytes), client)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            """{"uploadId":"up1","parts":[{"PartNumber":1,"ETag":"etag1"},{"PartNumber":2,"ETag":"etag2"},{"PartNumber":3,"ETag":"etag3"}]}""",
            completeBody,
        )
    }

    // ── Multipart path (Task 4) ─────────────────────────────────────────────
    //
    // These fake a 20-byte file split into 3 parts of 8/8/4 bytes (a server-chosen
    // partSize of 8, not the real PART_SIZE=16MiB) so the tests stay fast while still
    // exercising the real batching/retry/abort logic byte-for-byte. `doWork()` only
    // ever branches on the server's `start.multipart` flag, not the file's actual
    // size, so this is a faithful exercise of the multipart machinery without
    // allocating a real 32MiB+ buffer per test.

    private val multipartPicked = PickedFile(Uri.parse("content://fake/1"), "big.bin", 20L, "application/octet-stream")
    private val multipartBytes = ByteArray(20) { it.toByte() }

    private fun jsonResponse(
        request: okhttp3.Request, code: Int, body: String, headers: Map<String, String> = emptyMap(),
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "error")
            .body(body.toResponseBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return builder.build()
    }

    @Test
    fun `a file just over the threshold splits into the expected number of parts and completes`() = runBlocking {
        val requestsSeen = mutableListOf<String>()
        val fileAccess = FakeFileAccess(multipartPicked, multipartBytes)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                synchronized(requestsSeen) { requestsSeen.add("${request.method} ${request.url}") }
                val response = when {
                    request.url.encodedPath == "/api/files/upload-url" -> jsonResponse(
                        request,
                        200,
                        """{"id":"f1","key":"k","multipart":true,"uploadId":"up1","partSize":8,"parts":3,"vault":false}""",
                    )
                    request.url.encodedPath == "/api/files/f1/parts" -> jsonResponse(
                        request,
                        200,
                        """{"urls":{"1":"https://s3.example.com/part1","2":"https://s3.example.com/part2","3":"https://s3.example.com/part3"}}""",
                    )
                    request.url.toString() == "https://s3.example.com/part1" ->
                        jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag1\""))
                    request.url.toString() == "https://s3.example.com/part2" ->
                        jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag2\""))
                    request.url.toString() == "https://s3.example.com/part3" ->
                        jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag3\""))
                    request.url.encodedPath == "/api/files/f1/complete" -> jsonResponse(request, 200, "{}")
                    request.url.encodedPath == "/api/files/f1/confirm" -> jsonResponse(request, 200, "{}")
                    else -> jsonResponse(request, 500, """{"error":"unexpected request"}""")
                }
                response
            }
            .build()
        val worker = buildWorker(fileAccess, client)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("POST https://example.com/api/files/upload-url", requestsSeen[0])
        assertEquals("POST https://example.com/api/files/f1/parts", requestsSeen[1])
        assertEquals(
            setOf(
                "PUT https://s3.example.com/part1",
                "PUT https://s3.example.com/part2",
                "PUT https://s3.example.com/part3",
            ),
            requestsSeen.subList(2, 5).toSet(),
        )
        assertEquals("POST https://example.com/api/files/f1/complete", requestsSeen[5])
        assertEquals("POST https://example.com/api/files/f1/confirm", requestsSeen[6])
        assertEquals(7, requestsSeen.size)
    }

    @Test
    fun `a part that fails once then succeeds on retry still completes the upload`() = runBlocking {
        val requestsSeen = mutableListOf<String>()
        val fileAccess = FakeFileAccess(multipartPicked, multipartBytes)
        val part2Attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                synchronized(requestsSeen) { requestsSeen.add("${request.method} ${request.url}") }
                val response = when {
                    request.url.encodedPath == "/api/files/upload-url" -> jsonResponse(
                        request,
                        200,
                        """{"id":"f1","key":"k","multipart":true,"uploadId":"up1","partSize":8,"parts":3,"vault":false}""",
                    )
                    request.url.encodedPath == "/api/files/f1/parts" -> jsonResponse(
                        request,
                        200,
                        """{"urls":{"1":"https://s3.example.com/part1","2":"https://s3.example.com/part2","3":"https://s3.example.com/part3"}}""",
                    )
                    request.url.toString() == "https://s3.example.com/part1" ->
                        jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag1\""))
                    request.url.toString() == "https://s3.example.com/part2" ->
                        if (part2Attempts.getAndIncrement() == 0) {
                            jsonResponse(request, 500, """{"error":"transient"}""")
                        } else {
                            jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag2-retry\""))
                        }
                    request.url.toString() == "https://s3.example.com/part3" ->
                        jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag3\""))
                    request.url.encodedPath == "/api/files/f1/complete" -> jsonResponse(request, 200, "{}")
                    request.url.encodedPath == "/api/files/f1/confirm" -> jsonResponse(request, 200, "{}")
                    else -> jsonResponse(request, 500, """{"error":"unexpected request"}""")
                }
                response
            }
            .build()
        val worker = buildWorker(fileAccess, client)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(2, part2Attempts.get())
        assertTrue(requestsSeen.any { it == "POST https://example.com/api/files/f1/complete" })
        assertTrue(requestsSeen.none { it == "POST https://example.com/api/files/f1/abort" })
    }

    @Test
    fun `a part that fails every attempt with a retryable status aborts and asks WorkManager to retry`() = runBlocking {
        val requestsSeen = mutableListOf<String>()
        val fileAccess = FakeFileAccess(multipartPicked, multipartBytes)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                synchronized(requestsSeen) { requestsSeen.add("${request.method} ${request.url}") }
                val response = when {
                    request.url.encodedPath == "/api/files/upload-url" -> jsonResponse(
                        request,
                        200,
                        """{"id":"f1","key":"k","multipart":true,"uploadId":"up1","partSize":8,"parts":3,"vault":false}""",
                    )
                    request.url.encodedPath == "/api/files/f1/parts" -> jsonResponse(
                        request,
                        200,
                        """{"urls":{"1":"https://s3.example.com/part1","2":"https://s3.example.com/part2","3":"https://s3.example.com/part3"}}""",
                    )
                    request.url.toString() == "https://s3.example.com/part1" ->
                        jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag1\""))
                    // 500 is retryable, so the part loop burns all MAX_TRIES attempts on it — but
                    // since the underlying failure is transient, the worker as a whole should ask
                    // WorkManager to retry the job later (Result.retry()), not give up for good.
                    request.url.toString() == "https://s3.example.com/part2" ->
                        jsonResponse(request, 500, """{"error":"transient but persistent in this test"}""")
                    request.url.toString() == "https://s3.example.com/part3" ->
                        jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag3\""))
                    request.url.encodedPath == "/api/files/f1/complete" -> jsonResponse(request, 200, "{}")
                    request.url.encodedPath == "/api/files/f1/abort" -> jsonResponse(request, 200, "{}")
                    request.url.encodedPath == "/api/files/f1/confirm" -> jsonResponse(request, 200, "{}")
                    else -> jsonResponse(request, 500, """{"error":"unexpected request"}""")
                }
                response
            }
            .build()
        val worker = buildWorker(fileAccess, client)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        val part2Puts = requestsSeen.count { it == "PUT https://s3.example.com/part2" }
        assertEquals(4, part2Puts) // MAX_TRIES
        assertTrue(requestsSeen.none { it == "POST https://example.com/api/files/f1/complete" })
        assertTrue(requestsSeen.any { it == "POST https://example.com/api/files/f1/abort" })
    }

    @Test
    fun `a part that fails with a permanent status fails fast without exhausting retries`() = runBlocking {
        val requestsSeen = mutableListOf<String>()
        val fileAccess = FakeFileAccess(multipartPicked, multipartBytes)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                synchronized(requestsSeen) { requestsSeen.add("${request.method} ${request.url}") }
                val response = when {
                    request.url.encodedPath == "/api/files/upload-url" -> jsonResponse(
                        request,
                        200,
                        """{"id":"f1","key":"k","multipart":true,"uploadId":"up1","partSize":8,"parts":3,"vault":false}""",
                    )
                    request.url.encodedPath == "/api/files/f1/parts" -> jsonResponse(
                        request,
                        200,
                        """{"urls":{"1":"https://s3.example.com/part1","2":"https://s3.example.com/part2","3":"https://s3.example.com/part3"}}""",
                    )
                    request.url.toString() == "https://s3.example.com/part1" ->
                        jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag1\""))
                    // 403 is NOT retryable per upload-rules.ts's retryable() — a permanent
                    // rejection should fail after a single attempt, not burn through MAX_TRIES.
                    request.url.toString() == "https://s3.example.com/part2" ->
                        jsonResponse(request, 403, """{"error":"forbidden"}""")
                    request.url.toString() == "https://s3.example.com/part3" ->
                        jsonResponse(request, 200, "{}", mapOf("ETag" to "\"etag3\""))
                    request.url.encodedPath == "/api/files/f1/complete" -> jsonResponse(request, 200, "{}")
                    request.url.encodedPath == "/api/files/f1/abort" -> jsonResponse(request, 200, "{}")
                    request.url.encodedPath == "/api/files/f1/confirm" -> jsonResponse(request, 200, "{}")
                    else -> jsonResponse(request, 500, """{"error":"unexpected request"}""")
                }
                response
            }
            .build()
        val worker = buildWorker(fileAccess, client)

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        val part2Puts = requestsSeen.count { it == "PUT https://s3.example.com/part2" }
        assertEquals(1, part2Puts) // fails fast: not retryable, so exactly one attempt
        assertTrue(requestsSeen.none { it == "POST https://example.com/api/files/f1/complete" })
        assertTrue(requestsSeen.any { it == "POST https://example.com/api/files/f1/abort" })
    }
}
