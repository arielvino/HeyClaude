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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Capabilities destination ("capabilities" route) — the **app-level enable** layer
 * (layer 1; see [Capability]). One checkbox per capability: what the user is willing
 * to let Claude do on the device. Granting the matching system permission is a
 * separate concern handled on [PermissionsScreen].
 *
 * When a capability is enabled but a runtime permission it needs is not granted, the
 * row shows a warning and a shortcut to the Permissions screen — the capability is
 * switched on but can't actually run until the permission is granted there.
 *
 * @param onOpenPermissions navigate to the Permissions screen (from a warning row).
 */
@Composable
fun CapabilitiesScreen(
    capabilityStore: CapabilityStore,
    onOpenPermissions: () -> Unit,
    onBack: () -> Unit,
) {
    var enabled by remember { mutableStateOf(capabilityStore.enabledStates()) }
    val (granted, _) = rememberGrantedPermissions(Capabilities.allRequiredPermissions)

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
            Text("Capabilities", style = MaterialTheme.typography.headlineMedium)
        }

        Text(
            "Pick what Claude is allowed to do on your device. Some capabilities also " +
                "need a system permission — you grant those separately under Permissions.",
            style = MaterialTheme.typography.bodySmall,
        )

        Capabilities.ALL.forEach { capability ->
            val isOn = enabled[capability.id] ?: capability.enabledByDefault
            CapabilityRow(
                capability = capability,
                checked = isOn,
                // Warn only when switched ON but the OS permission is still missing.
                needsPermission = isOn && !capability.permissionSatisfied(granted),
                onCheckedChange = { on ->
                    capabilityStore.setEnabled(capability, on)
                    enabled = enabled + (capability.id to on)
                },
                onOpenPermissions = onOpenPermissions,
            )
        }
    }
}

/** A capability's checkbox + title/description, plus a warning shortcut when it needs a grant. */
@Composable
private fun CapabilityRow(
    capability: Capability,
    checked: Boolean,
    needsPermission: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenPermissions: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(modifier = Modifier.weight(1f)) {
                Text(capability.title, style = MaterialTheme.typography.titleMedium)
                Text(capability.description, style = MaterialTheme.typography.bodySmall)
            }
            if (needsPermission) {
                Text(
                    "⚠",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (needsPermission) {
            TextButton(onClick = onOpenPermissions) {
                Text(
                    "Needs a system permission — grant it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
