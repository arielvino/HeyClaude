package com.arielvino.heyclaude

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything that turns the user's enabled [Capability] set into Anthropic tool-use
 * (PROJECT_BRIEF.md §5, build step 2): the `tools` array we send, the device-action
 * executors that run a `tool_use` and produce a `tool_result`, and the cached system
 * prompt that tells the model how/when to use them.
 *
 * Two flavours of tool live here:
 *  - **Client tools** (`set_alarm`, `set_timer`, `open_app`) — a JSON-schema
 *    definition we send to Claude *and* a Kotlin executor that runs on the device.
 *    Claude pauses (`stop_reason == "tool_use"`), [AnthropicClient] calls
 *    [ToolKit.execute], and replies with the result string.
 *  - **Server tools** (`web_search`) — a typed definition Anthropic runs entirely
 *    server-side. We only include it in the array; there is nothing to execute
 *    locally. (Sending this definition is what lets the model actually search the
 *    web instead of refusing — without it in `tools`, Claude has no web access.)
 *
 * Which tools are sent is decided per-turn from the effective capability set
 * (app-enabled AND permission-satisfied), so a disabled capability simply isn't
 * offered to the model.
 */

/** The static system prompt, sent (cached) on every turn. See [AnthropicClient]. */
const val SYSTEM_PROMPT: String = """You are HeyClaude, a voice assistant running on the user's Android phone.

Your replies are read aloud by text-to-speech, so:
- Keep answers short and conversational — one or two sentences whenever possible.
- Write plain spoken prose: no markdown, headings, bullet points, code blocks, or emoji.
- Say things the way you'd speak them aloud (for example "7:30 a.m.", not a table).

You can act on the device through tools. Prefer performing the action over describing how to do it:
- set_alarm — set an alarm at a clock time (e.g. "wake me at 7").
- set_timer — start a countdown timer (e.g. "remind me in 10 minutes", "5 minute timer").
- open_app — launch an installed app by name (e.g. "open Spotify").
- web_search — use this whenever the user asks about current events, live data, or anything that may be out of date or that you're unsure of. Never claim you can't browse the web; search instead.

After a tool runs, confirm what happened in one brief spoken sentence. If a tool reports an error, say so plainly and suggest what the user can do."""

/**
 * The tool definitions to send plus the executors for the client tools. Built fresh
 * per turn by [buildToolKit] from the current effective capability set.
 */
class ToolKit(
    /** The `tools` array for the request body (client + server tool definitions). */
    val definitions: JSONArray,
    private val executors: Map<String, (JSONObject) -> String>,
) {
    /** True when there is at least one tool to offer the model. */
    val hasTools: Boolean get() = definitions.length() > 0

    /**
     * Runs the client tool [name] with Claude's parsed [input] and returns the
     * `tool_result` text. Server tools (web_search) never reach here — Anthropic runs
     * them — so an unknown name means a genuine mismatch.
     */
    fun execute(name: String, input: JSONObject): String =
        executors[name]?.invoke(input) ?: "Error: unknown tool \"$name\"."
}

/**
 * Assembles the [ToolKit] for one turn: a tool is included only if its capability id
 * is in [enabledCapabilityIds] (the app-enabled AND permission-satisfied set computed
 * by the caller). Adding a capability's tools is a single branch here.
 */
fun buildToolKit(actions: DeviceActions, enabledCapabilityIds: Set<String>): ToolKit {
    val defs = JSONArray()
    val executors = mutableMapOf<String, (JSONObject) -> String>()

    if ("alarm" in enabledCapabilityIds) {
        defs.put(setAlarmDef())
        defs.put(setTimerDef())
        executors["set_alarm"] = { input ->
            actions.setAlarm(
                hour = input.optInt("hour", -1),
                minute = input.optInt("minute", 0),
                message = input.optStringOrNull("message"),
            )
        }
        executors["set_timer"] = { input ->
            actions.setTimer(
                seconds = input.optInt("seconds", -1),
                message = input.optStringOrNull("message"),
            )
        }
    }
    if ("open_app" in enabledCapabilityIds) {
        defs.put(openAppDef())
        executors["open_app"] = { input -> actions.openApp(input.optString("app_name")) }
    }
    if ("web_search" in enabledCapabilityIds) {
        // Server-side tool: definition only, no local executor.
        defs.put(webSearchDef())
    }

    return ToolKit(defs, executors)
}

// --- Tool definitions (JSON schema sent to Claude) -------------------------------

