package app.fastdrive.android.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.fastdrive.android.api.DownloadUrlProvider
import app.fastdrive.android.api.DownloadUrlResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadWorkerTest {

    private class FakeProvider(private val response: DownloadUrlResponse) : DownloadUrlProvider {
        override suspend fun downloadUrl(fileId: String): DownloadUrlResponse = response
    }

    private fun buildWorker(response: DownloadUrlResponse): DownloadWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(
                workDataOf(
                    "file_id" to "f1",
                    "file_name" to "secret.txt",
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
                        DownloadWorker(
                            appContext,
                            workerParameters,
                            tokenProvider = { null },
                            apiFactory = { _, _ -> FakeProvider(response) },
                        )
                },
            )
            .build()
    }

    @Test
    fun `vault file fails without attempting a download`() = runBlocking {
        val worker = buildWorker(
            DownloadUrlResponse(url = "https://example.com/should-not-be-fetched", vault = true),
        )

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
    }
}
