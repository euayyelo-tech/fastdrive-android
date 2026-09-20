package app.fastdrive.android.sync

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Task 7 ([PeriodicSyncWorker]) and Task 8 ([InstantSyncService]) each derive their own "should I
 * be running?" decision independently from the same [SyncSettings] state, and every real call site
 * ([app.fastdrive.android.MainActivity.onCreate], both mode-select handlers in
 * `SyncSettingsScreen`) calls both `applySettings()` functions back-to-back for the same settings
 * transition. Their own test files each only ever call one of the two `applySettings()` functions
 * in isolation, so nothing actually proves the two mechanisms stay mutually exclusive when driven
 * together the way the real call sites drive them. This test closes that gap by calling both
 * functions together, mirroring the real call order, and asserting exactly one mechanism is active
 * at a time (or neither, when no folder is set).
 */
@RunWith(RobolectricTestRunner::class)
class SyncModeMutualExclusionTest {

    private fun isPeriodicWorkActive(workManager: WorkManager): Boolean {
        val infos = workManager.getWorkInfosForUniqueWork(PeriodicSyncWorker.WORK_NAME).get()
        return infos.isNotEmpty() && infos.any { it.state == WorkInfo.State.ENQUEUED }
    }

    @Test
    fun `battery-friendly mode with a folder set enqueues periodic work and leaves the service stopped`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)

        // Same order as SyncSettingsScreen's battery-friendly handler: instant service torn down
        // first, then the periodic work (re)enqueued.
        InstantSyncService.applySettings(context, syncSettings)
        PeriodicSyncWorker.applySettings(context, syncSettings)

        assertTrue("periodic work should be enqueued", isPeriodicWorkActive(workManager))
        assertNull("instant service should not be (re)started", shadowOf(context).peekNextStartedService())
    }

    @Test
    fun `instant mode with a folder set starts the service and cancels periodic work`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)

        // First get periodic work into the enqueued state, same as a real prior settings choice.
        InstantSyncService.applySettings(context, syncSettings)
        PeriodicSyncWorker.applySettings(context, syncSettings)
        assertTrue(isPeriodicWorkActive(workManager))

        // Now switch to instant mode, in the same order as SyncSettingsScreen's instant handler:
        // periodic work cancelled first, then the service started.
        syncSettings.setSyncMode(SyncMode.INSTANT)
        PeriodicSyncWorker.applySettings(context, syncSettings)
        InstantSyncService.applySettings(context, syncSettings)

        assertTrue(
            "periodic work should now be cancelled/absent",
            !isPeriodicWorkActive(workManager),
        )
        val started = shadowOf(context).peekNextStartedService()
        assertTrue(
            "instant service should be started",
            started?.component?.className == InstantSyncService::class.java.name,
        )
    }

    @Test
    fun `neither mechanism runs when no folder is set, regardless of mode`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)

        // Same order as MainActivity's startup re-assertion.
        PeriodicSyncWorker.applySettings(context, syncSettings)
        InstantSyncService.applySettings(context, syncSettings)

        assertTrue(!isPeriodicWorkActive(workManager))
        assertNull(shadowOf(context).peekNextStartedService())

        syncSettings.setSyncMode(SyncMode.INSTANT)
        PeriodicSyncWorker.applySettings(context, syncSettings)
        InstantSyncService.applySettings(context, syncSettings)

        assertTrue(!isPeriodicWorkActive(workManager))
        assertNull(shadowOf(context).peekNextStartedService())
    }
}
