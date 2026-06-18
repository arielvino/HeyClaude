package com.arielvino.heyclaude

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Permissions destination ("permissions" route) — the **OS-permission layer**
 * (layer 2; see [Capability]). Lists the runtime permissions that capabilities
 * declare, with their grant status, and lets the user request a grant. This layer
 * is independent of the app-level enable on [CapabilitiesScreen]: granting a
 * permission here does NOT switch a capability on, and a capability can be switched
 * off while its permission stays granted.
 *
 * Android only lets an app *request* a grant; revoking is done in system settings,
 * so granted rows link out there rather than offering an in-app toggle.
 */
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val permissions = Capabilities.allRequiredPermissions
    val (granted, refresh) = rememberGrantedPermissions(permissions)

    val requestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh() }

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
            Text("Permissions", style = MaterialTheme.typography.headlineMedium)
        }

        Text(
            "System permissions a capability needs before it can actually run. " +
                "Allowing one here doesn't switch the capability on — do that under " +
                "Capabilities. The two are independent.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (permissions.isEmpty()) {
            Text(
                "None of your current capabilities need a system permission.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            permissions.forEach { permission ->
                PermissionRow(
                    label = permissionLabel(permission),
                    usedBy = Capabilities.ALL
                        .filter { permission in it.requiredPermissions }
                        .map { it.title },
                    granted = permission in granted,
                    onGrant = { requestLauncher.launch(arrayOf(permission)) },
                    onManage = { context.openAppSettings() },
                )
            }
        }
    }
}

/** One permission's label, which capabilities use it, status, and grant/manage action. */
@Composable
private fun PermissionRow(
    label: String,
    usedBy: List<String>,
    granted: Boolean,
    onGrant: () -> Unit,
    onManage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (usedBy.isNotEmpty()) {
                Text(
                    "Used by: ${usedBy.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                if (granted) "Granted ✓" else "Not granted",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        // Granted permissions can only be revoked from system settings; ungranted
        // ones can be requested in-app.
        if (granted) {
            OutlinedButton(onClick = onManage) { Text("Manage") }
        } else {
            OutlinedButton(onClick = onGrant) { Text("Grant") }
        }
    }
}
