package app.fastdrive.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.fastdrive.android.data.CachedFile
import app.fastdrive.android.download.DownloadWorker
import app.fastdrive.android.upload.ContentResolverFileAccess
import app.fastdrive.android.upload.UploadWorker
import java.util.UUID

/**
 * The upload the app most recently enqueued, kept around (not just its [UUID]) so a failed upload
 * can be retried by re-enqueueing the same [uri]/[folder] — `WorkRequest.id` alone isn't enough to
 * retry with, since a fresh `WorkRequest` needs the original inputs again.
 */
private data class PendingUpload(val uri: Uri, val folder: String, val workId: UUID)

@Composable
fun FileListScreen(viewModel: FileListViewModel, baseUrl: String, onSettingsClick: () -> Unit = {}) {
    val files by viewModel.files.collectAsState()
    val refreshError by viewModel.refreshError.collectAsState()
    val context = LocalContext.current

    // Tracks the in-flight/most-recent download WorkRequest id per file, purely so each row can
    // show its own progress — nothing here survives process death, but WorkManager's own request
    // does, so a real in-progress download is never lost, just its on-screen indicator.
    val downloadWorkIds = remember { mutableStateMapOf<String, UUID>() }

    // The most recently enqueued upload, so its status can be shown above the list — same
    // observation approach as each download row's own `downloadWorkIds` entry.
    var pendingUpload by remember { mutableStateOf<PendingUpload?>(null) }

    val fileAccess = remember { ContentResolverFileAccess(context.contentResolver) }
    val pickDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // ACTION_OPEN_DOCUMENT results are persistable, but only once this is called — without
            // it, a WorkManager retry (a real possibility now that uploads use NetworkType.CONNECTED
            // constraints and Result.retry()) or a run after the app/device restarts fails with a
            // SecurityException when ContentResolverFileAccess tries to read the URI again.
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val folder = "/"
            pendingUpload = PendingUpload(uri, folder, enqueueUpload(context, baseUrl, uri, folder))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FastDrive") },
                actions = {
                    TextButton(onClick = onSettingsClick) {
                        Text("Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { pickDocumentLauncher.launch(arrayOf("*/*")) }) {
                Text("+")
            }
        },
    ) { scaffoldPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (refreshError != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(refreshError ?: "", modifier = Modifier.padding(end = 8.dp))
                        Button(onClick = { viewModel.refresh() }) {
                            Text("Retry")
                        }
                    }
                    HorizontalDivider()
                }

                UploadStatusRow(
                    pendingUpload = pendingUpload,
                    onRetry = {
                        pendingUpload?.let { p ->
                            pendingUpload = p.copy(workId = enqueueUpload(context, baseUrl, p.uri, p.folder))
                        }
                    },
                    onSucceeded = { viewModel.refresh() },
                    onSignedOut = { viewModel.notifySignedOutFromBackground() },
                )

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(files, key = { it.id }) { file ->
                        FileRow(
                            file = file,
                            downloadWorkId = downloadWorkIds[file.id],
                            onClick = { downloadWorkIds[file.id] = enqueueDownload(context, baseUrl, file) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun enqueueDownload(context: Context, baseUrl: String, file: CachedFile): UUID {
    val request = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(
            workDataOf(
                "file_id" to file.id,
                "file_name" to file.name,
                "base_url" to baseUrl,
            ),
        )
        .build()
    WorkManager.getInstance(context).enqueue(request)
    return request.id
}

private fun enqueueUpload(context: Context, baseUrl: String, uri: Uri, folder: String): UUID {
    val request = OneTimeWorkRequestBuilder<UploadWorker>()
        .setInputData(
            workDataOf(
                "uri" to uri.toString(),
                "folder" to folder,
                "base_url" to baseUrl,
            ),
        )
        // Without this, a retry (WorkManager's own, or the manual "Retry" button below) fires
        // immediately even with no connectivity, only to fail the same way again.
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueue(request)
    return request.id
}

@Composable
private fun UploadStatusRow(
    pendingUpload: PendingUpload?,
    onRetry: () -> Unit,
    onSucceeded: () -> Unit,
    onSignedOut: () -> Unit,
) {
    if (pendingUpload == null) return
    val context = LocalContext.current
    val workInfo = WorkManager.getInstance(context)
        .getWorkInfoByIdFlow(pendingUpload.workId)
        .collectAsState(initial = null)
        .value
    val state = workInfo?.state

    // Reacts to a terminal state exactly once per WorkInfo change: refreshes the list on success
    // (Task 9 — previously the list only ever refreshed on first composition) and, on a failure
    // whose outputData carries the auth_error flag UploadWorker sets on a 401, calls the same
    // sign-out path FileListViewModel already exposes for its own 401 handling.
    LaunchedEffect(workInfo) {
        when (state) {
            WorkInfo.State.SUCCEEDED -> {
                onSucceeded()
                // Nice-to-have cleanup: the permission taken for this upload is no longer needed
                // once it has landed. Only released on success — a FAILED upload may still be
                // retried with the same uri, which needs the permission to still be held.
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        pendingUpload.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            WorkInfo.State.FAILED -> {
                if (workInfo?.outputData?.getBoolean("auth_error", false) == true) onSignedOut()
            }
            else -> {}
        }
    }

    val label = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> "Uploading…"
        WorkInfo.State.SUCCEEDED -> "Uploaded"
        // The server's actual rejection message (set by UploadWorker's Result.failure(outputData)),
        // not a generic string — falls back to one only if outputData somehow has none.
        WorkInfo.State.FAILED -> workInfo?.outputData?.getString("error") ?: "Upload failed"
        else -> null
    }
    if (label != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, modifier = Modifier.padding(end = 8.dp))
            if (state == WorkInfo.State.FAILED) {
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun FileRow(file: CachedFile, downloadWorkId: UUID?, onClick: () -> Unit) {
    val context = LocalContext.current
    val workInfoState = if (downloadWorkId != null) {
        WorkManager.getInstance(context)
            .getWorkInfoByIdFlow(downloadWorkId)
            .collectAsState(initial = null)
    } else {
        null
    }
    val state = workInfoState?.value?.state

    ListItem(
        headlineContent = { Text(file.name) },
        supportingContent = { Text(formatSize(file.size)) },
        trailingContent = downloadStatusContent(state),
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clickable(onClick = onClick),
    )
}

private fun downloadStatusContent(state: WorkInfo.State?): (@Composable () -> Unit)? = when (state) {
    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
        { CircularProgressIndicator(modifier = Modifier.padding(4.dp)) }
    }
    WorkInfo.State.SUCCEEDED -> {
        { Text("Downloaded", color = MaterialTheme.colorScheme.primary) }
    }
    WorkInfo.State.FAILED -> {
        { Text("Failed", color = MaterialTheme.colorScheme.error) }
    }
    else -> null
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unitIndex = -1
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024
        unitIndex++
    }
    return "%.1f %s".format(size, units[unitIndex])
}
