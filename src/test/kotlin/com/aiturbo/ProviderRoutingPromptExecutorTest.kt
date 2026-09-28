package com.aiturbo

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.aiturbo.llm.LlmTarget
import com.aiturbo.llm.LlmTargetContext
import com.aiturbo.llm.ProviderRoutingPromptExecutor
import com.aiturbo.llm.deepseekModel
import com.aiturbo.llm.ollamaModel
import com.aiturbo.log.LoggingPromptExecutor
import com.aiturbo.tools.ToolJsonRenderer
import com.aiturbo.weather.WeatherUnavailableException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records what the routing executor handed over; never performs I/O. */
private class RecordingExecutor(
    private val frames: List<StreamFrame> = emptyList(),
    private val failOnClose: Boolean = false,
) : PromptExecutor() {

    var calls = 0
    var lastModel: LLModel? = null
    var lastPrompt: Prompt? = null
    var lastTools: List<ToolDescriptor>? = null
    var moderated = 0
    var streamingCalls = 0
    var closed = false

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        calls++
        lastPrompt = prompt
        lastModel = model
        lastTools = tools
        return Message.Assistant(
            parts = listOf(MessagePart.Text("ответ")),
            metaInfo = ResponseMetaInfo.Empty,
        )
    }

    override suspend fun execute(
        prompt: Prompt,
        model: ResolvedModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = execute(prompt, model.effectiveModel, tools)

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        streamingCalls++
        lastModel = model
        frames.forEach { emit(it) }
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        moderated++
        lastModel = model
        return ModerationResult(isHarmful = false, categories = emptyMap())
    }

    override fun close() {
        closed = true
        if (failOnClose) error("close failed")
    }
}

class ProviderRoutingPromptExecutorTest {

    private val deepseekChat = deepseekModel("deepseek-chat")
    private val localModel = ollamaModel("qwen3:8b")
    private val prompt = Prompt.build(id = "test-prompt") {
        system("Ты — ассистент по погоде.")
        user("Какая сейчас погода в Москве?")
    }

    private fun routing(
        deepseek: PromptExecutor,
        local: PromptExecutor,
        deepseekConfigured: Boolean = true,
    ) = ProviderRoutingPromptExecutor(
        deepseek = deepseek,
        local = local,
        localModel = localModel,
        deepseekConfigured = deepseekConfigured,
    )

    @Test
    fun `without an element the deepseek delegate gets the passed model unchanged`() = runTest {
        val deepseek = RecordingExecutor()
        val local = RecordingExecutor()

        routing(deepseek, local).execute(prompt, deepseekChat, emptyList())

        assertEquals(1, deepseek.calls)
        assertEquals(deepseekChat, deepseek.lastModel)
        assertEquals(0, local.calls)
    }

    @Test
    fun `a deepseek element keeps the deepseek path`() = runTest {
        val deepseek = RecordingExecutor()
        val local = RecordingExecutor()

        withContext(LlmTargetContext(LlmTarget.DEEPSEEK)) {
            routing(deepseek, local).execute(prompt, deepseekChat, emptyList())
        }

        assertEquals(1, deepseek.calls)
        assertEquals(deepseekChat, deepseek.lastModel)
        assertEquals(0, local.calls)
    }

    @Test
    fun `a local element substitutes the ollama model`() = runTest {
        val deepseek = RecordingExecutor()
        val local = RecordingExecutor()

        withContext(LlmTargetContext(LlmTarget.LOCAL)) {
            routing(deepseek, local).execute(prompt, deepseekChat, emptyList())
        }

        assertEquals(0, deepseek.calls)
        assertEquals(1, local.calls)
        assertEquals(LLMProvider.Ollama, local.lastModel?.provider)
        assertEquals("qwen3:8b", local.lastModel?.id)
    }

    @Test
    fun `local works even with a blank deepseek key`() = runTest {
        val deepseek = RecordingExecutor()
        val local = RecordingExecutor()

        withContext(LlmTargetContext(LlmTarget.LOCAL)) {
            routing(deepseek, local, deepseekConfigured = false)
                .execute(prompt, deepseekChat, emptyList())
        }

        assertEquals(1, local.calls)
        assertEquals(0, deepseek.calls)
    }

