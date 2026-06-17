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
 * Minimal Anthropic Messages API client — a single non-streaming POST to
 * /v1/messages. This is the spine of the whole app (PROJECT_BRIEF.md §5); the
 * rest of the pipeline exists to produce a string for [sendMessage] and consume
 * its result.
 *
 * Raw OkHttp is used deliberately for the skeleton (one POST, three headers,
 * org.json for the body) to keep the APK lean and the wire format visible. The
 * official Java SDK is the documented alternative once the tool loop grows.
 *
 * The tool-use loop, streaming, and history are NOT implemented yet — those are
 * later build-order steps.
 */
class AnthropicClient(
    private val apiKeyProvider: () -> String?,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Sends [userText] as a single user turn and returns Claude's final text reply.
     *
     * @throws AnthropicException on a missing key or a non-2xx response (message
     *   carries the HTTP status and the API's error text).
     */
    suspend fun sendMessage(
        userText: String,
        model: String = DEFAULT_MODEL,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim()
        if (apiKey.isNullOrEmpty()) {
            throw AnthropicException("No API key set. Paste your Anthropic key in Settings.")
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", userText),
                ),
            )
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
            firstTextBlock(raw)
        }
    }

    /** Pulls the first `content[].type == "text"` block out of a Messages response. */
    private fun firstTextBlock(raw: String): String {
        val content = JSONObject(raw).optJSONArray("content") ?: return ""
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") return block.optString("text")
        }
        return ""
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

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

class AnthropicException(message: String) : Exception(message)
