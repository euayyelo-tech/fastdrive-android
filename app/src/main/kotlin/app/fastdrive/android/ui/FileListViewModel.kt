package app.fastdrive.android.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.fastdrive.android.api.Cursor
import app.fastdrive.android.api.DriveApi
import app.fastdrive.android.data.AppDatabase
import app.fastdrive.android.data.CachedFile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val api: DriveApi,
    private val database: AppDatabase,
    context: Context,
) : ViewModel() {

    private val prefs = context.applicationContext.getSharedPreferences(CURSOR_PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    val files: StateFlow<List<CachedFile>> = database.cachedFileDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refresh() {
        viewModelScope.launch {
            val storedCursor = prefs.getString(CURSOR_KEY, null)?.let {
                runCatching { json.decodeFromString<Cursor>(it) }.getOrNull()
            }
            val page = api.changes(storedCursor)
            val dao = database.cachedFileDao()
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
                        )
                    },
                )
            }
            if (page.gone.isNotEmpty()) {
                dao.deleteByIds(page.gone.map { it.id })
            }
            page.cursor?.let { cursor ->
                prefs.edit().putString(CURSOR_KEY, json.encodeToString(cursor)).apply()
            }
        }
    }

    class Factory(
        private val api: DriveApi,
        private val database: AppDatabase,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FileListViewModel(api, database, context) as T
        }
    }
}
