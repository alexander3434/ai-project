package com.aiturbo.chat

import com.aiturbo.plugins.ChatTurnDto
import com.aiturbo.plugins.ChatRequest

/**
 * Pure validation and mapping of a `POST /chat` body — no Ktor, no LLM, no I/O.
 * Every violation is answered as a `400 {"error":…}` before any provider call
 * (FR-09, ASM-06); the exact texts are the route's contract and are asserted
 * verbatim by the tests.
 */
object ChatRequestValidator {

    private const val ALLOWED_ROLES = "user, assistant"

    /** The error text of the first violation, or `null` when the request is valid. */
    fun validate(request: ChatRequest): String? {
        if (request.messages.isEmpty()) {
            return "Field 'messages' must not be empty"
        }
        request.messages.forEachIndexed { index, message ->
            if (roleOf(message) == null) {
                return "Field 'messages[$index].role' must be one of: $ALLOWED_ROLES"
            }
            if (message.content.isBlank()) {
                return "Field 'messages[$index].content' must not be blank"
            }
        }
        if (roleOf(request.messages.last()) != ChatTurnRole.USER) {
            return "The last message must be from the user"
        }
        return null
    }

    /** Maps the DTO list onto the domain model: history is every message but the last one. */
    fun toSession(request: ChatRequest): ChatSession = ChatSession(
        history = request.messages.dropLast(1).map { message ->
            ChatTurn(role = requireNotNull(roleOf(message)), content = message.content.trim())
        },
        message = request.messages.last().content.trim(),
    )

    /** Trimmed, case-insensitive role mapping; `null` for anything but user/assistant (ASM-05). */
    private fun roleOf(message: ChatTurnDto): ChatTurnRole? = when (message.role.trim().lowercase()) {
        "user" -> ChatTurnRole.USER
        "assistant" -> ChatTurnRole.ASSISTANT
        else -> null
    }
}
