package com.aiturbo.chat

import kotlinx.coroutines.flow.Flow

/**
 * The chat slice's domain contract, mirroring `WeatherAgent`/`FuelingAgent`:
 * one conversation turn in, the visible answer pieces out. Implementations own
 * the Koog agent; the route owns the transport.
 */
fun interface ChatAgent {
    /** Emits the visible answer text pieces in order; the flow completes when the turn is over. */
    fun stream(session: ChatSession): Flow<String>
}

/** One chat request: the prior turns (oldest first) plus the new user message. */
data class ChatSession(val history: List<ChatTurn>, val message: String)

/** One conversation turn; only the two roles the model accepts (ASM-05). */
data class ChatTurn(val role: ChatTurnRole, val content: String)

enum class ChatTurnRole { USER, ASSISTANT }
