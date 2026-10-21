package com.aiturbo.llm

/**
 * Pre-stream provider check for `POST /chat` (FR-09, D-09). It carries a
 * boolean, never the key itself: the route asks whether the selected target can
 * serve a request before opening the event stream.
 *
 * Ollama availability is discovered at call time (a pre-flight ping would add a
 * network round-trip and a second timeout contract); a blank DeepSeek key is
 * answered as the same `503` text the routing executor already throws.
 */
data class ProviderAvailability(val deepseekConfigured: Boolean) {

    /** `null` when the target can serve a request; the 503 message otherwise. */
    fun unavailableReason(target: LlmTarget): String? = when (target) {
        LlmTarget.DEEPSEEK -> if (deepseekConfigured) null else DEEPSEEK_KEY_MISSING
        LlmTarget.LOCAL -> null
    }

    companion object {
        const val DEEPSEEK_KEY_MISSING = "DeepSeek API key is not configured"
    }
}
