package app.fastdrive.android.auth

import app.fastdrive.android.api.ApiException

/**
 * True when [e] is the server telling us the current auth token is no longer valid.
 *
 * Shared by [app.fastdrive.android.ui.FileListViewModel.refresh] and
 * [app.fastdrive.android.upload.UploadWorker.doWork] so a 401 is recognized the same way in both
 * places instead of two copies of the same `is ApiException && status == 401` check drifting apart.
 */
fun isUnauthorized(e: Throwable): Boolean = e is ApiException && e.status == 401

/**
 * Clears the stored token so the app falls back to sign-in the next time anything checks it.
 * [TokenStore] always reads/writes the same underlying "fastdrive_secure_prefs" file regardless of
 * which component constructed the instance, so a clear from a background [android.content.Context]
 * (e.g. inside `UploadWorker`) is visible to the foreground `TokenStore` instance the UI holds too.
 */
fun handleUnauthorized(tokenStore: TokenAccess) {
    tokenStore.clear()
}
