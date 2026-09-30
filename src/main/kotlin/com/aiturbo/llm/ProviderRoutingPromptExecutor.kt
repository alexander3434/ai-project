package com.aiturbo.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.aiturbo.weather.WeatherUnavailableException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * One prompt executor, two providers: the request-scoped [LlmTargetContext] element
 * set by the route picks the delegate. `DEEPSEEK` forwards the model Koog built the
 * agent with; `LOCAL` substitutes [localModel] because a Koog `AIAgent` fixes its
 * model at build time and `MultiLLMPromptExecutor` dispatches by `model.provider`
 * (D13, D14). The executor itself knows nothing about requests or HTTP; every
 * delegate call goes through the same frozen [com.aiturbo.log.LoggingPromptExecutor]
 * contract, so the logged `endpoint=`/`model=` identify the provider actually used
 * (FR-10). Both streaming shapes are routed explicitly — the `LLModel` one and the
 * `ResolvedModel` one — instead of relying on the base-class default (D-10).
 */
class ProviderRoutingPromptExecutor(
    private val deepseek: PromptExecutor,
    private val local: PromptExecutor,
    private val localModel: LLModel,
    private val deepseekConfigured: Boolean,
) : PromptExecutor() {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = when (currentLlmTarget()) {
        LlmTarget.LOCAL -> local.execute(prompt, localModel, tools)
        LlmTarget.DEEPSEEK -> deepseek().execute(prompt, model, tools)
    }

    override suspend fun execute(
        prompt: Prompt,
        model: ResolvedModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = when (currentLlmTarget()) {
        LlmTarget.LOCAL -> local.execute(prompt, localModel, tools)
        LlmTarget.DEEPSEEK -> deepseek().execute(prompt, model, tools)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        // The element is read when the flow is collected, not when it is built:
        // callers may build the flow before entering the request's withContext.
        val delegate = when (currentLlmTarget()) {
            LlmTarget.LOCAL -> local to localModel
            LlmTarget.DEEPSEEK -> deepseek() to model
        }
        emitAll(delegate.first.executeStreaming(prompt, delegate.second, tools))
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: ResolvedModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        // Same collection-time element read as the LLModel overload; the deepseek
        // branch keeps the model resolved for the request and the delegate reduces
        // it to its effective model itself.
        val frames = when (currentLlmTarget()) {
            LlmTarget.LOCAL -> local.executeStreaming(prompt, localModel, tools)
            LlmTarget.DEEPSEEK -> deepseek().executeStreaming(prompt, model, tools)
        }
        emitAll(frames)
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        when (currentLlmTarget()) {
            LlmTarget.LOCAL -> local.moderate(prompt, localModel)
            LlmTarget.DEEPSEEK -> deepseek().moderate(prompt, model)
        }

    override fun close() {
        // Both delegates are closed even if the first one throws (T4).
        runCatching { deepseek.close() }
        runCatching { local.close() }
    }

    /** FR-09: a blank key keeps today's 503 contract, before any log line or HTTP attempt. */
    private fun deepseek(): PromptExecutor {
        if (!deepseekConfigured) {
            throw WeatherUnavailableException("DeepSeek API key is not configured")
        }
        return deepseek
    }
}
