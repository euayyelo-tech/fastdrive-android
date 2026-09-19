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
import app.fastdrive.android.ui.FileListScreen
import app.fastdrive.android.ui.FileListViewModel
import app.fastdrive.android.ui.SignInScreen

// TODO(Task 4/5): move this to a build config / settings screen instead of hardcoding.
private const val API_BASE_URL = "https://fastdrive.app"

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
        val api = DriveApi(baseUrl = API_BASE_URL, token = tokenStore.getToken())
        val database = AppDatabase.get(applicationContext)

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
                                    api.setToken(tokenStore.getToken())
                                    navController.navigate("files") {
                                        popUpTo("sign_in") { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable("files") {
                            val fileListViewModel: FileListViewModel = viewModel(
                                factory = FileListViewModel.Factory(api, database, applicationContext),
                            )
                            FileListScreen(viewModel = fileListViewModel, baseUrl = API_BASE_URL)
                        }
                    }
                }
            }
        }
    }
}
