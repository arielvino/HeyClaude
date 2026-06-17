# Project Brief — "Hey Claude": Claude-Native Device-Action Assistant (Android)

> Read this at the start of a build session. It's the vision, architecture, and
> build order for the app. The session **workflow** (build/lint, cp-deploy to
> ariel's checkout, branch handling) lives in `.claude/skills/heyclaude/SKILL.md`.
>
> Repo origin: `https://github.com/arielvino/HeyClaude`
> Package: `com.arielvino.heyclaude` · minSdk 29 · targetSdk 36 · Kotlin/Gradle

---

## 0. One-line thesis

A personal, **Claude-native** Android voice assistant that doesn't just answer —
it *acts* on the device (alarms, timers, apps, calendar, …) via Anthropic-native
tool-use, grounded in the user's own structured data. **App-only** (no server),
**Anthropic-only** (no model-swap layer), by deliberate decision.

## 1. Three non-negotiable design decisions

1. **Claude-native device actions, not generic chat.** The whole point is deep
   Anthropic tool-use + long context + prompt caching. Do the device-action thing
   generic clients punt on.
2. **Single provider: Anthropic.** Not model-agnostic. Other providers speak a
   different API shape (`Authorization: Bearer` + `/v1/chat/completions` vs.
   `x-api-key` + `/v1/messages`); supporting them means an adapter layer for no
   payoff. Target `/v1/messages` directly and use it fully. The model is the one
   hard dependency; everything else stays swappable.
3. **App-only, no infrastructure.** The entire pipeline runs on the phone. The
   only network egress is the Anthropic API call itself (plus any tool that needs
   the network, e.g. web search).

Secondary priorities: personal-context grounding (knows the user's projects,
schedules, notes); open-source/offline **speech** layers (local STT/TTS — only
the model turn leaves the device); low, predictable cost.

## 2. Why this exists (and why not SpeakGPT)

**SpeakGPT** (Apache-2.0, mature) already owns the "generic LLM voice client"
niche: local key storage, custom providers, model switching, Whisper/Google
voice input, image gen, default-Assistant registration, share-sheet hooks,
generic function-calling. It's a hard skip here for two reasons:

1. It speaks the **OpenAI API shape**. Pointed at `api.anthropic.com` it can't
   even list models (wrong auth/headers) and chat calls fail (`/v1/chat/completions`
   vs `/v1/messages`). Claude only works in it *via OpenRouter* — a dependency
   this project rejects.
2. It **does no device actions** — its own roadmap lists "Device routines (set
   alarm, open app)" as unchecked.

So it's neither a faithful preview nor the target product. It confirms the gap.
**No evaluation step — go straight to building.**

## 3. Invocation reality (read before designing the trigger)

A background spoken wake word ("Hey Claude") is **not available** to a normal
app — Android reserves hotword activation for privileged system apps (ChatGPT
hit the same wall). So:

- **Default-assistant launch (primary).** Register as the device digital
  assistant; invoke via long-press/gesture straight into voice mode. The
  realistic hands-free-ish trigger.
- **In-app tap-to-talk (always works).** A button. This is the skeleton.
- **Self-run wake word (optional, costly).** A foreground service running
  openWakeWord/Vosk can listen for a custom phrase, but carries a persistent
  notification + battery drain. Opt-in power-user mode. **Do not build the core
  UX assuming a real hotword exists.**

## 4. Pipeline (everything on-device except the API call)

```
 INVOCATION ──▶ AUDIO CAPTURE ──▶ STT ──▶ VAD(end-of-turn)
 (gesture/tap)                                    │ text
                                                  ▼
                       ORCHESTRATOR  (context + Haiku/Sonnet routing + tool loop)
                         │  ▲                       │ tool_use
                  final  │  │ tool_result           ▼
                  text   │  └────────  TOOL DISPATCH (alarm/timer, open app,
                         ▼                          calendar, web, personal-context)
                        TTS                          │
                                          AnthropicClient (key from Keystore)
                                                  │ HTTPS
                                                  ▼
                                   POST api.anthropic.com/v1/messages
```

