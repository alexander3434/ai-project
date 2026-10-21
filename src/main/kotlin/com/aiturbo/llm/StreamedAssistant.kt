package com.aiturbo.llm

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame

/**
 * Accumulates the frames of one streamed round into the assembled assistant
 * message, shared by the chat agent (which needs the visible text and the tool
 * calls of the round) and the logging decorator (which needs the `text="…"
 * tool_calls=[…]` rendering for the `stage=deepseek-response` line, D-11).
 *
 * Text comes from [StreamFrame.TextDelta] only — the same rule Koog's own
 * `collectText()` uses — so a trailing [StreamFrame.TextComplete] never
 * double-counts. Tool calls come from [StreamFrame.ToolCallComplete]. Anything
 * else changes no state and `accept` never throws.
 */
class StreamedAssistant {

    private val text = StringBuilder()
    private val toolCalls = mutableListOf<MessagePart.Tool.Call>()

    fun accept(frame: StreamFrame) {
        when (frame) {
            is StreamFrame.TextDelta -> text.append(frame.text)
            is StreamFrame.ToolCallComplete -> toolCalls += MessagePart.Tool.Call(
                id = frame.id,
                tool = frame.name,
                args = frame.content,
            )
            else -> Unit
        }
    }

    /** The visible text accumulated from [StreamFrame.TextDelta] frames. */
    fun text(): String = text.toString()

    /** The assembled assistant message; empty when the stream carried nothing. */
    fun toMessage(): Message.Assistant = Message.Assistant(
        parts = buildList {
            if (text.isNotEmpty()) add(MessagePart.Text(text()))
            addAll(toolCalls)
        },
        metaInfo = ResponseMetaInfo.Empty,
    )
}
