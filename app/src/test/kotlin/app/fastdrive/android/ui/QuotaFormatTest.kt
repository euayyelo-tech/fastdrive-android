package app.fastdrive.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QuotaFormatTest {
    @Test
    fun `normal case renders GB for both sides`() {
        // 2.5 GB used, 15 GB quota (binary GB, matching desktop's fmt()).
        val used = (2.5 * 1_073_741_824.0).toLong()
        val quota = (15.0 * 1_073_741_824.0).toLong()
        assertEquals("2.50 GB of 15.00 GB used", formatQuota(used, quota))
    }

    @Test
    fun `zero used renders 0 B`() {
        val quota = (15.0 * 1_073_741_824.0).toLong()
        assertEquals("0 B of 15.00 GB used", formatQuota(0L, quota))
    }

    @Test
    fun `used equals quota renders the same value twice`() {
        val both = (15.0 * 1_073_741_824.0).toLong()
        assertEquals("15.00 GB of 15.00 GB used", formatQuota(both, both))
    }

    @Test
    fun `values below 1024 bytes render as B`() {
        assertEquals("512 B of 1023 B used", formatQuota(512L, 1023L))
    }

    @Test
    fun `values in the KB range render as KB with no decimals`() {
        // 2 KB used of 500 KB quota.
        assertEquals("2 KB of 500 KB used", formatQuota(2_048L, 512_000L))
    }

    @Test
    fun `values in the MB range render as MB with one decimal`() {
        // 10 MB used of 100 MB quota.
        val used = 10L * 1_048_576L
        val quota = 100L * 1_048_576L
        assertEquals("10.0 MB of 100.0 MB used", formatQuota(used, quota))
    }

    @Test
    fun `a clean round number has no stray decimals beyond the fixed precision`() {
        val used = 1L * 1_073_741_824L
        val quota = 4L * 1_073_741_824L
        assertEquals("1.00 GB of 4.00 GB used", formatQuota(used, quota))
    }
}