Swap contract: invocation emits a trigger → STT returns text → orchestrator
returns text → TTS consumes text. The model sits **outside** this contract
(hard Anthropic dependency).

## 5. The Anthropic integration (the one that matters)

- **Endpoint:** `POST https://api.anthropic.com/v1/messages`
- **Headers:** `x-api-key: <key>` (NOT `Authorization: Bearer`),
  `anthropic-version: 2023-06-01`, `content-type: application/json`
- **Minimal body:** `{"model": "...", "max_tokens": 250, "messages": [{"role":"user","content":"..."}]}`
- **Tool-use (core mechanic):** add a `tools` array (name, description,
  JSON-schema `input_schema`). Claude returns `tool_use` blocks → execute →
  reply with a `tool_result` (matching `tool_use_id`) → loop until a final text
  answer. Always `json.loads`/parse `input` — never raw-string-match it.
- **Streaming:** add `"stream": true` → SSE. Needs an SSE client (OkHttp-SSE).
  Start non-streaming for the skeleton; add streaming in polish.

### Client: official Java SDK vs. raw HTTP — decide early

Kotlin uses the **official Java SDK** (`com.anthropic:anthropic-java`). It gives
typed models, a beta tool-runner that drives the `tool_use`/`tool_result` loop
for you, streaming helpers, and structured outputs — less code to get the loop
right. The trade-off is APK size and transitive deps.

**Recommendation:** start with raw **OkHttp** for `AnthropicClient` (one POST,
three headers, hand-rolled tool loop) to keep the skeleton tiny and the wire
format visible while learning it; reassess the SDK once the tool loop grows
(many tools, streaming, structured outputs) and the SDK's tool-runner starts
paying for itself. Either way it's an internal detail behind `AnthropicClient` —
not a swap point exposed to the rest of the app.

### Models (hardcode — no models-list fetch; single provider)

| Use | Model ID | Context | $/MTok in·out | Notes |
|---|---|---|---|---|
| Simple/cheap turns | `claude-haiku-4-5` | 200K | $1 · $5 | Default for trivial intents |
| Complex turns | `claude-sonnet-4-6` | 1M | $3 · $15 | Escalate when needed |
| Hardest reasoning (optional) | `claude-opus-4-8` | 1M | $5 · $25 | Reserve for genuinely hard turns |

Routing decided in the orchestrator. **No extended/adaptive thinking on the
conversational path** — it adds latency and bills at output rate; a voice
assistant wants fast, short turns. (Note: don't send `temperature`/`top_p` on
4.x — they 400; steer via prompt.)

## 6. API key handling (no secrets in the repo)

- **Entered in the UI.** Settings screen has an API-key field; paste once.
- **Stored in the Android Keystore** via `EncryptedSharedPreferences`. Survives
  `adb` reinstalls / updates — enter once per device.
- **Never committed.** Repo ships a placeholder only, with a README note to
  paste your own in Settings on first run. No `BuildConfig`/`local.properties`
  key — the UI field is the single source of truth.
- Runtime: read key from Keystore → if empty, prompt → put in `x-api-key`.

## 7. One interaction (workflow)

Idle → invoke (gesture/tap, capture begins) → listen (VAD ends turn, STT
finalizes) → think (orchestrator assembles cached system prompt + tool defs +
relevant personal context + trimmed history + utterance; routes Haiku/Sonnet;
POSTs) → act (dispatch `tool_use` → `tool_result`, loop to final text) → speak
(segment final text on punctuation, pipe sentence-by-sentence to TTS so playback
starts before generation finishes) → idle (history updated; optional barge-in).

## 8. Cost discipline

