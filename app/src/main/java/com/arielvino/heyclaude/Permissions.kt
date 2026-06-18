package com.arielvino.heyclaude

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Helpers for the **OS-permission layer** (layer 2; see [Capability]). The system
 * owns this state — the app can only read it and *request* grants; it can't revoke
 * a permission programmatically (that needs the system settings screen).
 */

/** Returns the subset of [permissions] currently granted to the app. */
fun Context.grantedAmong(permissions: List<String>): Set<String> =
    permissions.filterTo(mutableSetOf()) {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

/** Opens this app's entry in system Settings, where the user can grant/revoke permissions. */
fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

/** Walks the ContextWrapper chain to the hosting [LifecycleOwner] (the Activity), or null. */
private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is LifecycleOwner) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Tracks which of [permissions] are currently granted, and refreshes on every
 * activity ON_RESUME — so grants made outside this screen (the system permission
 * dialog, or the system settings screen reached via [openAppSettings]) are
 * reflected when the user returns. Returns the live granted set plus a manual
 * `refresh` to call after an in-app request completes.
 */
@Composable
fun rememberGrantedPermissions(permissions: List<String>): Pair<Set<String>, () -> Unit> {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.grantedAmong(permissions)) }
    val refresh = { granted = context.grantedAmong(permissions) }

    val lifecycleOwner = context.findLifecycleOwner()
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted to refresh
}

/** Human-readable label for a runtime permission shown on the Permissions screen. */
fun permissionLabel(permission: String): String = when (permission) {
    Manifest.permission.READ_CALENDAR -> "Read calendar"
    Manifest.permission.WRITE_CALENDAR -> "Modify calendar"
    Manifest.permission.READ_CONTACTS -> "Read contacts"
    Manifest.permission.SEND_SMS -> "Send SMS"
    Manifest.permission.RECORD_AUDIO -> "Microphone"
    else -> permission.substringAfterLast('.')
        .replace('_', ' ')
        .lowercase()
        .replaceFirstChar { it.uppercase() }
}