private fun setAlarmDef(): JSONObject = toolDef(
    name = "set_alarm",
    description = "Set an alarm at a specific clock time (24-hour). Use for requests like " +
        "\"wake me at 7\" or \"alarm for 6:30 in the morning\".",
    inputSchema = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject()
                .put("hour", intProp("Hour in 24-hour format, 0-23.", min = 0, max = 23))
                .put("minute", intProp("Minute, 0-59.", min = 0, max = 59))
                .put("message", strProp("Optional label spoken/shown for the alarm.")),
        )
        put("required", JSONArray().put("hour").put("minute"))
    },
)

private fun setTimerDef(): JSONObject = toolDef(
    name = "set_timer",
    description = "Start a countdown timer for a duration. Use for \"remind me in 10 minutes\", " +
        "\"set a 5 minute timer\". Convert the spoken duration to total seconds.",
    inputSchema = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject()
                .put("seconds", intProp("Total timer length in seconds.", min = 1))
                .put("message", strProp("Optional label for the timer.")),
        )
        put("required", JSONArray().put("seconds"))
    },
)

private fun openAppDef(): JSONObject = toolDef(
    name = "open_app",
    description = "Open/launch an installed app on the phone by its display name, " +
        "e.g. \"open Spotify\", \"launch the camera\".",
    inputSchema = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().put("app_name", strProp("The app's display name, as the user said it.")),
        )
        put("required", JSONArray().put("app_name"))
    },
)

/**
 * Anthropic's server-side web search tool. Its mere presence in `tools` is what gives
 * the model web access; it is run server-side, so there's no client executor and no
 * tool_result round-trip. `web_search_20250305` is generally available — no beta header.
 */
private fun webSearchDef(): JSONObject = JSONObject()
    .put("type", "web_search_20250305")
    .put("name", "web_search")
    .put("max_uses", 5)

private fun toolDef(name: String, description: String, inputSchema: JSONObject): JSONObject =
    JSONObject()
        .put("name", name)
        .put("description", description)
        .put("input_schema", inputSchema)

private fun intProp(description: String, min: Int? = null, max: Int? = null): JSONObject =
    JSONObject().put("type", "integer").put("description", description).apply {
        if (min != null) put("minimum", min)
        if (max != null) put("maximum", max)
    }

private fun strProp(description: String): JSONObject =
    JSONObject().put("type", "string").put("description", description)

/** org.json returns "" for a missing/null string; treat blank as absent. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null

/**
 * Runs the client tools as real Android actions via implicit intents — no runtime
 * permissions needed for this seed set (AlarmClock intents and launching an app are
 * install-time concerns; see [Capabilities]). Each method returns the `tool_result`
 * text Claude sees, so it must read as a short factual outcome.
 *
 * [startActivity] is invoked off the main thread (the tool loop runs on
 * Dispatchers.IO); that's permitted, and the NEW_TASK flag is required because the
 * launching context here is not necessarily an Activity. Resolve failures surface
 * synchronously as [ActivityNotFoundException], which we turn into a spoken-friendly
 * error string rather than crashing the turn.
 */
class DeviceActions(context: Context) {

    private val appContext = context.applicationContext

    fun setAlarm(hour: Int, minute: Int, message: String?): String {
        if (hour !in 0..23 || minute !in 0..59) {
            return "I need a valid time between 00:00 and 23:59 to set the alarm."
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (!message.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Alarm set for %02d:%02d.".format(hour, minute)
        } catch (e: ActivityNotFoundException) {
            "There's no clock app available to set the alarm."
        }
    }

    fun setTimer(seconds: Int, message: String?): String {
        if (seconds <= 0) return "I need a positive duration to start a timer."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (!message.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Timer set for ${describeDuration(seconds)}."
        } catch (e: ActivityNotFoundException) {
            "There's no clock app available to set the timer."
        }
    }

    fun openApp(appName: String): String {
        val query = appName.trim()
        if (query.isEmpty()) return "Tell me which app to open."

        val pm = appContext.packageManager
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        )
        val needle = query.lowercase()
        // Prefer an exact label match, then a substring match.
        val match = launchers.firstOrNull { it.loadLabel(pm).toString().equals(query, ignoreCase = true) }
            ?: launchers.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(needle) }
            ?: return "I couldn't find an installed app called \"$query\"."

        val label = match.loadLabel(pm).toString()
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "I found $label but couldn't launch it."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(launch)
            "Opened $label."
        } catch (e: ActivityNotFoundException) {
            "I couldn't open $label."
        }
    }

    /** A spoken-friendly duration, e.g. 90 → "1 minute 30 seconds". */
    private fun describeDuration(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val parts = buildList {
            if (minutes > 0) add("$minutes minute${if (minutes == 1) "" else "s"}")
            if (seconds > 0) add("$seconds second${if (seconds == 1) "" else "s"}")
        }
        return if (parts.isEmpty()) "0 seconds" else parts.joinToString(" ")
    }
}
