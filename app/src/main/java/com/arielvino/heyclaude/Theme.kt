package com.arielvino.heyclaude

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * How the app picks light vs. dark colors. [SYSTEM] follows the device setting;
 * [LIGHT]/[DARK] force one regardless of the device. Persisted by [SettingsStore].
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/**
 * App theme. Resolves [themeMode] to a Material 3 color scheme — [ThemeMode.SYSTEM]
 * defers to [isSystemInDarkTheme]. Color-only for now (default M3 palette); brand
 * colors can be slotted into the two schemes later.
 */
@Composable
fun HeyClaudeTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
