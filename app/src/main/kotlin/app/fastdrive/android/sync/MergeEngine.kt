package app.fastdrive.android.sync

import java.time.Instant
import kotlin.math.abs

/**
 * Ports `fastdrive-app/desktop/src/engine/merge.ts` to Kotlin — the pure three-way merge that
 * is the heart of sync. Three pictures of the same folder tree:
 *   base    what this machine last knew both sides agreed on
 *   local   what is on the disk now
 *   remote  what the drive holds now (from the change feed)
 *
 * Compared path by path, each file lands in one of a few outcomes. The rule for conflicts:
 * keep both — the copy that reached the cloud first keeps its name, the other becomes
 * "<name> (conflicted copy, <machine>).<ext>" and is uploaded too. Nothing is ever silently
 * dropped.
 *
 * Pure: no disk, no network, no android.* types. Whatever drives sync feeds this and carries
 * out the plan.
 */

/**
 * One file as one side sees it. `rev` is the drive's own idea of the bytes (id + version): a
 * remote entry carries it, the base remembers the one it last synced, a local entry never has
 * one. Two equal revs are the same bytes without a hash — most files on the drive have no hash
 * until the search indexer reads them, and many never will.
 */
data class Entry(
    val path: String,
    val size: Long,
    val sha256: String?,
    val mtime: String?,
    val id: String? = null,
    val rev: String? = null,
)

typealias Snapshot = Map<String, Entry>

sealed class Action {
    data class Upload(val path: String, val replaceId: String? = null) : Action()
    data class Download(val path: String, val id: String) : Action()
    data class DeleteLocal(val path: String) : Action()
    data class DeleteRemote(val path: String, val id: String) : Action()
    data class MoveRemote(val from: String, val to: String, val id: String) : Action()
    data class MoveLocal(val from: String, val to: String) : Action()
    data class Conflict(val path: String, val id: String, val renamed: String) : Action()
    data class Settle(val path: String, val id: String) : Action()
}

/**
 * `mtime` (and the server's `changedAt`) are ISO-8601 UTC strings with a trailing `Z`
 * (e.g. `2026-09-19T00:00:00Z` — see `RemoteFile.changedAt` and its test fixtures across this
 * codebase), the same shape `Date.parse`/`toISOString()` produce on the TypeScript side.
 * `Instant.parse` accepts that format directly, so no custom formatter is needed here.
 */
fun same(a: Entry?, b: Entry?): Boolean {
    if (a == null || b == null) return a == null && b == null
    if (a.rev != null && b.rev != null && a.rev == b.rev) return true
    if (a.sha256 != null && b.sha256 != null) return a.sha256 == b.sha256
    if (a.mtime == null || b.mtime == null) return false
    val deltaMs = abs(Instant.parse(a.mtime).toEpochMilli() - Instant.parse(b.mtime).toEpochMilli())
    return a.size == b.size && deltaMs < 2000
}

fun conflictName(path: String, machine: String): String {
    val slash = path.lastIndexOf('/')
    val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
    val name = if (slash >= 0) path.substring(slash + 1) else path
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    return "$dir$stem (conflicted copy, $machine)$ext"
}

/**
 * Renames and moves, spotted before anything else: a file that vanished from one path and
 * appeared at another with the same bytes is one move, not a delete and an upload. On the
 * drive a file keeps its id through a move, so that pairs first; otherwise only when the hash
 * is known on both ends — size alone is too easy to fool.
 */
