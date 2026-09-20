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
import org.robolectric.Shadows.shadowOf

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

    // Round 2, Bug 1: the real firing path. WorkManager runs this worker AT or AFTER
    // resumeAtMillis, so by the time doWork() looks, the stored Timer has always already elapsed.
    // The previous round's self-healing getPauseCondition() hid exactly that state from the
    // worker, so its `is Timer` branch never ran and neither trigger mechanism was ever
    // re-applied: SyncSettings reported "not paused" while periodic work stayed cancelled and the
    // instant service stayed stopped until the user reopened the app. The old test above only
    // covered a still-FUTURE timer, which is the one case that never happens in production —
    // hence these two.

    @Test
    fun `an elapsed timer re-enqueues periodic work when the worker fires`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        // Paused for a minute, both mechanisms torn down the way pauseForDuration leaves them...
        syncSettings.setPauseCondition(PauseCondition.Timer(System.currentTimeMillis() + 60_000L))
        PeriodicSyncWorker.applySettings(context, syncSettings)
        assertFalse(isPeriodicWorkActive(workManager))
        // ...and now that minute has passed, which is when WorkManager actually runs this worker.
        syncSettings.setPauseCondition(PauseCondition.Timer(System.currentTimeMillis() - 1_000L))
        assertNull("an elapsed timer must already read as not-paused", syncSettings.getPauseCondition())
        assertFalse(
            "but nothing has re-applied settings yet — this is the state the worker fires in",
            isPeriodicWorkActive(workManager),
        )

        val result = buildWorker(context).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertNull(syncSettings.getPauseCondition())
        assertNull(
            "the worker, not a read, is what clears the stored condition",
            syncSettings.getStoredPauseCondition(),
        )
        assertTrue(
            "periodic work must actually be re-enqueued once the timer elapses",
            isPeriodicWorkActive(workManager),
        )
    }

    @Test
    fun `an elapsed timer restarts the instant service when the worker fires`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.INSTANT)
        syncSettings.setPauseCondition(PauseCondition.Timer(System.currentTimeMillis() + 60_000L))
        InstantSyncService.applySettings(context, syncSettings)
        // Paused, so applySettings only ever called stop() — nothing has been *started* yet, so
        // the assertion below can only see a start issued by the worker itself.
        assertNull(
            "nothing should be started while paused",
            shadowOf(context).peekNextStartedService(),
        )
        syncSettings.setPauseCondition(PauseCondition.Timer(System.currentTimeMillis() - 1_000L))

        val result = buildWorker(context).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            "the instant service must actually be restarted once the timer elapses",
            InstantSyncService::class.java.name,
            shadowOf(context).nextStartedService?.component?.className,
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
