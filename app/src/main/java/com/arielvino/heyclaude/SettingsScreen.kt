package com.arielvino.heyclaude

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Settings destination ("settings" route). Owns the Anthropic API key (step 1b)
 * and the light/dark/device theme choice — split out of [MainActivity] so the
 * talk screen stays focused on the model turn.
 *
 * @param onKeySavedChange notifies the host whether a key is now stored, so the
 *   talk screen can enable/disable Send when the user returns.
 */
@Composable
fun SettingsScreen(
    keyStore: ApiKeyStore,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onKeySavedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var keyInput by remember { mutableStateOf(keyStore.apiKey.orEmpty()) }
    var keySaved by remember { mutableStateOf(keyStore.hasKey()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }

        // --- Anthropic API key (step 1b) ---
        Text("API key", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("Anthropic API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                keyStore.apiKey = keyInput
                keySaved = keyStore.hasKey()
                onKeySavedChange(keySaved)
            },
            enabled = keyInput.isNotBlank(),
        ) { Text("Save key") }
        Text(
            text = if (keySaved) "Key saved ✓" else "No key saved",
            style = MaterialTheme.typography.bodySmall,
        )

        // --- Appearance: dark / light / follow-device theme ---
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                val label = when (mode) {
                    ThemeMode.SYSTEM -> "Device"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                }
                // Selected mode is filled; the rest are outlined.
                if (mode == themeMode) {
                    Button(onClick = { onThemeModeChange(mode) }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { onThemeModeChange(mode) }) { Text(label) }
                }
            }
        }
    }
}
