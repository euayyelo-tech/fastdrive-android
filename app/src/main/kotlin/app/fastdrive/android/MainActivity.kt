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
import app.fastdrive.android.sync.SyncSettings
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
                            SyncSettingsScreen(syncSettings = syncSettings)
                        }
                    }
                }
            }
        }
    }
}
