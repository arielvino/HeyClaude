package com.arielvino.heyclaude

import android.service.voice.VoiceInteractionService

/**
 * HeyClaude as the device's **own assistant** (PROJECT_BRIEF goal: locked, hands-free
 * voice). This is the always-resident [VoiceInteractionService] the system keeps bound
 * once the user picks HeyClaude in Settings → Default assistant app. It does almost
 * nothing itself — the actual per-invocation work happens in [HeyClaudeSessionService] /
 * [HeyClaudeSession], wired to this service by the `@xml/interaction_service` metadata.
 *
 * Why this path (validated empirically by the now-deleted LockRelayActivity probe): the
 * official Claude app's assist overlay self-aborts on the keyguard and we can't change
 * its code, but an assistant *we* own gets a session window the system renders over the
 * lock screen — so we can wake, listen, and act without an unlock.
 *
 * This is a skeleton: it makes HeyClaude selectable and opens our listening UI on the
 * assist trigger. A wake word (a separate always-listening service) is a later step.
 */
class HeyClaudeInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // Resident-assistant setup (hotword/AlwaysOnHotwordDetector, etc.) would go here
        // in a later step. The skeleton needs nothing at ready time.
    }
}
