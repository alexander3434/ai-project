package com.aiturbo

import io.ktor.server.config.ApplicationConfig

/**
 * Local (Ollama) LLM settings, read from application.conf. The environment
 * overrides use HOCON `${?VAR}` substitution (like `deepseek.baseUrl`) — no
 * secret is involved and no `.env` value is read.
 */
data class OllamaConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    /**
     * How long a local generation may take. A CPU-only Ollama serving a large
     * model routinely needs far more than the 10 s connect budget, so the HTTP
     * request wait is configurable while connect/socket stay short (NFR-06).
     */
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {

    /** Endpoint label used in the trace: Ollama's chat endpoint. */
    val chatEndpoint: String get() = "${baseUrl.trimEnd('/')}/api/chat"

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:11434"
        const val DEFAULT_MODEL = "qwen3:8b"
        const val DEFAULT_TIMEOUT_SECONDS = 120

        fun from(config: ApplicationConfig): OllamaConfig = OllamaConfig(
            baseUrl = config.propertyOrNull("ollama.baseUrl")?.getString() ?: DEFAULT_BASE_URL,
            model = config.propertyOrNull("ollama.model")?.getString() ?: DEFAULT_MODEL,
            timeoutSeconds = config.propertyOrNull("ollama.timeoutSeconds")?.getString()?.toIntOrNull()
                ?: DEFAULT_TIMEOUT_SECONDS,
        )
    }
}
