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
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    private fun buildWorker(fileAccess: FileAccess, client: OkHttpClient): UploadWorker {
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
                            tokenProvider = { null },
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
    fun `a part that fails every attempt aborts the upload and fails`() = runBlocking {
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
                        jsonResponse(request, 500, """{"error":"permanent"}""")
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
        assertEquals(4, part2Puts) // MAX_TRIES
        assertTrue(requestsSeen.none { it == "POST https://example.com/api/files/f1/complete" })
        assertTrue(requestsSeen.any { it == "POST https://example.com/api/files/f1/abort" })
    }
}
