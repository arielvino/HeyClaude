package com.arielvino.heyclaude

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Stub recognition service. The voice-interaction metadata's `recognitionService`
 * attribute is required by the framework parser, so a valid [RecognitionService] must
 * exist for HeyClaude to be selectable as the assistant — but HeyClaude does its real
 * speech-to-text through [SpeechToText] (a plain `SpeechRecognizer`) inside the session,
 * not through this service. So every call here just reports a client error rather than
 * pretending to recognize anything.
 */
class HeyClaudeRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Not a real recognizer — recognition happens in the session via SpeechRecognizer.
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
