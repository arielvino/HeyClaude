package com.arielvino.heyclaude

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Tap-to-talk skeleton (PROJECT_BRIEF.md §9, steps 1a–1d). One screen that:
 *  - stores the Anthropic API key in the Keystore (1b),
 *  - dictates a message via [SpeechToText] / `RECORD_AUDIO` (1d), and
 *  - sends it to /v1/messages and shows the reply (1c).
 *
 * TTS (1e) and real invocation come in later steps; for now you tap the mic (or
 * type) and tap send.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyStore = ApiKeyStore(this)
        val client = AnthropicClient(apiKeyProvider = { keyStore.apiKey })
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(keyStore = keyStore, client = client)
                }
            }
        }
    }
}

@Composable
private fun MainScreen(keyStore: ApiKeyStore, client: AnthropicClient) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val stt = remember { SpeechToText(context) }
    DisposableEffect(Unit) {
        onDispose { stt.destroy() }
    }

    var keyInput by remember { mutableStateOf(keyStore.apiKey.orEmpty()) }
    var keySaved by remember { mutableStateOf(keyStore.hasKey()) }
    var prompt by remember { mutableStateOf("Hello, Claude — reply in one short sentence.") }
    var reply by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }

    // Sends [text] as one turn and shows Claude's reply. Shared by the Send button
    // and STT auto-send, so both paths behave identically.
    fun sendToClaude(text: String) {
        if (text.isBlank() || loading) return
        loading = true
        reply = ""
        scope.launch {
            reply = try {
                client.sendMessage(text)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            loading = false
        }
    }

    // Start dictation; the transcript replaces the message field, then auto-sends
    // when SpeechRecognizer finalizes the utterance (onResults) — no second tap.
    fun beginListening() {
        listening = true
        prompt = ""
        reply = ""
        stt.start(
            onPartial = { prompt = it },
            onResult = { transcript ->
                prompt = transcript
                if (keySaved) sendToClaude(transcript)
            },
            onError = { msg -> reply = "Mic: $msg" },
            onDone = { listening = false },
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginListening() else reply = "Microphone permission denied."
    }

    fun onMicTap() {
        if (listening) {
            stt.stop()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) beginListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("HeyClaude", style = MaterialTheme.typography.headlineMedium)

        // --- Settings: API key (step 1b) ---
        Text("Settings", style = MaterialTheme.typography.titleMedium)
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
            },
            enabled = keyInput.isNotBlank(),
        ) { Text("Save key") }
        Text(
            text = if (keySaved) "Key saved ✓" else "No key saved",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        // --- Test the model turn (steps 1c–1d) ---
        Text("Test the model turn", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text(if (listening) "Listening…" else "Message") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onMicTap() },
                enabled = stt.isAvailable && !loading,
            ) { Text(if (listening) "■ Stop" else "🎤 Speak") }

            Button(
                onClick = { sendToClaude(prompt) },
                enabled = !loading && !listening && keySaved && prompt.isNotBlank(),
            ) { Text(if (loading) "Sending…" else "Send to Claude") }
        }

        if (reply.isNotEmpty()) {
            Text("Reply", style = MaterialTheme.typography.titleMedium)
            Text(reply, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
