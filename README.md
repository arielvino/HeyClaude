# HeyClaude

A Claude-native Android voice assistant that performs real device actions via
Anthropic-native tool-use. App-only, Anthropic-only. See
[`.claude/PROJECT_BRIEF.md`](.claude/PROJECT_BRIEF.md) for the full vision,
architecture, and build order.

## Status

Tap-to-talk skeleton (build-order steps 1a–1c): one screen that stores your API
key and sends a message to `/v1/messages`, proving the network path. STT, TTS,
the tool-use loop, and gesture invocation come next.

## First run

1. Build and install the `debug` variant (Android Studio, or `./gradlew installDebug`).
2. Open the app, paste your **Anthropic API key** into the Settings field, tap
   **Save key**. The key is stored encrypted in the Android Keystore — it is
   **never** committed to the repo or written to logs.
3. Tap **Send to Claude**. You should see a one-line reply.

No API key ships with the app; you supply your own from
[the Anthropic Console](https://console.anthropic.com/).
