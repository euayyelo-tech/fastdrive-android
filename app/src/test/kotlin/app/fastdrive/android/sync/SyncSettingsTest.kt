package app.fastdrive.android.sync

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
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
}
