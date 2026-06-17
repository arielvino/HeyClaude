package com.arielvino.heyclaude

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Plain (unencrypted) store for non-secret user preferences — currently just the
 * theme mode. Secrets like the API key live in [ApiKeyStore] instead.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = prefs.getString(KEY_THEME, null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
        set(value) = prefs.edit { putString(KEY_THEME, value.name) }

    private companion object {
        const val PREFS_FILE = "heyclaude_prefs"
        const val KEY_THEME = "theme_mode"
    }
}
