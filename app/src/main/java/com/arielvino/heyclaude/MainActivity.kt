package com.arielvino.heyclaude

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

/**
 * Tap-to-talk skeleton (PROJECT_BRIEF.md §9, steps 1a–1d). Two destinations behind
 * a Compose [NavHost]:
 *  - [MainScreen] ("main") — dictate via [SpeechToText] / `RECORD_AUDIO` (1d) and
 *    send to /v1/messages, showing the reply (1c).
 *  - [SettingsScreen] ("settings") — Anthropic API key (1b) and the theme choice.
 *
 * TTS (1e) and real invocation come in later steps; for now you tap the mic (or
 * type) and tap send.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val keyStore = ApiKeyStore(this)
        val settings = SettingsStore(this)
        val client = AnthropicClient(apiKeyProvider = { keyStore.apiKey })
        setContent {
            HeyClaudeApp(keyStore = keyStore, settings = settings, client = client)
        }
    }
}

@Composable
private fun HeyClaudeApp(
    keyStore: ApiKeyStore,
    settings: SettingsStore,
    client: AnthropicClient,
) {
    // App-level state, hoisted above the NavHost so both destinations agree:
    //  - themeMode drives the theme (changing it recomposes the whole tree),
    //  - keySaved gates Send on the talk screen and is updated from Settings.
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var keySaved by remember { mutableStateOf(keyStore.hasKey()) }

    HeyClaudeTheme(themeMode) {
        // App is edge-to-edge (forced on API 35+); the Surface fills the whole
        // window so its background paints behind the system bars, while
        // safeDrawingPadding keeps content out from under them — applied once
        // here so every NavHost destination inherits it.
        Surface(modifier = Modifier.fillMaxSize()) {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "main",
                modifier = Modifier.safeDrawingPadding(),
            ) {
                composable("main") {
                    MainScreen(
                        client = client,
                        keySaved = keySaved,
                        onOpenSettings = { navController.navigate("settings") },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        keyStore = keyStore,
                        themeMode = themeMode,
                        onThemeModeChange = {
                            themeMode = it
                            settings.themeMode = it
                        },
                        onKeySavedChange = { keySaved = it },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    client: AnthropicClient,
    keySaved: Boolean,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val stt = remember { SpeechToText(context) }
    DisposableEffect(Unit) {
        onDispose { stt.destroy() }
    }

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("HeyClaude", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onOpenSettings) { Text("⚙ Settings") }
        }

        if (!keySaved) {
            Text(
                "No API key yet — open Settings to add one.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

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
