package app.fastdrive.android.download

import app.fastdrive.android.api.DownloadUrlProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream

/** [fileId] resolved to a Vault-protected file, which can't be downloaded this way yet — matches
 *  [DownloadWorker]'s existing "Vault files can't be downloaded yet" handling. */
class VaultFileException(val fileId: String) : IOException("Vault files can't be downloaded yet")

/**
 * The download protocol itself (resolve a signed URL via [DownloadUrlProvider.downloadUrl], then
 * GET the bytes), extracted from [DownloadWorker] so Task 6's `SyncOrchestrator` can download a
 * file directly into the user's synced folder during a sync pass — [DownloadWorker] always writes
 * into `filesDir/downloads/<name>`, a flat app-private location that makes no sense for a file
 * that needs to land at a specific relative path inside a `DocumentFile` tree, so the destination
 * is left entirely to the caller via [destination] rather than duplicated here.
 *
 * [DownloadWorker] now delegates here for the same network protocol, keeping its own
 * filename-sanitizing/destination-resolution logic (which only makes sense for its own use case)
 * local to itself.
 */
object FileDownloader {
    suspend fun download(api: DownloadUrlProvider, httpClient: OkHttpClient, fileId: String, destination: () -> OutputStream) {
        val downloadInfo = api.downloadUrl(fileId)
        if (downloadInfo.vault) throw VaultFileException(fileId)

        val request = Request.Builder().url(downloadInfo.url).build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) {
                throw IOException("download failed: ${response.code}")
            }
            body.byteStream().use { input ->
                destination().use { output -> input.copyTo(output) }
            }
        }
    }
}
