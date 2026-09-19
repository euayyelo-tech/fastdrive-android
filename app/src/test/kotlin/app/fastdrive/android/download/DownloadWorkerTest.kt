package app.fastdrive.android.download

import android.app.Notification
import android.app.NotificationManager
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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

    @Test
    fun `vault file posts a distinct message from a generic download failure`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowManager = shadowOf(notificationManager)

        val vaultWorker = buildWorker(
            DownloadUrlResponse(url = "https://example.com/should-not-be-fetched", vault = true),
        )
        vaultWorker.doWork()
        val vaultNotification = shadowManager.allNotifications.last()
        assertEquals(
            "Vault files can't be downloaded yet",
            vaultNotification.extras.getString(Notification.EXTRA_TITLE),
        )

        // A non-vault URL that resolves to nothing routable fails the real HTTP call inside
        // doWork(), exercising the generic ("Download failed") notification path instead.
        val genericFailureWorker = buildWorker(
            DownloadUrlResponse(url = "https://127.0.0.1.invalid/nope", vault = false),
        )
        genericFailureWorker.doWork()
        val genericNotification = shadowManager.allNotifications.last()
        assertEquals(
            "Download failed",
            genericNotification.extras.getString(Notification.EXTRA_TITLE),
        )
    }

    @Test
    fun `sanitizeFileName strips path components so a name can't escape the downloads dir`() {
        assertEquals("evil.txt", sanitizeFileName("../../evil.txt"))
        assertEquals("evil.txt", sanitizeFileName("/etc/evil.txt"))
        assertEquals("evil.txt", sanitizeFileName("..\\..\\evil.txt"))
        assertEquals("plain.txt", sanitizeFileName("plain.txt"))
        assertEquals("", sanitizeFileName("../"))
    }
}
