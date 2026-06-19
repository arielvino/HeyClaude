package com.arielvino.heyclaude

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Factory the framework binds to spin up a fresh [HeyClaudeSession] each time the
 * assistant is invoked (assist gesture, power-button hold, lock-screen voice assist).
 * Registered as the `sessionService` in `@xml/interaction_service`; bound only by the
 * system via the `BIND_VOICE_INTERACTION` permission.
 */
class HeyClaudeSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        HeyClaudeSession(this)
}
