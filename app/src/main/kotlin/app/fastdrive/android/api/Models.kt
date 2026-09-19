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
