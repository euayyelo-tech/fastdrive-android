package app.fastdrive.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [shouldNotify]/[notificationText] as plain JVM tests — both are pure functions over
 * [SyncResult] with no Android framework types involved, so no Robolectric is needed here (unlike
 * [SyncOrchestratorTest], which does need it for Room/`DocumentFile`).
 */
class SyncNotifierTest {

    @Test
    fun `all-zero result with no failures does not notify`() {
        val result = SyncResult()
        assertFalse(shouldNotify(result))
    }

    @Test
    fun `uploads only notifies with upload count`() {
        val result = SyncResult(uploaded = 3)
        assertTrue(shouldNotify(result))
        assertEquals("3 uploaded", notificationText(result))
    }

    @Test
    fun `failures only notifies with failure count`() {
        val result = SyncResult(failed = listOf(SyncFailure(path = "a.txt", message = "boom")))
        assertTrue(shouldNotify(result))
        assertEquals("1 couldn't sync", notificationText(result))
    }

    @Test
    fun `activity and failures both notifies with both parts`() {
        val result = SyncResult(
            uploaded = 2,
            downloaded = 1,
            failed = listOf(SyncFailure(path = "a.txt", message = "boom")),
        )
        assertTrue(shouldNotify(result))
        assertEquals("2 uploaded, 1 downloaded — 1 couldn't sync", notificationText(result))
    }

    @Test
    fun `deletes moves and conflicts render correctly`() {
        val result = SyncResult(deletedLocal = 1, deletedRemote = 2, moved = 4, conflicts = 1)
        assertTrue(shouldNotify(result))
        assertEquals("3 deleted, 4 moved, 1 conflict", notificationText(result))
    }
}
