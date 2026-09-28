package com.aiturbo.llm

import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.aiturbo.OllamaConfig

/**
 * The Ollama model used on the local path. Only the capabilities the client can
 * serve are declared: `ToolChoice` and `OpenAIEndpoint` are DeepSeek/OpenAI
 * markers and are deliberately absent (D19).
 */
fun ollamaModel(id: String): LLModel = LLModel(
    provider = LLMProvider.Ollama,
    id = id,
    capabilities = listOf(
        LLMCapability.Completion,
        LLMCapability.Temperature,
        LLMCapability.Tools,
    ),
)

/**
 * Koog prompt executor backed by Ollama (verified against the resolved koog
 * 1.2.0 jar: the factory function `OllamaClient(baseUrl, timeoutConfig, ...)`
 * in `ai.koog.prompt.executor.ollama.client`, JVM name `ollamaClient`, backed by
 * the driver-resolved `KoogHttpClient`). The client is registered under the
 * Ollama provider so that [ollamaModel] resolves to it. Nothing connects at
 * construction: the HTTP client is built lazily, the connect wait stays capped at
 * [CONNECT_TIMEOUT_MILLIS] for fast "Ollama is down" detection (MS-09) and both the
 * request wait and the socket read wait are [OllamaConfig.timeoutSeconds] — a
 * CPU-only model can take far longer than 10 s to answer and pauses between
 * streamed bytes while it computes (NFR-06).
 */
fun ollamaPromptExecutor(config: OllamaConfig): PromptExecutor {
    val client = OllamaClient(
        baseUrl = config.baseUrl,
        timeoutConfig = ConnectionTimeoutConfig(
            requestTimeoutMillis = config.timeoutSeconds * 1_000L,
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
            socketTimeoutMillis = config.timeoutSeconds * 1_000L,
        ),
    )
    return MultiLLMPromptExecutor(LLMProvider.Ollama to client)
}

/** The server is local: a refused/unreachable connection must fail fast (NFR-06, MS-09). */
private const val CONNECT_TIMEOUT_MILLIS = 10_000L
