package com.aiturbo.llm

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** The provider a request is routed to. */
enum class LlmTarget {
    DEEPSEEK,
    LOCAL,
    ;

    companion object {
        const val FIELD = "model"
        const val ALLOWED_VALUES = "local, deepseek"

        /** null means "not one of the allowed values" — the route answers 400 (ASM-01). */
        fun fromRequest(value: String?): LlmTarget? = when (value?.trim()?.lowercase()) {
            null, "" -> DEEPSEEK          // absent or blank → DeepSeek
            "local" -> LOCAL
            "deepseek" -> DEEPSEEK
            else -> null                  // unknown, non-blank → 400, no LLM/tool call
        }
    }
}

/** Request-scoped provider selection; travels through the agent session like `CallTrace` does. */
class LlmTargetContext(val target: LlmTarget) : AbstractCoroutineContextElement(LlmTargetContext) {
    companion object Key : CoroutineContext.Key<LlmTargetContext>
}

/** The provider selected for the current request; DeepSeek when no element is present (ASM-02). */
suspend fun currentLlmTarget(): LlmTarget =
    coroutineContext[LlmTargetContext]?.target ?: LlmTarget.DEEPSEEK
