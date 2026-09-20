package app.fastdrive.android.upload

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class ContentResolverFileAccessTest {

    /** A minimal fake document provider so `stat()` can be exercised against a real cursor. */
    class FakeDocumentProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            cursor.addRow(arrayOf<Any>("report.pdf", 4096L))
            return cursor
        }

        override fun getType(uri: Uri): String = "application/pdf"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    @Test
    fun `stat reads display name and size from the content provider's cursor`() {
        val authority = "app.fastdrive.android.test.fakeprovider"
        val provider = Robolectric.buildContentProvider(FakeDocumentProvider::class.java)
            .create(authority)
            .get()
        ShadowContentResolver.registerProviderInternal(authority, provider)

        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        val fileAccess = ContentResolverFileAccess(resolver)
        val uri = Uri.parse("content://$authority/document/1")

        val result = fileAccess.stat(uri)

        assertEquals("report.pdf", result.name)
        assertEquals(4096L, result.size)
        assertEquals("application/pdf", result.contentType)
    }
}