fun pairMoves(gone: List<Entry>, appeared: List<Entry>): List<Pair<Entry, Entry>> {
    val out = mutableListOf<Pair<Entry, Entry>>()
    val byId = gone.filter { it.id != null }.associateBy { it.id!! }.toMutableMap()
    val left = mutableListOf<Entry>()
    for (a in appeared) {
        val from = a.id?.let { byId[it] }
        if (from != null) { out.add(from to a); byId.remove(a.id) } else left.add(a)
    }
    val taken = out.map { it.first }.toSet()
    val byHash = mutableMapOf<String, MutableList<Entry>>()
    for (g in gone) if (g.sha256 != null && g !in taken) byHash.getOrPut(g.sha256) { mutableListOf() }.add(g)
    for (a in left) {
        val hash = a.sha256 ?: continue
        val bucket = byHash[hash] ?: continue
        if (bucket.isNotEmpty()) out.add(bucket.removeAt(0) to a)
    }
    return out
}

fun plan(base: Snapshot, local: Snapshot, remote: Snapshot, machine: String): List<Action> {
    val actions = mutableListOf<Action>()
    val paths = base.keys + local.keys + remote.keys

    val localGone = mutableListOf<Entry>(); val localNew = mutableListOf<Entry>()
    val remoteGone = mutableListOf<Entry>(); val remoteNew = mutableListOf<Entry>()
    for (p in paths) {
        val b = base[p]; val l = local[p]; val r = remote[p]
        if (b != null && l == null && r != null && same(b, r)) localGone.add(b.copy(id = r.id))
        if (b == null && l != null && r == null) localNew.add(l)
        if (b != null && r == null && l != null && same(b, l)) remoteGone.add(b)
        if (b == null && r != null && l == null) remoteNew.add(r)
    }
    val movedLocally = pairMoves(localGone, localNew)
    val movedRemotely = pairMoves(remoteGone, remoteNew)
    val handled = mutableSetOf<String>()
    for ((from, to) in movedLocally) {
        actions.add(Action.MoveRemote(from.path, to.path, from.id!!)); handled.add(from.path); handled.add(to.path)
    }
    for ((from, to) in movedRemotely) {
        actions.add(Action.MoveLocal(from.path, to.path)); handled.add(from.path); handled.add(to.path)
    }

    for (p in paths) {
        if (p in handled) continue
        val b = base[p]; val l = local[p]; val r = remote[p]
        val localChanged = !same(b, l)
        val remoteChanged = !same(b, r)
        if (!localChanged && !remoteChanged) continue
        if (localChanged && !remoteChanged) {
            if (l != null) actions.add(Action.Upload(p, r?.id ?: b?.id))
            else if (r != null) actions.add(Action.DeleteRemote(p, r.id!!))
            continue
        }
        if (!localChanged && remoteChanged) {
            if (r != null) actions.add(Action.Download(p, r.id!!))
            else if (l != null) actions.add(Action.DeleteLocal(p))
            continue
        }
        // Both changed.
        if (l != null && r != null && same(l, r)) { actions.add(Action.Settle(p, r.id!!)); continue }
        if (l == null && r == null) continue
        if (l != null && r == null) { actions.add(Action.Upload(p, null)); continue }
        if (l == null && r != null) { actions.add(Action.Download(p, r.id!!)); continue }
        actions.add(Action.Conflict(p, r!!.id!!, conflictName(p, machine)))
    }
    return actions
}

/** Folder of a relative path on the drive ('' for the root) and its name. */
fun splitPath(path: String): Pair<String, String> {
    val slash = path.lastIndexOf('/')
    return if (slash < 0) "" to path else path.substring(0, slash) to path.substring(slash + 1)
}

fun joinPath(folder: String, name: String): String = if (folder.isNotEmpty()) "$folder/$name" else name

/** Names the drive would refuse or the operating system cannot write. */
fun unsyncable(path: String): String? {
    val parts = path.split('/')
    for (part in parts) {
        if (part.isEmpty() || part == "." || part == "..") return "an empty or dotted path segment"
        if (part.any { it in "\\:*?\"<>|" || it.code < 32 }) return "a character the drive does not allow"
        if (part.startsWith(".")) return "a hidden name"
        if (part.length > 255) return "a name that is too long"
    }
    if (parts.size > 24) return "a folder nested too deep"
    return null
}
