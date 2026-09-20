package app.fastdrive.android.ui

import android.content.Context
import android.widget.Toast
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.fastdrive.android.data.CachedFile
import app.fastdrive.android.download.DownloadWorker
import app.fastdrive.android.upload.ContentResolverFileAccess
import java.util.UUID

@Composable
fun FileListScreen(viewModel: FileListViewModel, baseUrl: String) {
    val files by viewModel.files.collectAsState()
    val refreshError by viewModel.refreshError.collectAsState()
    val context = LocalContext.current

    // Tracks the in-flight/most-recent download WorkRequest id per file, purely so each row can
    // show its own progress — nothing here survives process death, but WorkManager's own request
    // does, so a real in-progress download is never lost, just its on-screen indicator.
    val downloadWorkIds = remember { mutableStateMapOf<String, UUID>() }

    // Task 1 only: pick a file and confirm its metadata was read via ContentResolver. Task 3/4
    // wires this PickedFile into the actual upload protocol.
    val fileAccess = remember { ContentResolverFileAccess(context.contentResolver) }
    val pickDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val picked = fileAccess.stat(uri)
            Toast.makeText(
                context,
                "Selected: ${picked.name}, ${picked.size} bytes",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
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
