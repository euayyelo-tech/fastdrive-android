package app.fastdrive.android.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.fastdrive.android.data.CachedFile
import app.fastdrive.android.download.DownloadWorker

@Composable
fun FileListScreen(viewModel: FileListViewModel, baseUrl: String) {
    val files by viewModel.files.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(files, key = { it.id }) { file ->
            FileRow(file, onClick = { enqueueDownload(context, baseUrl, file) })
            HorizontalDivider()
        }
    }
}

private fun enqueueDownload(context: Context, baseUrl: String, file: CachedFile) {
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
}

@Composable
private fun FileRow(file: CachedFile, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(file.name) },
        supportingContent = { Text(formatSize(file.size)) },
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clickable(onClick = onClick),
    )
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