    @Test
    fun `a blank key fails before any delegate call and any log line`() = runTest {
        val inner = RecordingExecutor()
        val local = RecordingExecutor()
        val deepseek = LoggingPromptExecutor(
            delegate = inner,
            endpoint = "https://api.deepseek.com/chat/completions",
            toolJsonRenderer = ToolJsonRenderer(),
        )

        LogCapture().use { capture ->
            val executor = routing(deepseek, local, deepseekConfigured = false)

            val noElement = assertFailsWith<WeatherUnavailableException> {
                executor.execute(prompt, deepseekChat, emptyList())
            }
            val explicit = assertFailsWith<WeatherUnavailableException> {
                withContext(LlmTargetContext(LlmTarget.DEEPSEEK)) {
                    executor.execute(prompt, deepseekChat, emptyList())
                }
            }

            assertEquals("DeepSeek API key is not configured", noElement.message)
            assertEquals("DeepSeek API key is not configured", explicit.message)
            assertEquals(0, inner.calls)
            assertTrue(capture.lines().isEmpty(), capture.lines().toString())
        }
    }

    @Test
    fun `close closes both delegates even when the first one throws`() {
        val deepseek = RecordingExecutor(failOnClose = true)
        val local = RecordingExecutor()

        routing(deepseek, local).close()

        assertTrue(deepseek.closed)
        assertTrue(local.closed)
    }

    @Test
    fun `streaming reads the element at collection time`() = runTest {
        val deepseekFrames = listOf<StreamFrame>(StreamFrame.TextDelta("deepseek"), StreamFrame.End())
        val localFrames = listOf<StreamFrame>(StreamFrame.TextDelta("local"), StreamFrame.End())
        val deepseek = RecordingExecutor(frames = deepseekFrames)
        val local = RecordingExecutor(frames = localFrames)
        val executor = routing(deepseek, local)

        // Built with no element, collected inside LOCAL: the local delegate must win.
        val builtOutside = executor.executeStreaming(prompt, deepseekChat, emptyList())
        val localCollected = withContext(LlmTargetContext(LlmTarget.LOCAL)) {
            builtOutside.toList()
        }
        assertEquals(localFrames, localCollected)
        assertEquals(1, local.streamingCalls)
        assertEquals(0, deepseek.streamingCalls)

        // Built inside LOCAL, collected with no element: the deepseek delegate must win.
        val builtInside = withContext(LlmTargetContext(LlmTarget.LOCAL)) {
            executor.executeStreaming(prompt, deepseekChat, emptyList())
        }
        val deepseekCollected = builtInside.toList()
        assertEquals(deepseekFrames, deepseekCollected)
        assertEquals(1, deepseek.streamingCalls)
        assertEquals(1, local.streamingCalls)
    }

    @Test
    fun `streaming with a blank key fails at collection time`() = runTest {
        val deepseek = RecordingExecutor()
        val executor = routing(deepseek, RecordingExecutor(), deepseekConfigured = false)

        val flow = executor.executeStreaming(prompt, deepseekChat, emptyList())

        assertFailsWith<WeatherUnavailableException> { flow.toList() }
        assertEquals(0, deepseek.streamingCalls)
    }

    @Test
    fun `moderate follows the same selection`() = runTest {
        val deepseek = RecordingExecutor()
        val local = RecordingExecutor()
        val executor = routing(deepseek, local)

        executor.moderate(prompt, deepseekChat)
        assertEquals(1, deepseek.moderated)
        assertNull(local.lastModel)

        withContext(LlmTargetContext(LlmTarget.LOCAL)) {
            executor.moderate(prompt, deepseekChat)
        }
        assertEquals(1, local.moderated)
        assertEquals(LLMProvider.Ollama, local.lastModel?.provider)
    }

    @Test
    fun `moderate with a blank key fails before the delegate`() = runTest {
        val deepseek = RecordingExecutor()

        assertFailsWith<WeatherUnavailableException> {
            routing(deepseek, RecordingExecutor(), deepseekConfigured = false)
                .moderate(prompt, deepseekChat)
        }
        assertEquals(0, deepseek.moderated)
    }

    @Test
    fun `the resolved-model overload routes like the plain one`() = runTest {
        val deepseek = RecordingExecutor()
        val local = RecordingExecutor()
        val executor = routing(deepseek, local)

        withContext(LlmTargetContext(LlmTarget.LOCAL)) {
            executor.execute(prompt, ResolvedModel(deepseekChat), emptyList())
        }

        assertEquals(1, local.calls)
        assertEquals("qwen3:8b", local.lastModel?.id)
    }
}
