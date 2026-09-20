package app.fastdrive.android.sync

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeriodicSyncWorkerTest {

    private fun buildWorker(runPass: suspend (Context) -> SyncResult): PeriodicSyncWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestListenableWorkerBuilder<PeriodicSyncWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = PeriodicSyncWorker(appContext, workerParameters, runPass)
                },
            )
            .build()
    }

    @Test
    fun `a clean pass succeeds`() = runBlocking {
        val worker = buildWorker { SyncResult(uploaded = 2, downloaded = 1) }

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun `a pass with recorded per-action failures still succeeds`() = runBlocking {
        // runOnePass records per-action and whole-pass failures inside SyncResult.failed instead
        // of throwing (see SyncOrchestrator's own doc comment) — none of that is fatal to this
        // worker's run; the next 15-minute tick is the retry mechanism for exactly this case.
        val worker = buildWorker {
            SyncResult(uploaded = 1, failed = listOf(SyncFailure(path = "a.txt", message = "boom")))
        }

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun `a whole-pass abort (no folder set) still succeeds rather than retrying forever`() = runBlocking {
        val worker = buildWorker {
            SyncResult(failed = listOf(SyncFailure(path = "", message = "No folder is set to sync.")))
        }

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun `an exception escaping runPass retries`() = runBlocking {
        // Defense-in-depth: runOnePass is documented to never throw, but if some future change let
        // an exception through, a network-shaped failure should get WorkManager's own retry with
        // backoff via UploadRetryPolicy's retryable() rule, same as UploadWorker.
        val worker = buildWorker { throw java.io.IOException("network down") }

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `applySettings enqueues periodic work when battery-friendly with a folder set`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)

        PeriodicSyncWorker.applySettings(context, syncSettings)

        val infos = workManager.getWorkInfosForUniqueWork(PeriodicSyncWorker.WORK_NAME).get()
        assertEquals(1, infos.size)
        assertTrue(infos.single().state == WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `applySettings cancels periodic work when mode switches away from battery-friendly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        PeriodicSyncWorker.applySettings(context, syncSettings)

        syncSettings.setSyncMode(SyncMode.INSTANT)
        PeriodicSyncWorker.applySettings(context, syncSettings)

        val infos = workManager.getWorkInfosForUniqueWork(PeriodicSyncWorker.WORK_NAME).get()
        assertTrue(infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `applySettings does not enqueue when no folder is set`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)

        PeriodicSyncWorker.applySettings(context, syncSettings)

        val infos = workManager.getWorkInfosForUniqueWork(PeriodicSyncWorker.WORK_NAME).get()
        assertTrue(infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED })
    }
}
