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
 * Phase 4 Task 3: a paused [SyncSettings] must gate both Phase 3 trigger mechanisms
 * ([PeriodicSyncWorker.applySettings], [InstantSyncService.applySettings]) to a stopped state,
 * exactly like having no folder set, regardless of which [SyncMode] is selected. Mirrors
 * [SyncModeMutualExclusionTest]'s style of driving both `applySettings()` calls together the way
 * the real call sites do.
 */
@RunWith(RobolectricTestRunner::class)
class PauseGatingTest {

    private fun isPeriodicWorkActive(workManager: WorkManager): Boolean {
        val infos = workManager.getWorkInfosForUniqueWork(PeriodicSyncWorker.WORK_NAME).get()
        return infos.isNotEmpty() && infos.any { it.state == WorkInfo.State.ENQUEUED }
    }

    @Test
    fun `pausing stops periodic work even though battery-friendly mode with a folder is selected`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        PeriodicSyncWorker.applySettings(context, syncSettings)
        assertTrue(isPeriodicWorkActive(workManager))

        syncSettings.setPauseCondition(PauseCondition.Manual)
        PeriodicSyncWorker.applySettings(context, syncSettings)

        assertTrue(
            "periodic work should be cancelled once paused",
            !isPeriodicWorkActive(workManager),
        )
    }

    @Test
    fun `pausing stops the instant service even though instant mode with a folder is selected`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.INSTANT)
        InstantSyncService.applySettings(context, syncSettings)
        assertTrue(
            "instant service should be started before pausing",
            shadowOf(context).peekNextStartedService()?.component?.className ==
                InstantSyncService::class.java.name,
        )

        syncSettings.setPauseCondition(PauseCondition.Timer(System.currentTimeMillis() + 1000L))
        InstantSyncService.applySettings(context, syncSettings)

        assertNull(
            "no further start should be issued once paused",
            shadowOf(context).peekNextStartedService(),
        )
        assertTrue(shadowOf(context).nextStoppedService != null)
    }

    @Test
    fun `pausing with both mechanisms driven together leaves neither active`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        syncSettings.setPauseCondition(PauseCondition.AnyWifi)

        InstantSyncService.applySettings(context, syncSettings)
        PeriodicSyncWorker.applySettings(context, syncSettings)

        assertTrue(!isPeriodicWorkActive(workManager))
        assertNull(shadowOf(context).peekNextStartedService())
    }
}
