package com.aiturbo.llm

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.aiturbo.DeepseekConfig

/**
 * DeepSeek model with the capabilities the Koog OpenAI-compatible client
 * requires — in particular [LLMCapability.OpenAIEndpoint.Completions],
 * without which the client cannot determine request parameters.
 */
fun deepseekModel(id: String): LLModel = LLModel(
    provider = LLMProvider.DeepSeek,
    id = id,
    capabilities = listOf(
        LLMCapability.Completion,
        LLMCapability.Temperature,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.OpenAIEndpoint.Completions,
    ),
)

/**
 * Koog prompt executor backed by DeepSeek (OpenAI-compatible API).
 * The OpenAI client is registered under the DeepSeek provider so that
 * [deepseekModel] resolves to it.
 */
fun deepseekPromptExecutor(config: DeepseekConfig): PromptExecutor {
    val client = OpenAILLMClient(
        config.apiKey,
        OpenAIClientSettings(
            baseUrl = config.baseUrl,
            chatCompletionsPath = "chat/completions",
        ),
    )
    return MultiLLMPromptExecutor(LLMProvider.DeepSeek to client)
}