Local STT+TTS = free; only the model turn bills. **Cache** the static system
prompt + tool definitions (cache reads ≈0.1×; note per-model minimum cacheable
prefix — Haiku 4.5 is 4096 tokens, Sonnet 4.6 is 2048, so the cached prefix must
exceed that or it silently won't cache). **Trim** history hard (last ~3–4 turns).
**Cap** `max_tokens` low (~250; accept occasional truncation for short replies).
**Route** trivial intents to Haiku. Target: single-digit dollars/month for
personal use.

## 9. Build order (Android-first)

1. **Tap-to-talk skeleton** — one screen, one button: record → STT → Claude →
   TTS. No gestures, no wake word, no service. Proves the full loop.
   *Progress (2026-06-17): 1a–1d done; **1e is the current target.***
   - 1a. ✅ Single Compose screen + `INTERNET`; `RECORD_AUDIO` + recognizer
         `<queries>` added in 1d.
   - 1b. ✅ Settings screen → API key → `EncryptedSharedPreferences`.
   - 1c. ✅ `AnthropicClient`: non-streaming POST to `/v1/messages` with correct
         headers; button sends typed text → reply on screen (proves network + key).
   - 1d. ✅ STT via `SpeechRecognizer` (`SpeechToText.kt`): mic button →
         `RECORD_AUDIO` runtime grant → dictation fills the message field (partial
         results stream live) → existing Send path POSTs it to Claude.
   - 1e. ⬅️ **NEXT** — Add TTS (`TextToSpeech`): speak Claude's reply aloud so a
         full tap → speak → hear loop closes.
   *Open UX fixes (reported 2026-06-17, 1d works "pretty much"):*
   *(a) ✅ **Auto-send on end-of-speech** — the transcript routes through the shared
   `sendToClaude()` (also used by the Send button), so dictation fires the turn with
   no second tap. `SpeechToText` keeps the last partial and uses it as the result
   when the recognizer ends with an empty final or NO_MATCH/SPEECH_TIMEOUT (common
   on-device), so end-of-speech reliably sends whatever was heard.*
   *(b) **Render the reply as Markdown**, not raw text — the model returns
   Markdown and it currently shows literally (needs a Markdown composable).*
2. **Tool-use loop (the differentiator)** — `tool_use`/`tool_result` round-trip;
   first tools: set alarm/timer + open app.
3. **Default-assistant integration** — register as assistant; gesture → voice mode.
4. **Personal-context tools** — wire in journal/tasks/schedules grounding.
5. **Polish** — streaming (SSE), barge-in, Haiku/Sonnet routing; swap
   `SpeechRecognizer` → whisper.cpp, `TextToSpeech` → Piper; add optional
   wake-word foreground-service mode.

## 10. Component swap table

| Layer | Default (start here) | OSS/offline upgrade |
|---|---|---|
| Invocation | Default-assistant gesture + in-app tap | Self-run openWakeWord/Vosk service (opt-in) |
| Audio capture | `AudioRecord` | — (always-on mic needs `FOREGROUND_SERVICE_MICROPHONE`, wake-word mode only) |
| VAD | Silero VAD | WebRTC VAD |
| STT | Android `SpeechRecognizer` | whisper.cpp on-device |
| Orchestrator | Custom Kotlin (history, routing, tool loop, context) | — |
| **Model** | **Anthropic Claude — fixed, no swap** | — |
| Tools | Alarm/timer, open app, web search | Calendar, SMS, settings; personal-context tools |
| TTS | Android `TextToSpeech` | Piper (neural) |

## 11. Constraints checklist

- Anthropic only. No other providers, no model-swap, no OpenAI-compat layer.
- No real API key in the repo. Placeholder only; key in UI → Keystore. Header is
  `x-api-key` + `anthropic-version`.
- App-only. No proxy, gateway, or server.
- Don't assume a real background hotword. Core UX is gesture/tap.
- Request per-tool permissions (alarm, calendar, SMS, …) lazily, on first use.
- Keep spoken replies short — long TTS is the worst UX.
