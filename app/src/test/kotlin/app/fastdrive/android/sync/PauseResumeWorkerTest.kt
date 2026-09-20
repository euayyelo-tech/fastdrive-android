package app.fastdrive.android.sync

import android.app.Application
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PauseResumeWorkerTest {

    private fun buildWorker(context: Context): PauseResumeWorker {
        return TestListenableWorkerBuilder<PauseResumeWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = PauseResumeWorker(appContext, workerParameters)
                },
            )
            .build()
    }

    private fun isPeriodicWorkActive(workManager: WorkManager): Boolean {
        val infos = workManager.getWorkInfosForUniqueWork(PeriodicSyncWorker.WORK_NAME).get()
        return infos.isNotEmpty() && infos.any { it.state == WorkInfo.State.ENQUEUED }
    }

    @Test
    fun `clears a still-active Timer condition and re-applies settings`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        syncSettings.setPauseCondition(PauseCondition.Timer(System.currentTimeMillis() + 1000L))
        // Gated off while paused, same as PauseGatingTest proves.
        PeriodicSyncWorker.applySettings(context, syncSettings)
        assertFalse(isPeriodicWorkActive(workManager))

        val result = buildWorker(context).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertNull(syncSettings.getPauseCondition())
        assertTrue(
            "periodic work should be re-enqueued once the timer clears the pause",
            isPeriodicWorkActive(workManager),
        )
    }

    @Test
    fun `does nothing if the pause condition changed away from Timer before firing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        // The original Timer condition this worker was scheduled for got superseded by a
        // different pause condition (e.g. the user picked "pause until on Wi-Fi") before this
        // job's cancellation won its race against it actually firing.
        syncSettings.setPauseCondition(PauseCondition.AnyWifi)

        val result = buildWorker(context).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            "a non-Timer condition must survive an in-flight timer worker's firing",
            PauseCondition.AnyWifi,
            syncSettings.getPauseCondition(),
        )
    }

    @Test
    fun `does nothing if already resumed before firing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        // Never paused (or already resumed) by the time this fires.

        val result = buildWorker(context).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertNull(syncSettings.getPauseCondition())
    }

    @Test
    fun `pauseForDuration stops both mechanisms immediately and schedules a resume worker`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        PeriodicSyncWorker.applySettings(context, syncSettings)
        assertTrue(isPeriodicWorkActive(workManager))

        PauseResumeWorker.pauseForDuration(context, syncSettings, durationMillis = 60_000L)

        assertTrue(syncSettings.isPaused())
        assertFalse(isPeriodicWorkActive(workManager))
        val scheduled = workManager.getWorkInfosForUniqueWork(PauseResumeWorker.PAUSE_RESUME_WORK_NAME).get()
        assertEquals(1, scheduled.size)
        assertTrue(scheduled.single().state == WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `resumeNow cancels the pending timer, clears state, and re-applies settings`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        PauseResumeWorker.pauseForDuration(context, syncSettings, durationMillis = 60_000L)
        assertTrue(syncSettings.isPaused())

        var teardownCalled = false
        PauseResumeWorker.resumeNow(context, syncSettings) { teardownCalled = true }

        assertTrue(teardownCalled)
        assertNull(syncSettings.getPauseCondition())
        assertTrue(isPeriodicWorkActive(workManager))
        val scheduled = workManager.getWorkInfosForUniqueWork(PauseResumeWorker.PAUSE_RESUME_WORK_NAME).get()
        assertTrue(scheduled.isEmpty() || scheduled.all { it.state == WorkInfo.State.CANCELLED })
    }
}
