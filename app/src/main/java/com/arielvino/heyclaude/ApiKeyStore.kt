package com.arielvino.heyclaude

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit

/**
 * Stores the Anthropic API key in the Android Keystore via EncryptedSharedPreferences.
 *
 * The key is entered once in the UI and persists across reinstalls/updates. It is
 * never written to the repo, logs, or BuildConfig — this store is the single source
 * of truth (see PROJECT_BRIEF.md §6).
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)
        set(value) {
            prefs.edit {
                if (value.isNullOrBlank()) remove(KEY_API) else putString(KEY_API, value.trim())
            }
        }

    fun hasKey(): Boolean = !apiKey.isNullOrBlank()

    private companion object {
        const val PREFS_FILE = "heyclaude_secure_prefs"
        const val KEY_API = "anthropic_api_key"
    }
}
