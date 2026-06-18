package com.arielvino.heyclaude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Anthropic Messages API client — non-streaming POSTs to /v1/messages, with the
 * tool-use loop (PROJECT_BRIEF.md §5). This is the spine of the whole app; the rest
 * of the pipeline exists to produce a string for [sendMessage] and consume its result.
 *
 * Raw OkHttp + org.json is used deliberately for the skeleton (one POST, three
 * headers, hand-rolled loop) to keep the APK lean and the wire format visible. The
 * official Java SDK is the documented alternative once the loop grows.
 *
 * The loop: send `messages` + the cached `system` prompt + the [ToolKit] `tools`.
 * While the model stops with `tool_use`, run each client tool, append a `tool_result`
 * turn, and re-POST until it stops with a final text answer. Server tools (web search)
 * are run by Anthropic inside a single response, so they need no round-trip here.
 *
 * Streaming and multi-turn history are still later build-order steps — each call is a
 * fresh single-user-turn conversation.
 */
class AnthropicClient(
    private val apiKeyProvider: () -> String?,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Sends [userText] as a single user turn, running the tool loop to completion, and
     * returns Claude's final spoken text.
     *
     * @param toolKit the tools to offer this turn (client + server); null/empty means
     *   a plain chat turn with no tools.
     * @param system the system prompt; sent as a cache-controlled block so the static
     *   prefix (system + tools) is cached across turns (brief §8).
     * @throws AnthropicException on a missing key or a non-2xx response (message
     *   carries the HTTP status and the API's error text).
     */
    suspend fun sendMessage(
        userText: String,
        toolKit: ToolKit? = null,
        system: String? = null,
        model: String = DEFAULT_MODEL,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim()
        if (apiKey.isNullOrEmpty()) {
            throw AnthropicException("No API key set. Paste your Anthropic key in Settings.")
        }

        val messages = JSONArray().put(textMessage("user", userText))

        // Bounded tool loop: each round POSTs, and either runs tools and continues, or
        // returns the final text. If we exhaust the rounds without a final answer the
        // tail string below is returned (MAX_TOOL_ROUNDS guards a runaway loop).
        repeat(MAX_TOOL_ROUNDS) {
            val response = postOnce(apiKey, messages, toolKit, system, model, maxTokens)
            val content = response.optJSONArray("content") ?: JSONArray()

            when (response.optString("stop_reason")) {
                // Claude wants a client tool run: echo its turn back, then a tool_result turn.
                "tool_use" -> {
                    messages.put(assistantMessage(content))
                    messages.put(toolResultsMessage(content, toolKit))
                }
                // A long server-tool turn was paused; resend the assistant turn to continue.
                "pause_turn" -> messages.put(assistantMessage(content))
                // end_turn / max_tokens / stop_sequence → final answer.
                else -> return@withContext concatText(content)
            }
        }
        "Sorry — that took too many steps to work out."
    }

    /** One POST round; returns the parsed response JSON or throws on a non-2xx. */
    private fun postOnce(
        apiKey: String,
        messages: JSONArray,
        toolKit: ToolKit?,
        system: String?,
        model: String,
        maxTokens: Int,
    ): JSONObject {
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            if (system != null) put("system", systemBlocks(system))
            if (toolKit != null && toolKit.hasTools) put("tools", toolKit.definitions)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(MESSAGES_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) {
                throw AnthropicException("HTTP ${response.code}: ${extractError(raw)}")
            }
            return JSONObject(raw)
        }
    }

    /**
     * Wraps the system prompt in a single cache-controlled text block. The cache
     * breakpoint here covers everything before it in the canonical order (tools, then
     * system), so the static prefix is reused on later turns at ~0.1× cost. Note the
     * per-model minimum cacheable prefix (Haiku 4.5 = 4096 tokens, Sonnet 4.6 = 2048):
     * below it caching silently no-ops, which is harmless.
     */
    private fun systemBlocks(system: String): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", system)
                .put("cache_control", JSONObject().put("type", "ephemeral")),
        )

    /** Echoes an assistant turn back verbatim (its full content array, tool_use included). */
    private fun assistantMessage(content: JSONArray): JSONObject =
        JSONObject().put("role", "assistant").put("content", content)

    /** Runs every tool_use block in [content] and packs the results into one user turn. */
    private fun toolResultsMessage(content: JSONArray, toolKit: ToolKit?): JSONObject {
        val results = JSONArray()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") != "tool_use") continue
            val input = block.optJSONObject("input") ?: JSONObject()
            val result = toolKit?.execute(block.optString("name"), input)
                ?: "Error: no tools available."
            results.put(
                JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", block.optString("id"))
                    .put("content", result),
            )
        }
        return JSONObject().put("role", "user").put("content", results)
    }

    private fun textMessage(role: String, text: String): JSONObject =
        JSONObject().put("role", role).put("content", text)

    /** Joins every `text` block of a response's content into the final answer. */
    private fun concatText(content: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    /** Best-effort extraction of `error.message` from an error response body. */
    private fun extractError(raw: String): String =
        runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: raw.ifEmpty { "(empty response body)" }

    companion object {
        const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"

        /** Cheap default for trivial turns; escalate to sonnet/opus later (brief §5). */
        const val DEFAULT_MODEL = "claude-haiku-4-5"

        /** Short replies keep voice latency and cost low; may truncate (brief §8). */
        const val DEFAULT_MAX_TOKENS = 250

        /** Safety cap on tool_use round-trips per turn, to bound a runaway loop. */
        const val MAX_TOOL_ROUNDS = 6

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

class AnthropicException(message: String) : Exception(message)
