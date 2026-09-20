package app.fastdrive.android.upload

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * Seam over [ContentResolver] so later tasks' UploadWorker never touches ContentResolver
 * directly — exactly mirroring how DownloadWorker never touches OkHttpClient directly outside
 * DriveApi. [stat] can return a [PickedFile] with size == -1L when the content provider doesn't
 * report a size; callers must treat that as "size unknown" and fail clearly rather than sending
 * a bogus size to the server's upload-url call.
 */
interface FileAccess {
    fun stat(uri: Uri): PickedFile
    fun openStream(uri: Uri): InputStream
}

class ContentResolverFileAccess(private val resolver: ContentResolver) : FileAccess {
    override fun stat(uri: Uri): PickedFile {
        var name = "file"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        val contentType = resolver.getType(uri) ?: "application/octet-stream"
        return PickedFile(uri, name, size, contentType)
    }

    override fun openStream(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IllegalStateException("cannot open $uri")
}
