package com.arielvino.heyclaude

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speaks Claude's reply aloud (PROJECT_BRIEF.md §9, step 1e) via Android's
 * [TextToSpeech] — the TTS link of the pipeline (§4), default per the swap table
 * (§10); Piper is the later neural upgrade. Gated by the "Talkback" sound setting.
 *
 * Construct on the main thread and [shutdown] when the owning screen leaves
 * composition. Engine init is async; [speak] is a no-op until it finishes.
 */
class Tts(context: Context) {

    // Set on the TTS init thread, read on the main thread — keep it visible across both.
    @Volatile
    private var ready = false

    private val engine = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) ready = true
    }

    /** Speaks [text], interrupting any current utterance. No-op until the engine is ready. */
    fun speak(text: String) {
        if (ready && text.isNotBlank()) {
            engine.setLanguage(Locale.getDefault())
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "heyclaude-reply")
        }
    }

    /** Stops any current speech without releasing the engine (e.g. barge-in). */
    fun stop() {
        engine.stop()
    }

    /** Releases the engine. Call from the screen's onDispose. */
    fun shutdown() {
        engine.stop()
        engine.shutdown()
    }
}
