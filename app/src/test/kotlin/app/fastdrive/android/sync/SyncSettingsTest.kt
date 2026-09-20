package app.fastdrive.android.sync

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.fastdrive.android.api.Cursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Plain SharedPreferences works fine under Robolectric (unlike EncryptedSharedPreferences, which
 * needs a real AndroidKeyStore provider — see TokenStore/FileListViewModelTest), so this runs
 * against a real SyncSettings rather than a fake.
 */
@RunWith(RobolectricTestRunner::class)
class SyncSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val settings = SyncSettings(context)

    @Test
    fun `folder uri defaults to null`() {
        assertNull(settings.getFolderUri())
    }

    @Test
    fun `folder uri round-trips through storage`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ASync")
        settings.setFolderUri(uri)
        assertEquals(uri, settings.getFolderUri())
    }

    @Test
    fun `sync mode defaults to battery friendly`() {
        assertEquals(SyncMode.BATTERY_FRIENDLY, settings.getSyncMode())
    }

    @Test
    fun `sync mode round-trips through storage`() {
        settings.setSyncMode(SyncMode.INSTANT)
        assertEquals(SyncMode.INSTANT, settings.getSyncMode())

        settings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
        assertEquals(SyncMode.BATTERY_FRIENDLY, settings.getSyncMode())
    }

    @Test
    fun `remote cursor defaults to null`() {
        assertNull(settings.getRemoteCursor())
    }

    @Test
    fun `remote cursor round-trips through storage`() {
        val cursor = Cursor(at = "2026-01-01T00:00:00Z", id = "id-1")
        settings.setRemoteCursor(cursor)
        assertEquals(cursor, settings.getRemoteCursor())
    }

    @Test
    fun `remote cursor is independent of folder uri and sync mode`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ASync")
        val cursor = Cursor(at = "2026-01-01T00:00:00Z", id = "id-1")

        settings.setFolderUri(uri)
        settings.setSyncMode(SyncMode.INSTANT)
        settings.setRemoteCursor(cursor)

        assertEquals(uri, settings.getFolderUri())
        assertEquals(SyncMode.INSTANT, settings.getSyncMode())
        assertEquals(cursor, settings.getRemoteCursor())
    }

    @Test
    fun `pause condition defaults to null and not paused`() {
        assertNull(settings.getPauseCondition())
        assertFalse(settings.isPaused())
    }

    @Test
    fun `timer pause condition round-trips through storage`() {
        // A future timestamp — Finding #5 (Phase 4 fix round) makes an already-past resumeAtMillis
        // self-heal to not-paused, so a plain round-trip test needs one that hasn't elapsed yet.
        val resumeAtMillis = System.currentTimeMillis() + 123456789L
        settings.setPauseCondition(PauseCondition.Timer(resumeAtMillis))

        assertEquals(PauseCondition.Timer(resumeAtMillis), settings.getPauseCondition())
        assertTrue(settings.isPaused())
    }

    @Test
    fun `any-wifi pause condition round-trips through storage`() {
        settings.setPauseCondition(PauseCondition.AnyWifi)

        assertEquals(PauseCondition.AnyWifi, settings.getPauseCondition())
        assertTrue(settings.isPaused())
    }

    @Test
    fun `specific-wifi pause condition round-trips through storage`() {
        settings.setPauseCondition(PauseCondition.SpecificWifi(ssid = "HomeWifi"))

        assertEquals(PauseCondition.SpecificWifi("HomeWifi"), settings.getPauseCondition())
        assertTrue(settings.isPaused())
    }

    @Test
    fun `manual pause condition round-trips through storage`() {
        settings.setPauseCondition(PauseCondition.Manual)

        assertEquals(PauseCondition.Manual, settings.getPauseCondition())
        assertTrue(settings.isPaused())
    }

    @Test
    fun `clearing the pause condition un-pauses`() {
        settings.setPauseCondition(PauseCondition.Manual)
        assertTrue(settings.isPaused())

        settings.setPauseCondition(null)

        assertNull(settings.getPauseCondition())
        assertFalse(settings.isPaused())
    }

    @Test
    fun `switching pause condition types does not leak the previous type's fields`() {
        // A future timestamp — see the round-trip test above for why (Finding #5 self-heal).
        val resumeAtMillis = System.currentTimeMillis() + 999_000L
        settings.setPauseCondition(PauseCondition.SpecificWifi(ssid = "HomeWifi"))
        settings.setPauseCondition(PauseCondition.Timer(resumeAtMillis))

        // If the old SSID key survived, getPauseCondition would still be internally consistent
        // (it only reads the fields for the current type tag), but round-tripping back to
        // SpecificWifi should not resurrect the old ssid value either.
        assertEquals(PauseCondition.Timer(resumeAtMillis), settings.getPauseCondition())

        settings.setPauseCondition(PauseCondition.AnyWifi)
        settings.setPauseCondition(PauseCondition.SpecificWifi(ssid = "NewWifi"))
        assertEquals(PauseCondition.SpecificWifi("NewWifi"), settings.getPauseCondition())
    }

    // Finding #5 (Phase 4 fix round): a Timer pause whose resumeAtMillis is already in the past
    // (e.g. the PauseResumeWorker job that should have cleared it was lost) must self-heal instead
    // of being trusted as still "paused" forever.

    @Test
    fun `an expired timer pause self-heals to not-paused`() {
        settings.setPauseCondition(PauseCondition.Timer(resumeAtMillis = System.currentTimeMillis() - 1_000L))

        assertNull(settings.getPauseCondition())
        assertFalse(settings.isPaused())
    }

    @Test
    fun `a timer pause exactly at the resume instant self-heals to not-paused`() {
        settings.setPauseCondition(PauseCondition.Timer(resumeAtMillis = System.currentTimeMillis()))

        assertNull(settings.getPauseCondition())
    }

    @Test
    fun `a still-future timer pause remains paused`() {
        settings.setPauseCondition(PauseCondition.Timer(resumeAtMillis = System.currentTimeMillis() + 60_000L))

        assertTrue(settings.getPauseCondition() is PauseCondition.Timer)
        assertTrue(settings.isPaused())
    }

    // Round 2, Bug 1: that self-heal must be a pure READ. When it also cleared the stored value,
    // PauseResumeWorker — which fires precisely when the timer has elapsed and reads the same
    // storage — found nothing left to act on and never re-applied the trigger mechanisms.

    @Test
    fun `the expired-timer self-heal does not clear what is stored`() {
        val resumeAtMillis = System.currentTimeMillis() - 1_000L
        settings.setPauseCondition(PauseCondition.Timer(resumeAtMillis))

        assertNull("reads as not paused", settings.getPauseCondition())
        assertFalse(settings.isPaused())
        assertEquals(
            "but the stored Timer survives for PauseResumeWorker to act on",
            PauseCondition.Timer(resumeAtMillis),
            settings.getStoredPauseCondition(),
        )
        // Still true after any number of reads — nothing here writes.
        assertNull(settings.getPauseCondition())
        assertEquals(PauseCondition.Timer(resumeAtMillis), settings.getStoredPauseCondition())
    }

    @Test
    fun `getStoredPauseCondition matches getPauseCondition for every non-expired condition`() {
        val resumeAtMillis = System.currentTimeMillis() + 60_000L
        settings.setPauseCondition(PauseCondition.Timer(resumeAtMillis))
        assertEquals(settings.getPauseCondition(), settings.getStoredPauseCondition())

        settings.setPauseCondition(PauseCondition.AnyWifi)
        assertEquals(settings.getPauseCondition(), settings.getStoredPauseCondition())

        settings.setPauseCondition(PauseCondition.SpecificWifi("HomeWifi"))
        assertEquals(settings.getPauseCondition(), settings.getStoredPauseCondition())

        settings.setPauseCondition(PauseCondition.Manual)
        assertEquals(settings.getPauseCondition(), settings.getStoredPauseCondition())

        settings.setPauseCondition(null)
        assertNull(settings.getStoredPauseCondition())
    }

    // Finding #2 (Phase 4 fix round): clearAccountState() (the shared sign-out path) must not
    // leave a pause condition set by the previous account for the next one to inherit, and must
    // cancel any pending PauseResumeWorker timer job too.

    @Test
    fun `clearAccountState clears a pause condition and cancels a pending resume worker`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)
        PauseResumeWorker.pauseForDuration(context, settings, durationMillis = 60_000L)
        assertTrue(settings.isPaused())
        assertTrue(
            workManager.getWorkInfosForUniqueWork(PauseResumeWorker.PAUSE_RESUME_WORK_NAME).get()
                .any { it.state == WorkInfo.State.ENQUEUED },
        )

        settings.clearAccountState()

        assertNull(settings.getPauseCondition())
        assertFalse(settings.isPaused())
        val scheduled = workManager.getWorkInfosForUniqueWork(PauseResumeWorker.PAUSE_RESUME_WORK_NAME).get()
        assertTrue(scheduled.isEmpty() || scheduled.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `clearAccountState clears a manual pause condition too`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        settings.setPauseCondition(PauseCondition.Manual)

        settings.clearAccountState()

        assertNull(settings.getPauseCondition())
    }
}
