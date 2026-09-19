package app.fastdrive.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fastdrive.android.api.ApiException
import app.fastdrive.android.api.DeviceCodeResponse
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.auth.TokenStore
import kotlinx.coroutines.delay

private sealed interface SignInState {
    data object Loading : SignInState
    data class AwaitingApproval(val deviceCode: DeviceCodeResponse) : SignInState
    data class Error(val message: String) : SignInState
}

@Composable
fun SignInScreen(
    api: DriveApi,
    tokenStore: TokenStore,
    onSignedIn: () -> Unit,
) {
    var state by remember { mutableStateOf<SignInState>(SignInState.Loading) }
    var attempt by remember { mutableStateOf(0) }

    // Restarting the flow (after "gone") just bumps `attempt`, which re-runs this effect.
    androidx.compose.runtime.LaunchedEffect(attempt) {
        state = SignInState.Loading
        try {
            val deviceCode = api.deviceCode(name = "Android")
            state = SignInState.AwaitingApproval(deviceCode)
            while (true) {
                delay(2500)
                val poll = api.devicePoll(deviceCode.id, deviceCode.secret)
                when (poll.status) {
                    "approved" -> {
                        tokenStore.setToken(poll.token)
                        onSignedIn()
                        return@LaunchedEffect
                    }
                    "gone" -> {
                        state = SignInState.Error("This sign-in code expired.")
                        return@LaunchedEffect
                    }
                    else -> { /* pending — keep polling */ }
                }
            }
        } catch (e: ApiException) {
            state = SignInState.Error(e.message ?: "Sign-in failed (${e.status}).")
        } catch (e: Exception) {
            state = SignInState.Error(e.message ?: "Sign-in failed.")
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (val s = state) {
            is SignInState.Loading -> {
                CircularProgressIndicator()
                Text("Starting sign-in...", modifier = Modifier.padding(top = 16.dp))
            }
            is SignInState.AwaitingApproval -> {
                Text("Sign in to FastDrive", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Go to ${s.deviceCode.link} and enter this code:",
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    s.deviceCode.code,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            is SignInState.Error -> {
                Text(s.message, modifier = Modifier.padding(bottom = 16.dp))
                Button(onClick = { attempt++ }) {
                    Text("Try again")
                }
            }
        }
    }
}
