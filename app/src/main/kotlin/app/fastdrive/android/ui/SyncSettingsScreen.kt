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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.sync.InstantSyncService
import app.fastdrive.android.sync.PauseCondition
import app.fastdrive.android.sync.PauseResumeWorker
import app.fastdrive.android.sync.PeriodicSyncWorker
import app.fastdrive.android.sync.SyncMode
import app.fastdrive.android.sync.SyncSettings
import app.fastdrive.android.sync.WifiResumeWatcher
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Result of fetching [DriveApi.whoami] for the quota display at the bottom of this screen. */
private sealed interface QuotaUiState {
    data object Loading : QuotaUiState
    data class Loaded(val used: Long, val quota: Long) : QuotaUiState
    data object Error : QuotaUiState
}

private suspend fun fetchQuota(api: DriveApi): QuotaUiState = try {
    val response = api.whoami()
    QuotaUiState.Loaded(response.used, response.quota)
} catch (e: CancellationException) {
    // Must propagate, not be swallowed as a fetch failure — same rule this codebase already
    // applies to runOnePass (see the fix in commit 2e5e6c3): an unguarded catch-all here would
    // eat structured-concurrency cancellation (e.g. leaving this composable) as if it were a
    // network error.
    throw e
} catch (e: Exception) {
    // Never let a network/parse failure here take down the rest of the settings screen — the
    // folder picker and sync-mode controls above must keep working regardless.
    QuotaUiState.Error
}

/**
 * Lets the user pick the folder FastDrive syncs into and the sync-frequency mode. Choosing a
 * folder or switching to [SyncMode.BATTERY_FRIENDLY] here (re)enqueues Task 7's periodic
 * WorkManager sync via [PeriodicSyncWorker.applySettings] and stops Task 8's
 * [InstantSyncService]; switching to [SyncMode.INSTANT] starts [InstantSyncService] and cancels
 * the periodic work, so the two modes are always mutually exclusive.
 *
 * Also shows a quota/storage-used line fetched from [DriveApi.whoami]. There's no ViewModel or
 * shared "last sync result" signal anywhere in this codebase yet (`PeriodicSyncWorker` and
 * `InstantSyncService` run headless with no state exposed back to the UI layer) — building one
 * just for this display would mean inventing cross-component plumbing this project doesn't have
 * elsewhere. Instead the fetch runs once whenever this composable enters composition, via
 * `LaunchedEffect(Unit)`; since Compose Navigation (`NavHost`) recreates a route's composable each
 * time it's navigated to rather than keeping it alive in the back stack, simply reopening Settings
 * from the file list already re-fetches fresh quota data — covering both "on screen open" and, in
 * practice, "after a sync pass completes" for the common case of checking Settings once a sync is
 * noticed to have run.
 */
