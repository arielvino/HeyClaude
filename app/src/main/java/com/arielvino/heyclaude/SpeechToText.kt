package com.arielvino.heyclaude

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Thin wrapper over Android's on-device [SpeechRecognizer] — the STT link of the
 * pipeline (PROJECT_BRIEF.md §4, build step 1d). Turns a spoken utterance into a
 * transcript string; everything downstream (orchestrator, AnthropicClient) just
 * consumes that string, exactly as the typed path does.
 *
 * `SpeechRecognizer` is the default per the swap table (§10); whisper.cpp is the
 * later offline upgrade. Must be constructed and driven on the main thread, and
 * [destroy]ed when the owning screen leaves composition.
 *
 * Requires the `RECORD_AUDIO` permission — the caller is responsible for holding
 * the grant before calling [start]; [RecognitionListener.onError] surfaces a
 * missing grant as [SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS].
 */
class SpeechToText(context: Context) {

    private val available = SpeechRecognizer.isRecognitionAvailable(context)
    private val recognizer: SpeechRecognizer? =
        if (available) SpeechRecognizer.createSpeechRecognizer(context) else null

    /** False when no recognition service is installed — caller should disable the mic. */
    val isAvailable: Boolean get() = available

    /**
     * Starts listening. [onPartial] streams interim hypotheses (for live display);
     * [onResult] delivers the final transcript; [onError] carries a human-readable
     * reason; [onDone] always fires once when the turn ends (result or error), so
     * the caller can clear its "listening" state in one place.
     */
    fun start(
        onPartial: (String) -> Unit = {},
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        val r = recognizer ?: run {
            onError("Speech recognition isn't available on this device.")
            onDone()
            return
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                firstHypothesis(partialResults)?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                onResult(firstHypothesis(results).orEmpty())
                onDone()
            }

            override fun onError(error: Int) {
                onError(errorText(error))
                onDone()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        r.startListening(intent)
    }

    /** Stops capture and forces the recognizer to finalize the current utterance. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Releases the recognizer. Call from the screen's onDispose. */
    fun destroy() {
        recognizer?.destroy()
    }

    private fun firstHypothesis(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
        SpeechRecognizer.ERROR_NETWORK -> "network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "didn't catch that — try again"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer is busy"
        SpeechRecognizer.ERROR_SERVER -> "recognition server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
        else -> "speech error ($code)"
    }
}
