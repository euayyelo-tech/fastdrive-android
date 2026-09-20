package app.fastdrive.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeEngineTest {

    private fun entry(
        path: String,
        size: Long = 10,
        sha256: String? = null,
        mtime: String? = "2026-01-01T00:00:00Z",
        id: String? = null,
        rev: String? = null,
    ) = Entry(path = path, size = size, sha256 = sha256, mtime = mtime, id = id, rev = rev)

    private fun snapshot(vararg entries: Entry): Snapshot = entries.associateBy { it.path }

    // ── same() ──────────────────────────────────────────────────────────

    @Test
    fun `same treats matching rev as identical regardless of other fields`() {
        val a = entry("f", size = 1, mtime = null, rev = "r1")
        val b = entry("f", size = 999, mtime = null, rev = "r1")
        assertTrue(same(a, b))
    }

    @Test
    fun `same treats matching sha256 as identical regardless of size or mtime`() {
        val a = entry("f", size = 1, sha256 = "h1", mtime = null)
        val b = entry("f", size = 999, sha256 = "h1", mtime = "2026-01-01T00:00:00Z")
        assertTrue(same(a, b))
    }

    @Test
    fun `same is false when hashes differ`() {
        val a = entry("f", sha256 = "h1")
        val b = entry("f", sha256 = "h2")
        assertTrue(!same(a, b))
    }

    @Test
    fun `same falls back to size plus close mtime when no rev or hash`() {
        val a = entry("f", size = 10, mtime = "2026-01-01T00:00:00.000Z")
        val b = entry("f", size = 10, mtime = "2026-01-01T00:00:01.500Z")
        assertTrue(same(a, b))
    }

    @Test
    fun `same is false when mtimes are more than 2s apart`() {
        val a = entry("f", size = 10, mtime = "2026-01-01T00:00:00Z")
        val b = entry("f", size = 10, mtime = "2026-01-01T00:00:03Z")
        assertTrue(!same(a, b))
    }

    @Test
    fun `same is false when either mtime is null and no rev or hash`() {
        val a = entry("f", mtime = null)
        val b = entry("f", mtime = "2026-01-01T00:00:00Z")
        assertTrue(!same(a, b))
    }

    @Test
    fun `same treats two nulls as identical and one null as different`() {
        assertTrue(same(null, null))
        assertTrue(!same(entry("f"), null))
        assertTrue(!same(null, entry("f")))
    }

    // ── conflictName() ──────────────────────────────────────────────────

    @Test
    fun `conflictName inserts marker before extension`() {
        assertEquals("docs/report (conflicted copy, PC1).pdf", conflictName("docs/report.pdf", "PC1"))
    }

    @Test
    fun `conflictName handles a root-level file with no extension`() {
        assertEquals("README (conflicted copy, PC1)", conflictName("README", "PC1"))
    }

    // ── pairMoves() ─────────────────────────────────────────────────────

    @Test
    fun `pairMoves matches by id first`() {
        val gone = listOf(entry("old.txt", id = "id1", sha256 = "h1"))
        val appeared = listOf(entry("new.txt", id = "id1", sha256 = "h2"))
        val pairs = pairMoves(gone, appeared)
        assertEquals(1, pairs.size)
        assertEquals("old.txt", pairs[0].first.path)
        assertEquals("new.txt", pairs[0].second.path)
    }

    @Test
    fun `pairMoves falls back to sha256 when no id available`() {
        val gone = listOf(entry("old.txt", sha256 = "h1"))
        val appeared = listOf(entry("new.txt", sha256 = "h1"))
        val pairs = pairMoves(gone, appeared)
        assertEquals(1, pairs.size)
        assertEquals("old.txt", pairs[0].first.path)
        assertEquals("new.txt", pairs[0].second.path)
    }

    @Test
    fun `pairMoves does not pair unrelated entries`() {
        val gone = listOf(entry("old.txt", sha256 = "h1"))
        val appeared = listOf(entry("new.txt", sha256 = "h2"))
        assertTrue(pairMoves(gone, appeared).isEmpty())
    }

    // ── plan() ──────────────────────────────────────────────────────────

    @Test
    fun `file only added remotely is a download`() {
        val r = entry("f", id = "id1")
        val actions = plan(snapshot(), snapshot(), snapshot(r), "PC1")
        assertEquals(listOf(Action.Download("f", "id1")), actions)
    }

    @Test
    fun `file only added locally is an upload`() {
        val l = entry("f")
        val actions = plan(snapshot(), snapshot(l), snapshot(), "PC1")
        assertEquals(listOf(Action.Upload("f", null)), actions)
    }

    @Test
    fun `unchanged on both sides produces no action`() {
        val b = entry("f", rev = "r1")
        val l = entry("f", rev = "r1")
        val r = entry("f", rev = "r1")
        assertTrue(plan(snapshot(b), snapshot(l), snapshot(r), "PC1").isEmpty())
    }

    @Test
    fun `local rename detected via matching id is MoveRemote`() {
        // base+remote agree on old.txt (id1, untouched remotely); locally it vanished from
        // old.txt and reappeared at new.txt still carrying the same known id (the local index
        // recognizes it as the same file, just moved) -> paired by id, not by hash.
        val b = entry("old.txt", id = "id1", rev = "r1")
        val r = entry("old.txt", id = "id1", rev = "r1")
        val l = entry("new.txt", id = "id1", rev = null)
        val actions = plan(snapshot(b), snapshot(l), snapshot(r), "PC1")
        assertEquals(listOf(Action.MoveRemote("old.txt", "new.txt", "id1")), actions)
    }

    @Test
    fun `remote rename detected via matching id is MoveLocal`() {
        val b = entry("old.txt", id = "id1", rev = "r1")
        val l = entry("old.txt", id = "id1", rev = "r1")
        val r = entry("new.txt", id = "id1", rev = "r1")
        val actions = plan(snapshot(b), snapshot(l), snapshot(r), "PC1")
        assertEquals(listOf(Action.MoveLocal("old.txt", "new.txt")), actions)
    }

    @Test
    fun `rename detected via matching sha256 when no id is available`() {
        // Remote rename, paired by hash instead of id (id absent on both sides).
        val b = entry("old.txt", rev = "r1", sha256 = "h1")
        val l = entry("old.txt", rev = "r1", sha256 = "h1")
        val r = entry("new.txt", sha256 = "h1")
        val actions = plan(snapshot(b), snapshot(l), snapshot(r), "PC1")
        assertEquals(listOf(Action.MoveLocal("old.txt", "new.txt")), actions)
    }

    @Test
    fun `both sides made the identical change settles`() {
        val b = entry("f", sha256 = "h1", id = "id1")
        val l = entry("f", sha256 = "h2")
        val r = entry("f", sha256 = "h2", id = "id1")
        val actions = plan(snapshot(b), snapshot(l), snapshot(r), "PC1")
        assertEquals(listOf(Action.Settle("f", "id1")), actions)
    }

    @Test
    fun `both sides deleted produces no action`() {
        val b = entry("f", id = "id1")
        val actions = plan(snapshot(b), snapshot(), snapshot(), "PC1")
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `local edited plus remote deleted uploads because the edit wins`() {
        val b = entry("f", sha256 = "h1", id = "id1")
        val l = entry("f", sha256 = "h2")
        val actions = plan(snapshot(b), snapshot(l), snapshot(), "PC1")
        assertEquals(listOf(Action.Upload("f", null)), actions)
    }

    @Test
    fun `local deleted plus remote edited downloads because the edit wins`() {
        val b = entry("f", sha256 = "h1", id = "id1")
        val r = entry("f", sha256 = "h2", id = "id1")
        val actions = plan(snapshot(b), snapshot(), snapshot(r), "PC1")
        assertEquals(listOf(Action.Download("f", "id1")), actions)
    }

    @Test
    fun `both sides edited differently is a conflict with the renamed name`() {
        val b = entry("docs/report.pdf", sha256 = "h1", id = "id1")
        val l = entry("docs/report.pdf", sha256 = "h2")
        val r = entry("docs/report.pdf", sha256 = "h3", id = "id1")
        val actions = plan(snapshot(b), snapshot(l), snapshot(r), "PC1")
        assertEquals(
            listOf(Action.Conflict("docs/report.pdf", "id1", "docs/report (conflicted copy, PC1).pdf")),
            actions,
        )
    }

    @Test
    fun `local unchanged and remote deleted deletes the local copy`() {
        val b = entry("f", sha256 = "h1", id = "id1")
        val l = entry("f", sha256 = "h1")
        val actions = plan(snapshot(b), snapshot(l), snapshot(), "PC1")
        assertEquals(listOf(Action.DeleteLocal("f")), actions)
    }

    @Test
    fun `remote unchanged and local deleted deletes the remote copy`() {
        val b = entry("f", sha256 = "h1", id = "id1")
        val r = entry("f", sha256 = "h1", id = "id1")
        val actions = plan(snapshot(b), snapshot(), snapshot(r), "PC1")
        assertEquals(listOf(Action.DeleteRemote("f", "id1")), actions)
    }

    // ── splitPath() / joinPath() ────────────────────────────────────────

    @Test
    fun `splitPath separates folder and name`() {
        assertEquals("docs" to "report.pdf", splitPath("docs/report.pdf"))
        assertEquals("" to "report.pdf", splitPath("report.pdf"))
    }

    @Test
    fun `joinPath is the inverse of splitPath`() {
        assertEquals("docs/report.pdf", joinPath("docs", "report.pdf"))
        assertEquals("report.pdf", joinPath("", "report.pdf"))
    }

    // ── unsyncable() ────────────────────────────────────────────────────

    @Test
    fun `unsyncable rejects an empty or dotted path segment`() {
        assertEquals("an empty or dotted path segment", unsyncable("docs//report.pdf"))
        assertEquals("an empty or dotted path segment", unsyncable("docs/./report.pdf"))
        assertEquals("an empty or dotted path segment", unsyncable("docs/../report.pdf"))
    }

    @Test
    fun `unsyncable rejects a disallowed character`() {
        assertEquals("a character the drive does not allow", unsyncable("docs/report?.pdf"))
        assertEquals("a character the drive does not allow", unsyncable("docs/report:x.pdf"))
    }

    @Test
    fun `unsyncable rejects a hidden name`() {
        assertEquals("a hidden name", unsyncable(".gitignore"))
        assertEquals("a hidden name", unsyncable("docs/.hidden"))
    }

    @Test
    fun `unsyncable rejects a name that is too long`() {
        val longName = "a".repeat(256)
        assertEquals("a name that is too long", unsyncable(longName))
    }

    @Test
    fun `unsyncable rejects a folder nested too deep`() {
        val deep = (1..25).joinToString("/") { "d$it" }
        assertEquals("a folder nested too deep", unsyncable(deep))
    }

    @Test
    fun `unsyncable accepts a normal path`() {
        assertNull(unsyncable("docs/report.pdf"))
    }
}