@Composable
fun SyncSettingsScreen(
    syncSettings: SyncSettings,
    api: DriveApi,
    wifiResumeWatcher: WifiResumeWatcher,
    requestSpecificWifiLocationPermission: (onGranted: () -> Unit, onDenied: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var folderUri by remember { mutableStateOf(syncSettings.getFolderUri()) }
    var syncMode by remember { mutableStateOf(syncSettings.getSyncMode()) }
    var quotaState by remember { mutableStateOf<QuotaUiState>(QuotaUiState.Loading) }
    var pauseCondition by remember { mutableStateOf(syncSettings.getPauseCondition()) }
    var specificWifiSsid by remember { mutableStateOf("") }
    // Shown when the user picks specific-Wi-Fi and denies the location permission Task 4's
    // MainActivity.requestSpecificWifiLocationPermission() requests — per the spec, this never
    // silently falls back to a different pause condition, it just tells the user and leaves them
    // free to pick another option (or retry the same one, which re-prompts).
    var wifiPermissionDenied by remember { mutableStateOf(false) }

    // Applies a non-timer pause condition (AnyWifi/SpecificWifi/Manual): persists it, re-asserts
    // both trigger mechanisms (they gate on SyncSettings.isPaused()), and (re)starts the Wi-Fi
    // watcher — WifiResumeWatcher.start() stops any previously-running watcher first regardless of
    // the new condition's type, so this also correctly tears down a watcher left over from a prior
    // AnyWifi/SpecificWifi pause when switching to Manual.
    fun applyPause(condition: PauseCondition) {
        syncSettings.setPauseCondition(condition)
        PeriodicSyncWorker.applySettings(context, syncSettings)
        InstantSyncService.applySettings(context, syncSettings)
        wifiResumeWatcher.start(syncSettings, condition)
        pauseCondition = condition
        wifiPermissionDenied = false
    }

    LaunchedEffect(Unit) { quotaState = fetchQuota(api) }

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
            PeriodicSyncWorker.applySettings(context, syncSettings)
            // Finding #1: if the user already had Instant mode selected before ever picking a
            // folder, InstantSyncService.applySettings() was never called for it (only
            // PeriodicSyncWorker was) — instant mode would silently never start until the next
            // app restart happened to re-assert it from MainActivity.onCreate(). Only the folder
            // changed here, not the mode itself, so calling both in either order is safe; matching
            // the mode-switch callbacks below is just for consistency.
            InstantSyncService.applySettings(context, syncSettings)
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
                // Stop the instant-mode service FIRST so a due periodic tick can't race a still-
                // running instant loop into two concurrent sync passes.
                InstantSyncService.applySettings(context, syncSettings)
                PeriodicSyncWorker.applySettings(context, syncSettings)
            },
        )
        SyncModeOption(
            label = "Instant (uses more battery)",
            selected = syncMode == SyncMode.INSTANT,
            onSelect = {
                syncMode = SyncMode.INSTANT
                syncSettings.setSyncMode(SyncMode.INSTANT)
                // Cancel the periodic work FIRST so a tick that's already due can't race the
                // service start into two concurrent sync passes.
                PeriodicSyncWorker.applySettings(context, syncSettings)
                InstantSyncService.applySettings(context, syncSettings)
            },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Text("Storage", style = MaterialTheme.typography.titleMedium)
        when (val state = quotaState) {
            is QuotaUiState.Loading ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            is QuotaUiState.Loaded -> {
                val fraction = if (state.quota > 0) {
                    (state.used.toFloat() / state.quota.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    formatQuota(state.used, state.quota),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            is QuotaUiState.Error ->
                Text(
                    "Couldn't load storage usage",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Text("Pause sync", style = MaterialTheme.typography.titleMedium)

        val activeCondition = pauseCondition
        if (activeCondition == null) {
            Button(
                modifier = Modifier.padding(top = 8.dp),
                onClick = {
                    PauseResumeWorker.pauseForDuration(context, syncSettings, TimeUnit.HOURS.toMillis(1))
                    // Timer/Manual conditions never need the watcher, but a previous AnyWifi/
                    // SpecificWifi pause could still have one registered — tear it down so it can't
                    // race this new Timer pause by firing and clearing it early.
                    wifiResumeWatcher.stop()
                    pauseCondition = syncSettings.getPauseCondition()
                    wifiPermissionDenied = false
                },
            ) { Text("Pause for 1 hour") }

            Button(
                modifier = Modifier.padding(top = 8.dp),
                onClick = {
                    PauseResumeWorker.pauseUntilTomorrowMorning(context, syncSettings)
                    wifiResumeWatcher.stop()
                    pauseCondition = syncSettings.getPauseCondition()
                    wifiPermissionDenied = false
                },
            ) { Text("Pause until tomorrow morning") }

            Button(
                modifier = Modifier.padding(top = 8.dp),
                onClick = { applyPause(PauseCondition.AnyWifi) },
            ) { Text("Pause until connected to any Wi-Fi") }

            OutlinedTextField(
                value = specificWifiSsid,
                onValueChange = {
                    specificWifiSsid = it
                    wifiPermissionDenied = false
                },
                label = { Text("Wi-Fi network name (SSID)") },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Button(
                modifier = Modifier.padding(top = 8.dp),
                enabled = specificWifiSsid.isNotBlank(),
                onClick = {
                    val ssid = specificWifiSsid.trim()
                    // Ask for Task 4's location permission BEFORE storing a SpecificWifi condition
                    // — otherwise WifiResumeWatcher would register a callback that can never read a
                    // real SSID and this pause would never auto-resume.
                    requestSpecificWifiLocationPermission(
                        { applyPause(PauseCondition.SpecificWifi(ssid)) },
                        { wifiPermissionDenied = true },
                    )
                },
            ) { Text("Pause until connected to this Wi-Fi") }
            if (wifiPermissionDenied) {
                Text(
                    "Location permission is needed to detect this Wi-Fi network. Grant it to use " +
                        "this option, or pick a different one below.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Button(
                modifier = Modifier.padding(top = 8.dp),
                onClick = { applyPause(PauseCondition.Manual) },
            ) { Text("Pause indefinitely") }
        } else {
            Text(pauseStatusText(activeCondition), modifier = Modifier.padding(top = 8.dp))
            Button(
                modifier = Modifier.padding(top = 8.dp),
                onClick = {
                    PauseResumeWorker.resumeNow(
                        context,
                        syncSettings,
                        onNetworkWatcherTeardown = { wifiResumeWatcher.stop() },
                    )
                    pauseCondition = null
                    wifiPermissionDenied = false
                },
            ) { Text("Resume now") }
        }
    }
}

/** Plain-language status shown while [PauseCondition] is active, per the spec's exact examples. */
private fun pauseStatusText(condition: PauseCondition): String = when (condition) {
    is PauseCondition.Timer ->
        "Paused until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(condition.resumeAtMillis))
    is PauseCondition.AnyWifi -> "Paused until connected to Wi-Fi"
    is PauseCondition.SpecificWifi -> "Paused until connected to '${condition.ssid}'"
    is PauseCondition.Manual -> "Paused"
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
