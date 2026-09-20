package app.fastdrive.android.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.fastdrive.android.api.ChangesPage
import app.fastdrive.android.api.Cursor
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.auth.TokenAccess
import app.fastdrive.android.auth.TokenStore
import app.fastdrive.android.auth.handleUnauthorized
import app.fastdrive.android.auth.isUnauthorized
import app.fastdrive.android.data.AppDatabase
import app.fastdrive.android.data.CachedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Small, unencrypted prefs file just for the sync cursor. It is not a secret, so it does not
 * need to live alongside TokenStore's EncryptedSharedPreferences.
 */
private const val CURSOR_PREFS = "fastdrive_sync_prefs"
private const val CURSOR_KEY = "changes_cursor"

class FileListViewModel(
    private val database: AppDatabase,
    private val tokenStore: TokenAccess,
    context: Context,
    // Defaults to the real API, but taken as a plain suspend function so a test can substitute a
    // fake multi-page sequence without needing a real DriveApi/network stack.
    private val changesFetcher: suspend (Cursor?) -> ChangesPage,
) : ViewModel() {

    private val prefs = context.applicationContext.getSharedPreferences(CURSOR_PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    val files: StateFlow<List<CachedFile>> = database.cachedFileDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _signedOut = MutableStateFlow(false)

    /** True once a refresh has hit a 401 and the token has been cleared. The UI should navigate
     *  back to sign-in when this flips to true. */
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    private val _refreshError = MutableStateFlow<String?>(null)

    /** A short message for a non-auth refresh failure (network, 5xx, bad JSON, ...). The cached
     *  list is left showing; the UI should surface this as a dismissible/retryable banner, not a
     *  blocking dialog or a crash. */
    val refreshError: StateFlow<String?> = _refreshError.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                var cursor = prefs.getString(CURSOR_KEY, null)?.let {
                    runCatching { json.decodeFromString<Cursor>(it) }.getOrNull()
                }
                val dao = database.cachedFileDao()
                var more: Boolean
                do {
                    val page = changesFetcher(cursor)
                    if (page.files.isNotEmpty()) {
                        dao.upsertAll(
                            page.files.map { remote ->
                                CachedFile(
                                    id = remote.id,
                                    folder = remote.folder,
                                    name = remote.name,
                                    size = remote.size,
                                    contentType = remote.contentType,
                                    changedAt = remote.changedAt,
                                    sha256 = remote.sha256,
                                    mtime = remote.mtime,
                                )
                            },
                        )
                    }
                    if (page.gone.isNotEmpty()) {
                        dao.deleteByIds(page.gone.map { it.id })
                    }
                    if (page.cursor != null) {
                        cursor = page.cursor
                        prefs.edit().putString(CURSOR_KEY, json.encodeToString(page.cursor)).apply()
                    }
                    more = page.more
                } while (more)
            }.onSuccess {
                _refreshError.value = null
            }.onFailure { e ->
                if (isUnauthorized(e)) {
                    handleUnauthorized(tokenStore)
                    _signedOut.value = true
                } else {
                    _refreshError.value = e.message ?: "Couldn't refresh files."
                }
            }
        }
    }

    /**
     * Called by `FileListScreen` when it observes an upload's `WorkInfo` failing with the
     * `auth_error` flag set. `UploadWorker` already cleared the shared token store itself (it runs
     * in the background and can't navigate); this just flips the same [signedOut] flag the UI
     * already watches to navigate back to sign-in, without a second `TokenStore.clear()` call.
     */
    fun notifySignedOutFromBackground() {
        _signedOut.value = true
    }

    class Factory(
        private val api: DriveApi,
        private val database: AppDatabase,
        private val tokenStore: TokenStore,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FileListViewModel(database, tokenStore, context, changesFetcher = api::changes) as T
        }
    }
}
