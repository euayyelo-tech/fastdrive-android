package app.fastdrive.android.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Seam for [TokenStore] so tests (e.g. FileListViewModelTest) can substitute a fake instead of a
 * real [TokenStore] — Robolectric's JVM has no AndroidKeyStore provider, so constructing a real
 * one there throws.
 */
interface TokenAccess {
    fun getToken(): String?
    fun setToken(token: String?)
    fun clear()
}

class TokenStore(context: Context) : TokenAccess {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "fastdrive_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getToken(): String? = prefs.getString("auth_token", null)
    override fun setToken(token: String?) = prefs.edit().putString("auth_token", token).apply()
    override fun clear() = prefs.edit().clear().apply()
}
