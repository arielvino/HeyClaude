package com.arielvino.heyclaude

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The per-invocation assistant session: HeyClaude's listening UI plus the same
 * speech → Claude (tool-use) → speech pipeline the tap-to-talk [TalkScreen] runs, but
 * hosted in a system-rendered window that can appear over the lock screen.
 *
 * The UI is Jetpack Compose, like the rest of the app. A session window has no Activity,
 * so it provides none of the ViewTree owners Compose needs — this class supplies them
 * itself (it *is* the [LifecycleOwner] / [ViewModelStoreOwner] / [SavedStateRegistryOwner]
 * for its [ComposeView]) and drives the lifecycle from the session callbacks. The
 * composition is then disposed with that lifecycle.
 *
 * Reuses the real building blocks ([SpeechToText], [AnthropicClient], [Tts],
 * [DeviceActions], [buildToolKit], [SYSTEM_PROMPT]) so this path and the in-app path stay
 * behaviourally identical.
 */
class HeyClaudeSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // ViewTree owners for hosting Compose in a non-Activity window. The lifecycle is moved
    // by the session callbacks (onCreate→CREATED, onShow→RESUMED, onDestroy→DESTROYED).
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    // Main-thread scope: SpeechRecognizer callbacks arrive on the main thread and we touch
    // Compose state from here; AnthropicClient hops to IO internally. Cancelled in onDestroy.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var keyStore: ApiKeyStore
    private lateinit var settings: SettingsStore
    private lateinit var capabilityStore: CapabilityStore
    private lateinit var client: AnthropicClient
    private lateinit var deviceActions: DeviceActions
    private lateinit var stt: SpeechToText
    private lateinit var tts: Tts

    // Snapshot-backed UI state, read by the composable below and written by the pipeline.
    private var status by mutableStateOf("")
    private var transcript by mutableStateOf("")
    private var reply by mutableStateOf("")

    private var listening = false
    private var loading = false

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val ctx = context
        keyStore = ApiKeyStore(ctx)
        settings = SettingsStore(ctx)
        capabilityStore = CapabilityStore(ctx)
        client = AnthropicClient(apiKeyProvider = { keyStore.apiKey })
        deviceActions = DeviceActions(ctx)
        stt = SpeechToText(ctx)
        tts = Tts(ctx)
    }

    override fun onCreateContentView(): View =
        ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@HeyClaudeSession)
            setViewTreeViewModelStoreOwner(this@HeyClaudeSession)
            setViewTreeSavedStateRegistryOwner(this@HeyClaudeSession)
            // Tie composition to our lifecycle (disposed in onDestroy), not to window
            // attach/detach — the session may detach/reattach the view.
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HeyClaudeTheme(settings.themeMode) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        SessionContent(status = status, transcript = transcript, reply = reply)
                    }
                }
            }
        }

    // Shown when the assistant is invoked (gesture / power button / lock-screen voice
    // assist). Jump straight into listening — the whole point of a voice assistant.
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        beginListening()
    }

    override fun onHide() {
        stt.stop()
        tts.stop()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        scope.cancel()
        stt.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    private fun beginListening() {
        if (!hasMicPermission()) {
            status = "Microphone permission needed — open HeyClaude to grant it."
            return
        }
        if (!stt.isAvailable) {
            status = "Speech recognition isn't available on this device."
            return
        }
        listening = true
        transcript = ""
        reply = ""
        status = "Listening…"
        tts.stop() // barge-in: don't talk over the user
        stt.start(
            onPartial = { transcript = it },
            onResult = { result ->
                transcript = result
                if (keyStore.hasKey()) {
                    sendToClaude(result)
                } else {
                    status = "No API key yet — open HeyClaude → Settings to add one."
                }
            },
            onError = { msg -> status = "Mic: $msg" },
            onDone = {
                listening = false
                if (status == "Listening…") status = ""
            },
        )
    }

    private fun sendToClaude(text: String) {
        if (text.isBlank() || loading) return
        loading = true
        reply = ""
        status = "Thinking…"
        val toolKit = buildToolKit(deviceActions, effectiveCapabilityIds())
        scope.launch {
            val answer = try {
                client.sendMessage(text, toolKit = toolKit, system = SYSTEM_PROMPT)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            loading = false
            status = ""
            reply = answer
            if (settings.talkback) tts.speak(answer)
        }
    }

    // App-enabled AND permission-satisfied capabilities — identical rule to TalkScreen, so
    // the assistant offers Claude exactly the tools the in-app path would.
    private fun effectiveCapabilityIds(): Set<String> {
        val granted = context.grantedAmong(Capabilities.allRequiredPermissions)
        return Capabilities.ALL
            .filter { capabilityStore.isEnabled(it) && it.permissionSatisfied(granted) }
            .map { it.id }
            .toSet()
    }

    private fun hasMicPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

/** The session's listening readout: status line, the user's transcript, and Claude's reply. */
@Composable
private fun SessionContent(status: String, transcript: String, reply: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("HeyClaude", style = MaterialTheme.typography.headlineMedium)
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
        if (transcript.isNotBlank()) {
            Text("You", style = MaterialTheme.typography.titleSmall)
            Text(transcript, style = MaterialTheme.typography.bodyLarge)
        }
        if (reply.isNotBlank()) {
            Text("Claude", style = MaterialTheme.typography.titleSmall)
            Text(reply, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
