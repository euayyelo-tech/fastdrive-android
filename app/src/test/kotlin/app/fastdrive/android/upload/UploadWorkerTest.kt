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
}
