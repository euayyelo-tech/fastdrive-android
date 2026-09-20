package app.fastdrive.android.api

import kotlinx.serialization.Serializable

@Serializable
data class RemoteFile(
    val id: String,
    val folder: String,
    val name: String,
    val size: Long,
    val contentType: String,
    val sha256: String? = null,
    val mtime: String? = null,
    val version: Int,
    val changedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class GoneEntry(val id: String, val changedAt: String)

@Serializable
data class Cursor(val at: String, val id: String)

@Serializable
data class ChangesPage(
    val files: List<RemoteFile>,
    val gone: List<GoneEntry>,
    val cursor: Cursor? = null,
    val more: Boolean,
    val now: String,
)

@Serializable
data class DeviceCodeResponse(
    val id: String,
    val code: String,
    val secret: String,
    val expiresAt: String,
    val link: String,
)

@Serializable
data class DevicePollResponse(
    val status: String, // "pending" | "approved" | "gone"
    val token: String? = null,
    val owner: String? = null,
)

@Serializable
data class DownloadUrlResponse(val url: String, val vault: Boolean = false)

// whoami() isn't defined as a code sample in the task brief's Models.kt block, but the
// interface list calls for it. Shape verified against desktop/src/engine/api.ts's
// whoami() return type: { you: { actor, drive, role }; plan: { id, name, maxFileBytes }; used; quota }.
@Serializable
data class WhoamiYou(val actor: String, val drive: String, val role: String)

@Serializable
data class WhoamiPlan(val id: String, val name: String, val maxFileBytes: Long)

@Serializable
data class WhoamiResponse(
    val you: WhoamiYou,
    val plan: WhoamiPlan,
    val used: Long,
    val quota: Long,
)

// Verified against fastdrive-app's real route handlers (app/api/files/upload-url,
// [id]/parts, [id]/complete, [id]/confirm, [id]/abort/route.ts), not just transcribed
// from the task brief — two fields the brief's draft omitted:
//   - `key`: always present in every upload-url response branch (fresh, replace,
//     multipart, single-PUT); it's the storage object key, distinct from `id`.
//   - `vault`: always present (true/false), telling the client whether the file
//     landed in an E2E-encrypted Vault folder.
// `replaced`/`versionId` only appear on the REPLACE branch, so they stay nullable.
@Serializable
data class UploadUrlResponse(
    val id: String,
    val key: String,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val multipart: Boolean = false,
    val uploadId: String? = null,
    val partSize: Long? = null,
    val parts: Int? = null,
    val vault: Boolean = false,
    val replaced: Boolean? = null,
    val versionId: String? = null,
)

// POST /api/files/[id]/parts returns { urls: { [partNumber]: url } }. JS object keys
// are always strings on the wire even though the server builds the map from numbers,
// so Map<String, String> is the correct wire shape here (confirmed by reading
// [id]/parts/route.ts directly rather than trusting the brief's transcription).
@Serializable
data class PartsResponse(val urls: Map<String, String>)

// POST /api/files/[id]/complete body: { uploadId, parts: [{ PartNumber, ETag }] }.
// Field casing (PartNumber, ETag — not partNumber/etag) confirmed against
// [id]/complete/route.ts's own destructuring.
@Serializable
data class PartETag(val PartNumber: Int, val ETag: String)

// Request body for POST /api/files/[id]/complete. A plain mapOf(String to Any) can't
// serialize here since `parts` is a List<PartETag>, not a String like the other
// upload calls' bodies — kotlinx.serialization has no serializer for `Any`.
@Serializable
data class CompleteRequest(val uploadId: String, val parts: List<PartETag>)

// Request body for POST /api/files/upload-url. Previously built via a stringly-typed
// buildMap<String, String> (put("multipart", multipart.toString())), which serialized
// `multipart: true` as the JSON STRING "true" rather than the boolean `true`. The
// server route (app/api/files/upload-url/route.ts) does `body?.multipart === true` —
// a strict equality check that always fails against a string, so multipart uploads
// never actually started server-side. A real @Serializable class makes every field's
// wire type explicit and correct.
@Serializable
data class UploadUrlRequest(
    val name: String,
    val contentType: String,
    val size: Long,
    val folder: String,
    val multipart: Boolean,
    val mtime: String? = null,
    val sha256: String? = null,
    val replace: String? = null,
)

// Request body for POST /api/files/[id]/parts. Same stringly-typed-map bug as
// UploadUrlRequest above: `from`/`to` were being sent as JSON strings via
// mapOf(... to from.toString() ...). The server's Number(body?.from)-style coercion
// happens to tolerate numeric strings, but there's no reason to rely on that when a
// typed request body sends real JSON numbers instead.
@Serializable
data class PartsRequest(val uploadId: String, val from: Int, val to: Int)
