package app.fastdrive.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.auth.TokenStore
import app.fastdrive.android.data.AppDatabase
import app.fastdrive.android.sync.InstantSyncService
import app.fastdrive.android.sync.PauseCondition
import app.fastdrive.android.sync.PeriodicSyncWorker
import app.fastdrive.android.sync.SyncSettings
import app.fastdrive.android.sync.WifiResumeWatcher
import app.fastdrive.android.ui.FileListScreen
import app.fastdrive.android.ui.FileListViewModel
import app.fastdrive.android.ui.SignInScreen
import app.fastdrive.android.ui.SyncSettingsScreen

class MainActivity : ComponentActivity() {
    // Android 13+ requires an explicit runtime prompt for notification permissions; the manifest
    // entry alone doesn't grant it. Requested up front so DownloadWorker's failure notification
    // (Task 5) can actually show. Denial is fine here: the download itself still runs and lands
    // in filesDir; the user just won't see a failure notification if it doesn't.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Phase 4 Task 4: a single instance tied to this Activity's lifecycle. There's no Application
    // subclass in this codebase to hang a longer-lived instance off, and re-arming here is a
    // foreground-triggered "reassert whatever was chosen" step (same pattern as the
    // PeriodicSyncWorker.applySettings/InstantSyncService.applySettings calls below), not a
    // background service — restarting the watcher on every app start (onCreate) is acceptable for
    // that, and stop() is defensive against leaking a stale NetworkCallback across recreations.
    private val wifiResumeWatcher: WifiResumeWatcher by lazy { WifiResumeWatcher(applicationContext) }

    // Task 5's UI (the sync-settings screen) calls requestSpecificWifiLocationPermission() when the
    // user picks the specific-Wi-Fi pause option; this launcher backs that call. Denial is reported
    // back via the onDenied callback passed to that method, not silently swallowed like the
    // notification permission above — the spec requires showing the user a clear message and
    // letting them pick a different pause option instead.
    private var pendingLocationPermissionResult: ((Boolean) -> Unit)? = null
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingLocationPermissionResult?.invoke(granted)
            pendingLocationPermissionResult = null
        }

    /**
     * Requests `ACCESS_FINE_LOCATION` if it isn't already granted, then calls [onGranted] or
     * [onDenied]. Android's own system permission dialog serves as the rationale here — the need
     * ("resume sync on this specific Wi-Fi network") is self-evident from the option the user just
     * picked, so a custom rationale screen isn't warranted.
     *
     * [onDenied] is the caller's cue to show "this pause option needs location permission" and let
     * the user choose a different pause option — never to silently fall back to any-Wi-Fi or an
     * indefinite pause.
     */
    fun requestSpecificWifiLocationPermission(onGranted: () -> Unit, onDenied: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
            return
        }
        pendingLocationPermissionResult = { granted -> if (granted) onGranted() else onDenied() }
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val tokenStore = TokenStore(applicationContext)
        // tokenProvider reads TokenStore fresh on every call, so DriveApi always sees the current
        // token with no separate sync step — sign-in, sign-out, and a 401-triggered
        // TokenStore.clear() are all picked up automatically.
        val api = DriveApi(baseUrl = BuildConfig.API_BASE_URL, tokenProvider = { tokenStore.getToken() })
        val database = AppDatabase.get(applicationContext)
        val syncSettings = SyncSettings(applicationContext)
        // Re-asserts whatever schedule the user last chose on every app start — cheap thanks to
        // ExistingPeriodicWorkPolicy.KEEP, and the only place that runs before any sync_settings
        // screen visit, so a battery-friendly choice from a previous install/session actually
        // takes effect again after a process restart.
        PeriodicSyncWorker.applySettings(applicationContext, syncSettings)
        // Same startup re-assertion as above, for Task 8's instant-mode foreground service — a
        // process restart while INSTANT mode was last chosen needs this to actually resume it.
        InstantSyncService.applySettings(applicationContext, syncSettings)
        // Phase 4 Task 4: if a Wi-Fi-based pause was still active when the app last closed,
        // re-arm the watcher now — otherwise it would only resume the next time the app happens
        // to be foregrounded while already on the target network, rather than watching for it.
        val pauseCondition = syncSettings.getPauseCondition()
        if (pauseCondition is PauseCondition.AnyWifi || pauseCondition is PauseCondition.SpecificWifi) {
            wifiResumeWatcher.start(syncSettings, pauseCondition)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val startDestination = if (tokenStore.getToken() == null) "sign_in" else "files"

                    NavHost(navController = navController, startDestination = startDestination) {
                        composable("sign_in") {
                            SignInScreen(
                                api = api,
                                tokenStore = tokenStore,
                                onSignedIn = {
                                    navController.navigate("files") {
                                        popUpTo("sign_in") { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable("files") {
                            val fileListViewModel: FileListViewModel = viewModel(
                                factory = FileListViewModel.Factory(api, database, tokenStore, applicationContext),
                            )
                            val signedOut by fileListViewModel.signedOut.collectAsState()
                            LaunchedEffect(signedOut) {
                                if (signedOut) {
                                    // Finding #2 (Phase 4 fix round): SyncSettings.clearAccountState()
                                    // already clears the persisted pause condition on sign-out, but
                                    // this watcher is an Activity-scoped instance it can't reach —
                                    // stop it here so a Wi-Fi-based pause condition belonging to the
                                    // account that just signed out doesn't keep watching (and
                                    // possibly firing) for the next account that signs in.
                                    wifiResumeWatcher.stop()
                                    navController.navigate("sign_in") {
                                        popUpTo("files") { inclusive = true }
                                    }
                                }
                            }
                            FileListScreen(
                                viewModel = fileListViewModel,
                                baseUrl = BuildConfig.API_BASE_URL,
                                onSettingsClick = { navController.navigate("sync_settings") },
                            )
                        }
                        composable("sync_settings") {
                            SyncSettingsScreen(
                                syncSettings = syncSettings,
                                api = api,
                                wifiResumeWatcher = wifiResumeWatcher,
                                requestSpecificWifiLocationPermission = ::requestSpecificWifiLocationPermission,
                            )
                        }
                    }
                }
            }
        }
    }

    // Finding #3 (Phase 4 fix round): wifiResumeWatcher's ConnectivityManager.NetworkCallback was
    // never unregistered on Activity destruction — every config change (rotation, theme, font
    // size, locale) recreates MainActivity, and with it a fresh watcher, while the OLD instance's
    // callback stayed registered forever (an unconditional leak, and a possible eventual crash if
    // the platform's per-uid registration cap is ever hit).
    override fun onDestroy() {
        wifiResumeWatcher.stop()
        super.onDestroy()
    }
}
