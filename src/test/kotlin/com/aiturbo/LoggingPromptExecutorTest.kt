package com.aiturbo

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import ch.qos.logback.classic.Level
import com.aiturbo.log.LoggingPromptExecutor
import com.aiturbo.tools.ToolJsonRenderer
import com.aiturbo.llm.deepseekModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private data class WeatherToolArgs(
    @property:LLMDescription("Город или регион")
    val location: String,
)

private class WeatherToolStub : SimpleTool<WeatherToolArgs>(
    argsType = typeToken<WeatherToolArgs>(),
    name = "get_weather",
    description = "Возвращает текущую погоду и местное время для указанного города или региона",
) {
    override suspend fun execute(args: WeatherToolArgs): String = args.location
}

private class RecordingPromptExecutor(
    private val response: Message.Assistant? = null,
    private val failure: Throwable? = null,
    private val frames: List<StreamFrame> = emptyList(),
    private val onExecute: () -> Unit = {},
) : PromptExecutor() {

    val receivedTools = mutableListOf<List<ToolDescriptor>>()
    val receivedPrompts = mutableListOf<Prompt>()
    var closed = false

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        onExecute()
        receivedPrompts += prompt
        receivedTools += tools
        failure?.let { throw it }
        return response ?: Message.Assistant(
            parts = listOf(MessagePart.Text("ответ")),
            metaInfo = ResponseMetaInfo.Empty,
        )
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow {
        receivedPrompts += prompt
        receivedTools += tools
        frames.forEach { emit(it) }
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        ModerationResult(isHarmful = false, categories = emptyMap())

    override fun close() {
        closed = true
    }
}

class LoggingPromptExecutorTest {

    private val model = deepseekModel("deepseek-chat")
    private val tool = WeatherToolStub()
    private val tools = listOf(tool.descriptor)
    private val prompt = Prompt.build(id = "test-prompt") {
        system("Ты — ассистент по погоде.")
        user("Какая сейчас погода в Москве?")
    }

    private fun executor(delegate: PromptExecutor) = LoggingPromptExecutor(
        delegate = delegate,
        endpoint = "https://api.deepseek.com/chat/completions",
        toolJsonRenderer = ToolJsonRenderer(),
    )

    private fun toolCallAssistant() = Message.Assistant(
        parts = listOf(
            MessagePart.Tool.Call(
                id = "call-1",
                tool = "get_weather",
                args = """{"location":"Москва"}""",
            )
        ),
        metaInfo = ResponseMetaInfo.Empty,
    )

    @Test
    fun `logs the request before the delegate is called and the response after`() = runTest {
        LogCapture().use { capture ->
            var linesWhenCalled = -1
            val delegate = RecordingPromptExecutor(response = toolCallAssistant()) {
                linesWhenCalled = capture.lines().size
            }

            executor(delegate).execute(prompt, model, tools)

            val lines = capture.lines()
            assertEquals(2, lines.size, lines.toString())
            assertEquals(1, linesWhenCalled, "the request line must be written before the LLM call")
            assertTrue(lines[0].contains("stage=deepseek-request"), lines[0])
            assertTrue(lines[0].contains("endpoint=https://api.deepseek.com/chat/completions"), lines[0])
            assertTrue(lines[0].contains("model=deepseek-chat"), lines[0])
            assertTrue(lines[0].contains("tools_count=1"), lines[0])
            assertTrue(lines[0].contains("""tool_choice=-"""), lines[0])
            assertTrue(lines[0].contains("""messages=[system: "Ты — ассистент по погоде.", user: "Какая сейчас погода в Москве?"]"""), lines[0])
            assertTrue(lines[1].contains("stage=deepseek-response model=deepseek-chat"), lines[1])
            assertTrue(lines[1].contains("""tool_calls=[{"name":"get_weather","args":"{\"location\":\"Москва\"}"}]"""), lines[1])
        }
    }

    @Test
    fun `logs the full get_weather definition and passes the identical tools to the delegate`() = runTest {
        LogCapture().use { capture ->
            val delegate = RecordingPromptExecutor()

            executor(delegate).execute(prompt, model, tools)

            val requestLine = capture.lines().first()
            assertTrue(requestLine.contains("tools_count=1"), requestLine)
            assertTrue(requestLine.contains(""""name":"get_weather""""), requestLine)
            assertTrue(
                requestLine.contains("Возвращает текущую погоду и местное время для указанного города или региона"),
                requestLine,
            )
            assertTrue(requestLine.contains(""""required":["location"]"""), requestLine)

            assertEquals(1, delegate.receivedTools.size)
            assertEquals(tools, delegate.receivedTools.single())
            assertEquals(listOf("get_weather"), delegate.receivedTools.single().map { it.name })
        }
    }

    @Test
    fun `renders the tool choice of the prompt`() = runTest {
        LogCapture().use { capture ->
            val withNone = Prompt.build(id = "time", params = LLMParams(toolChoice = LLMParams.ToolChoice.None)) {
                user("Location: Kisumu")
            }

            executor(RecordingPromptExecutor()).execute(withNone, model, tools)

            assertTrue(capture.lines().first().contains("tool_choice=none"), capture.lines().first())
        }
    }

    @Test
    fun `an empty tools list is logged as zero and only warns`() = runTest {
        LogCapture().use { capture ->
            val delegate = RecordingPromptExecutor()

            executor(delegate).execute(prompt, model, emptyList())

            val lines = capture.lines()
            assertEquals(3, lines.size, lines.toString())
            assertTrue(lines[0].contains("tools_count=0"), lines[0])
            assertTrue(lines[0].contains("tools=[]"), lines[0])
            assertTrue(lines[2].contains("stage=deepseek-response"), lines[2])
            assertEquals(1, capture.linesAt(Level.WARN).size)
            assertTrue(capture.linesAt(Level.WARN).single().contains("tools_count=0"), capture.linesAt(Level.WARN).toString())
            assertEquals(emptyList(), delegate.receivedTools.single())
        }
    }

    @Test
    fun `a failure is logged with the error and rethrown`() = runTest {
        LogCapture().use { capture ->
            val delegate = RecordingPromptExecutor(failure = IllegalStateException("deepseek is down"))

            val error = assertFailsWith<IllegalStateException> {
                executor(delegate).execute(prompt, model, tools)
            }

            val lines = capture.lines()
            assertEquals(2, lines.size, lines.toString())
            assertTrue(lines[1].contains("stage=deepseek-response"), lines[1])
            assertTrue(lines[1].contains("error=IllegalStateException: deepseek is down"), lines[1])
            assertEquals("deepseek is down", error.message)
        }
    }

    @Test
    fun `a cancellation is rethrown without a response line`() = runTest {
        LogCapture().use { capture ->
            val delegate = RecordingPromptExecutor(failure = CancellationException("cancelled"))

            assertFailsWith<CancellationException> {
                executor(delegate).execute(prompt, model, tools)
            }

            val lines = capture.lines()
            assertEquals(1, lines.size, lines.toString())
            assertTrue(lines.single().contains("stage=deepseek-request"), lines.single())
        }
    }

    @Test
    fun `executeStreaming logs the request with the streaming flag and forwards the frames`() = runTest {
        LogCapture().use { capture ->
            val frames = listOf<StreamFrame>(
                StreamFrame.TextDelta("привет"),
                StreamFrame.End(),
            )
            val delegate = RecordingPromptExecutor(frames = frames)

            val received = executor(delegate).executeStreaming(prompt, model, tools).toList()

            assertEquals(frames, received)
            assertEquals(tools, delegate.receivedTools.single())
            assertEquals(1, capture.lines().size, capture.lines().toString())
            assertTrue(capture.lines().single().contains("stage=deepseek-request"), capture.lines().single())
            assertTrue(capture.lines().single().contains("streaming=true"), capture.lines().single())
            assertTrue(capture.lines().single().contains("tools_count=1"), capture.lines().single())
        }
    }

    @Test
    fun `close delegates to the wrapped executor`() {
        val delegate = RecordingPromptExecutor()

        executor(delegate).close()

        assertTrue(delegate.closed)
    }
}
