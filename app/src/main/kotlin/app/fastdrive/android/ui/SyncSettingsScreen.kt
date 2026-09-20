package app.fastdrive.android.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.fastdrive.android.sync.SyncMode
import app.fastdrive.android.sync.SyncSettings

/**
 * Lets the user pick the folder FastDrive syncs into and the sync-frequency mode. Neither choice
 * starts any actual sync work here — Tasks 7-8 read [SyncSettings] to decide which trigger
 * mechanism (WorkManager vs. a foreground service) to run.
 */
@Composable
fun SyncSettingsScreen(syncSettings: SyncSettings) {
    val context = LocalContext.current
    var folderUri by remember { mutableStateOf(syncSettings.getFolderUri()) }
    var syncMode by remember { mutableStateOf(syncSettings.getSyncMode()) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Unlike Phase 2's read-only ACTION_OPEN_DOCUMENT picker, the sync engine needs to
            // write downloaded/moved/trashed files into this tree, so both flags are required.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            syncSettings.setFolderUri(uri)
            folderUri = uri
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Sync folder", style = MaterialTheme.typography.titleMedium)
        Text(
            folderUri?.toString() ?: "No folder chosen yet",
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Button(onClick = { folderPickerLauncher.launch(null) }) {
            Text(if (folderUri == null) "Choose folder" else "Change folder")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Text("Sync mode", style = MaterialTheme.typography.titleMedium)

        SyncModeOption(
            label = "Battery-friendly (every 15 min)",
            selected = syncMode == SyncMode.BATTERY_FRIENDLY,
            onSelect = {
                syncMode = SyncMode.BATTERY_FRIENDLY
                syncSettings.setSyncMode(SyncMode.BATTERY_FRIENDLY)
                // TODO(Tasks 7-8): stop the instant-sync foreground service (if running) and
                // (re)schedule the periodic WorkManager sync request for this mode.
            },
        )
        SyncModeOption(
            label = "Instant (uses more battery)",
            selected = syncMode == SyncMode.INSTANT,
            onSelect = {
                syncMode = SyncMode.INSTANT
                syncSettings.setSyncMode(SyncMode.INSTANT)
                // TODO(Tasks 7-8): cancel the periodic WorkManager sync request (if scheduled)
                // and start the foreground service that watches for changes instantly.
            },
        )
    }
}

@Composable
private fun SyncModeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
