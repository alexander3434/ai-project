package com.aiturbo.log

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure renderers for the trace lines: no logging side effects, every result is a
 * single line (newlines are escaped) so one chain entry stays one log line.
 */

/** Renders the request messages as `[system: "…", user: "…"]`. */
internal fun renderMessages(messages: List<Message>): String =
    messages.joinToString(prefix = "[", postfix = "]", separator = ", ") { message ->
        "${message.role.name.lowercase()}: \"${oneLine(message.textContent())}\""
    }

/**
 * Renders the model's answer as `text="…" tool_calls=[{"name":…,"args":…}]`.
 * `args` is the JSON string the model sent for the tool call.
 */
internal fun renderAssistant(response: Message.Assistant): String {
    val text = response.parts
        .filterIsInstance<MessagePart.Text>()
        .joinToString("\n") { it.text }
    val toolCalls = response.parts
        .filterIsInstance<MessagePart.Tool.Call>()
        .joinToString(prefix = "[", postfix = "]", separator = ",") { call ->
            buildJsonObject {
                put("name", call.tool)
                put("args", call.args)
            }.toString()
        }
    return "text=\"${oneLine(text)}\" tool_calls=$toolCalls"
}

/** Compact one-line JSON for a JSON object. */
internal fun renderJson(obj: JsonObject): String = obj.toString()

/** Collapses a possibly multi-line value into one escaped line. */
private fun oneLine(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\r\n", "\\n")
    .replace("\n", "\\n")
    .replace("\r", "\\n")
    .replace("\"", "\\\"")
