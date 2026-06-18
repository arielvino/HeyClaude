package com.arielvino.heyclaude

import android.content.Context
import androidx.core.content.edit

/**
 * A device action the assistant can perform (PROJECT_BRIEF.md §2 / build step 2).
 *
 * Each capability is gated by **two independent layers** that never collapse into a
 * single toggle:
 *  1. **App-level enable** — the checkbox on [CapabilitiesScreen], persisted in
 *     [CapabilityStore]. The user's "I want Claude to be able to do this" switch.
 *  2. **OS permission grant** — the Android runtime permissions in
 *     [requiredPermissions], owned by the system and managed on the separate
 *     [PermissionsScreen].
 *
 * A capability is *effective* (i.e. would be exposed to Claude's tool loop) only
 * when it is app-enabled AND every required permission is granted. The other
 * combinations are deliberately allowed:
 *  - enabled but a permission is missing → a warning on the Capabilities screen
 *    (the action can't actually run yet);
 *  - permission granted but app-disabled → a deliberate app-level block (the OS
 *    would allow it, but the user has switched the capability off).
 *
 * [requiredPermissions] lists **runtime (dangerous) permissions only** — the ones
 * that need an explicit grant. Install-time/normal permissions (INTERNET,
 * SET_ALARM) are declared in the manifest and auto-granted, so they don't gate a
 * capability and don't appear here.
 */
data class Capability(
    val id: String,
    val title: String,
    val description: String,
    val requiredPermissions: List<String> = emptyList(),
    /** App-enabled out of the box; the OS permission (if any) is still requested separately. */
    val enabledByDefault: Boolean = true,
) {
    /** True when every runtime permission this capability needs is in [granted]. */
    fun permissionSatisfied(granted: Set<String>): Boolean =
        requiredPermissions.all { it in granted }
}

/**
 * The static registry of capabilities the app knows about. Adding a new device
 * action is a single entry here; if it needs a runtime permission, list it in
 * [Capability.requiredPermissions] (and declare it in AndroidManifest.xml) and the
 * permission layer — Permissions screen row, warning icon, effective-state gating —
 * picks it up automatically.
 *
 * Seed set (chosen 2026-06-18): alarm/timer, open app, web search. None of these
 * needs a runtime permission, so [allRequiredPermissions] is currently empty and
 * the Permissions screen shows its empty state. A capability like Calendar
 * (READ_CALENDAR / WRITE_CALENDAR) would be the first to exercise the permission
 * layer — see the commented entry below.
 */
object Capabilities {

    val ALL: List<Capability> = listOf(
        Capability(
            id = "alarm",
            title = "Set alarms & timers",
            description = "Create alarms and countdown timers in your clock app.",
            // AlarmClock intents need no runtime permission (SET_ALARM is install-time).
        ),
        Capability(
            id = "open_app",
            title = "Open apps",
            description = "Launch an installed app by name.",
            // Resolving/launching an app via intent needs no runtime permission.
        ),
        Capability(
            id = "web_search",
            title = "Web search",
            description = "Look things up on the web (server-side Anthropic web search).",
            // Uses INTERNET only (install-time, manifest-declared); no runtime permission.
        ),
        // Example of a capability that DOES exercise the permission layer — uncomment
        // (and add the two <uses-permission> lines to AndroidManifest.xml) to see the
        // warning icon + a Permissions row appear, with no other code changes:
        // Capability(
        //     id = "calendar",
        //     title = "Calendar",
        //     description = "Read your schedule and create events.",
        //     requiredPermissions = listOf(
        //         android.Manifest.permission.READ_CALENDAR,
        //         android.Manifest.permission.WRITE_CALENDAR,
        //     ),
        //     enabledByDefault = false,
        // ),
    )

    val byId: Map<String, Capability> = ALL.associateBy { it.id }

    /** Distinct runtime permissions any registered capability needs — the Permissions screen's rows. */
    val allRequiredPermissions: List<String> =
        ALL.flatMap { it.requiredPermissions }.distinct()
}

/**
 * Persists the **app-level enable** layer (layer 1) for each capability, in a plain
 * (non-secret) prefs file — parallel to [SettingsStore]. The OS-permission layer is
 * not stored here; it's read live from the system via [grantedAmong].
 */
class CapabilityStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun isEnabled(capability: Capability): Boolean =
        prefs.getBoolean(key(capability.id), capability.enabledByDefault)

    fun setEnabled(capability: Capability, enabled: Boolean) {
        prefs.edit { putBoolean(key(capability.id), enabled) }
    }

    /** Snapshot of every registered capability's app-level enable state, keyed by id. */
    fun enabledStates(): Map<String, Boolean> =
        Capabilities.ALL.associate { it.id to isEnabled(it) }

    private fun key(id: String) = "$KEY_PREFIX$id"

    private companion object {
        const val PREFS_FILE = "heyclaude_capabilities"
        const val KEY_PREFIX = "cap_enabled_"
    }
}
