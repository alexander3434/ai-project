package com.aiturbo.log

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import com.aiturbo.llm.StreamedAssistant
import com.aiturbo.tools.ToolJsonRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

/**
 * The single choke point for every DeepSeek call: it logs the outbound request
 * (stage `deepseek-request`) and the model's answer (stage `deepseek-response`),
 * then delegates untouched. Nothing is filtered, reordered or added — the
 * descriptors Koog built are exactly what the wrapped executor receives, so an
 * empty `tools` array can never be introduced here (FR-01, FR-02, FR-04, FR-05).
 *
 * A streamed call writes the same two lines: the request carries `streaming=true`
 * and the outcome is logged once the stream is over, assembled from its frames
 * (D-11) — or as the failure marker when the stream breaks.
 *
 * The executor never receives the API key, headers or the configuration object:
 * only the endpoint label, the model id, the prompt and the descriptors.
 */
class LoggingPromptExecutor(
    private val delegate: PromptExecutor,
    /** The provider endpoint this decorator reports in its trace line (FR-10). */
    internal val endpoint: String,
    private val toolJsonRenderer: ToolJsonRenderer,
) : PromptExecutor() {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = traced(model.id, prompt, tools, streaming = false) {
        delegate.execute(prompt, model, tools)
    }

    override suspend fun execute(
        prompt: Prompt,
        model: ResolvedModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = traced(model.effectiveModel.id, prompt, tools, streaming = false) {
        delegate.execute(prompt, model, tools)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        val id = TraceLog.currentId()
        logRequest(id, model.id, prompt, tools, streaming = true)
        val collected = StreamedAssistant()
        try {
            emitAll(delegate.executeStreaming(prompt, model, tools).onEach { collected.accept(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TraceLog.deepseekFailure(id, model.id, e)
            throw e
        }
        TraceLog.deepseekResponse(id, model.id, collected.toMessage())
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override fun close() {
        delegate.close()
    }

    private suspend fun traced(
        modelId: String,
        prompt: Prompt,
        tools: List<ToolDescriptor>,
        streaming: Boolean,
        call: suspend () -> Message.Assistant,
    ): Message.Assistant {
        val id = TraceLog.currentId()
        logRequest(id, modelId, prompt, tools, streaming)
        val response = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TraceLog.deepseekFailure(id, modelId, e)
            throw e
        }
        TraceLog.deepseekResponse(id, modelId, response)
        return response
    }

    private fun logRequest(
        id: String?,
        modelId: String,
        prompt: Prompt,
        tools: List<ToolDescriptor>,
        streaming: Boolean,
    ) {
        TraceLog.deepseekRequest(
            id = id,
            endpoint = endpoint,
            model = modelId,
            messages = prompt.messages,
            toolsCount = tools.size,
            tools = toolJsonRenderer.renderAll(tools),
            toolChoice = renderToolChoice(prompt.params.toolChoice),
            streaming = streaming,
        )
        if (tools.isEmpty()) {
            TraceLog.logger.warn(
                "req=${id ?: "-"} stage=deepseek-request WARN outbound DeepSeek request with an empty tools list " +
                    "endpoint=$endpoint model=$modelId tools_count=0"
            )
        }
    }

    private fun renderToolChoice(toolChoice: LLMParams.ToolChoice?): String? = when (toolChoice) {
        null -> null
        is LLMParams.ToolChoice.None -> "none"
        is LLMParams.ToolChoice.Auto -> "auto"
        is LLMParams.ToolChoice.Required -> "required"
        is LLMParams.ToolChoice.Named -> "named:${toolChoice.name}"
    }
}
