package app.fastdrive.android.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses a ChangesPage response`() {
        val sample = """
            {"files":[{"id":"f1","folder":"/","name":"a.txt","size":10,"contentType":"text/plain","sha256":null,"mtime":null,"version":1,"changedAt":"2026-09-19T00:00:00Z","deletedAt":null}],"gone":[],"cursor":{"at":"2026-09-19T00:00:00Z","id":"f1"},"more":false,"now":"2026-09-19T00:00:01Z"}
        """.trimIndent()
        val page = json.decodeFromString<ChangesPage>(sample)
        assertEquals(1, page.files.size)
        assertEquals("a.txt", page.files[0].name)
        assertEquals(false, page.more)
    }

    @Test
    fun `parses a DeviceCodeResponse`() {
        val sample = """
            {"id":"d1","code":"ABCD-1234","secret":"s3cr3t","expiresAt":"2026-09-19T00:10:00Z","link":"https://fastdrive.app/link/d1"}
        """.trimIndent()
        val res = json.decodeFromString<DeviceCodeResponse>(sample)
        assertEquals("ABCD-1234", res.code)
        assertEquals("https://fastdrive.app/link/d1", res.link)
    }

    @Test
    fun `parses a DevicePollResponse when approved`() {
        val sample = """{"status":"approved","token":"tok_abc","owner":"me@example.com"}"""
        val res = json.decodeFromString<DevicePollResponse>(sample)
        assertEquals("approved", res.status)
        assertEquals("tok_abc", res.token)
    }

    @Test
    fun `parses a WhoamiResponse`() {
        val sample = """
            {"you":{"actor":"me@example.com","drive":"drv_1","role":"owner"},"plan":{"id":"pro","name":"Pro","maxFileBytes":5368709120},"used":1024,"quota":10737418240}
        """.trimIndent()
        val res = json.decodeFromString<WhoamiResponse>(sample)
        assertEquals("owner", res.you.role)
        assertEquals("Pro", res.plan.name)
        assertEquals(1024L, res.used)
    }
}
