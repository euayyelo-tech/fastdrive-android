package app.fastdrive.android.sync

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowService

@RunWith(RobolectricTestRunner::class)
class InstantSyncServiceTest {

    @Test
    fun `onCreate starts itself in the foreground with a persistent notification`() {
        val controller = Robolectric.buildService(InstantSyncService::class.java)
        val service = controller.get()
        // Never let the real SyncOrchestrator run against a real Context during this test.
        service.runPass = { SyncResult() }

        controller.create()

        val shadowService = shadowOf(service) as ShadowService
        assertTrue(shadowService.lastForegroundNotification != null)

        controller.destroy()
    }

    @Test
    fun `applySettings starts the service when instant mode is chosen with a folder set`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.INSTANT)

        InstantSyncService.applySettings(context, syncSettings)

        val started = shadowOf(context).peekNextStartedService()
        assertEquals(InstantSyncService::class.java.name, started?.component?.className)
    }

    @Test
    fun `applySettings stops the service when battery-friendly mode is chosen`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = SyncSettings(context)
        syncSettings.setFolderUri(Uri.parse("content://fake/tree/1"))
        syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)

        InstantSyncService.applySettings(context, syncSettings)

        // Nothing new was started for instant mode...
        assertNull(shadowOf(context).peekNextStartedService())
        // ...and a stop was issued for this exact service.
        val stopped = shadowOf(context).nextStoppedService
        assertEquals(InstantSyncService::class.java.name, stopped?.component?.className)
    }

    @Test
    fun `applySettings stops the service when no folder is set even in instant mode`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val syncSettings = SyncSettings(context)
        syncSettings.setSyncMode(SyncMode.INSTANT)

        InstantSyncService.applySettings(context, syncSettings)

        assertNull(shadowOf(context).peekNextStartedService())
        val stopped = shadowOf(context).nextStoppedService
        assertEquals(InstantSyncService::class.java.name, stopped?.component?.className)
    }
}
