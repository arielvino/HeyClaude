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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
 * a Compose [NavHost], starting at the talk screen:
 *  - [TalkScreen] ("talk") — home; a centered push-to-talk mic that dictates via
 *    [SpeechToText] / `RECORD_AUDIO` (1d) and sends to /v1/messages, showing the
 *    transcript + reply (1c). A collapsible drawer holds Settings (+ future entries).
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
    // App-level state, hoisted above the NavHost so all destinations agree:
    //  - themeMode drives the theme (changing it recomposes the whole tree),
    //  - keySaved gates Send on the talk screen and is updated from Settings.
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var keySaved by remember { mutableStateOf(keyStore.hasKey()) }
    var talkback by remember { mutableStateOf(settings.talkback) }

    HeyClaudeTheme(themeMode) {
        // App is edge-to-edge (forced on API 35+); the Surface fills the whole
        // window so its background paints behind the system bars, while
        // safeDrawingPadding keeps content out from under them — applied once
        // here so every NavHost destination inherits it.
        Surface(modifier = Modifier.fillMaxSize()) {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "talk",
                modifier = Modifier.safeDrawingPadding(),
            ) {
                composable("talk") {
                    TalkScreen(
                        client = client,
                        keySaved = keySaved,
                        talkback = talkback,
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
                        talkback = talkback,
                        onTalkbackChange = {
                            talkback = it
                            settings.talkback = it
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
private fun TalkScreen(
    client: AnthropicClient,
    keySaved: Boolean,
    talkback: Boolean,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val stt = remember { SpeechToText(context) }
    val tts = remember { Tts(context) }
    DisposableEffect(Unit) {
        onDispose {
            stt.destroy()
            tts.shutdown()
        }
    }

    var prompt by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }

    // Sends [text] as one turn and shows Claude's reply. Called by the STT auto-send
    // path once the recognizer finalizes the utterance.
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
            if (talkback) tts.speak(reply)
        }
    }

    // Start dictation; partials stream into [prompt] live, then it auto-sends when
    // SpeechRecognizer finalizes the utterance (onResults) — no second tap.
    fun beginListening() {
        listening = true
        prompt = ""
        reply = ""
        tts.stop() // barge-in: don't talk over the user
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

    val drawerState = rememberDrawerState(DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "HeyClaude",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                // Future menu entries (history, tools, …) go here.
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar: the ☰ button that opens the collapsible menu.
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { scope.launch { drawerState.open() } }) {
                    Text("☰ Menu")
                }
            }

            // Centered, circular push-to-talk button — the screen's focal point.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = { onMicTap() },
                    shape = CircleShape,
                    enabled = stt.isAvailable && !loading,
                    modifier = Modifier.size(140.dp),
                ) {
                    Text(
                        text = if (listening) "■" else "🎤",
                        style = MaterialTheme.typography.headlineLarge,
                    )
                }
                Text(
                    text = when {
                        listening -> "Listening…"
                        loading -> "Thinking…"
                        else -> "Tap to talk"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            // Resolved transcript + Claude's reply (scrolls if long).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!keySaved) {
                    Text(
                        "No API key yet — open the ☰ menu → Settings to add one.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (prompt.isNotBlank()) {
                    Text("You", style = MaterialTheme.typography.titleSmall)
                    Text(prompt, style = MaterialTheme.typography.bodyLarge)
                }
                if (reply.isNotEmpty()) {
                    Text("Claude", style = MaterialTheme.typography.titleSmall)
                    Text(reply, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
