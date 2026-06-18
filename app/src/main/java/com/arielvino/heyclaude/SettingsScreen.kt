package com.arielvino.heyclaude

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Settings destination ("settings" route). Holds the Anthropic API key (step 1b),
 * the light/dark/device theme, and the talkback toggle, each in a
 * [CollapsibleSection] so the screen stays scannable. Split out of the talk
 * screen, reached from its drawer menu.
 *
 * @param onKeySavedChange notifies the host whether a key is now stored, so the
 *   talk screen can enable/disable Send when the user returns.
 */
@Composable
fun SettingsScreen(
    keyStore: ApiKeyStore,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    talkback: Boolean,
    onTalkbackChange: (Boolean) -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }

        CollapsibleSection("API key") {
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
        }

        CollapsibleSection("Appearance") {
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

        CollapsibleSection("Sound") {
            CheckboxRow(
                label = "Talkback (read replies aloud)",
                checked = talkback,
                onCheckedChange = onTalkbackChange,
            )
        }
    }
}

/**
 * A titled section whose body can be collapsed by tapping the header. Collapsed by
 * default so the screen opens scannable; the ▾/▸ caret reflects state.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

/** A full-width checkbox + label row; tapping anywhere toggles it. */
@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
