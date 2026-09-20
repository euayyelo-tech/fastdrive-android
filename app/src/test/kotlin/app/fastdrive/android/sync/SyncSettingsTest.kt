package app.fastdrive.android.sync

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.fastdrive.android.api.Cursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val settings = SyncSettings(ApplicationProvider.getApplicationContext())

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
}
