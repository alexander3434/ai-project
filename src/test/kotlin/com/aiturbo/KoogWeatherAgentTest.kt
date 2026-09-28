package com.aiturbo

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import com.aiturbo.log.CallTrace
import com.aiturbo.weather.KoogWeatherAgent
import com.aiturbo.llm.deepseekModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class AgentToolArgs(
    @property:LLMDescription("Город или регион")
    val location: String,
)

private class AgentToolStub(private val result: String) : SimpleTool<AgentToolArgs>(
    argsType = typeToken<AgentToolArgs>(),
    name = "get_weather",
    description = "Возвращает текущую погоду и местное время для указанного города или региона",
) {
    var invocations = 0

    override suspend fun execute(args: AgentToolArgs): String {
        invocations++
        return result
    }
}

private class FailingAgentTool : SimpleTool<AgentToolArgs>(
    argsType = typeToken<AgentToolArgs>(),
    name = "get_weather",
    description = "Возвращает текущую погоду и местное время для указанного города или региона",
) {
    override suspend fun execute(args: AgentToolArgs): String = throw IllegalStateException("погода недоступна")
}

/** Returns the scripted answers in order and keeps repeating the last one. */
private class ScriptedPromptExecutor(private val answers: List<Message.Assistant>) : PromptExecutor() {

    val receivedTools = mutableListOf<List<ToolDescriptor>>()
    val receivedPrompts = mutableListOf<Prompt>()
    private var index = 0

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        receivedPrompts += prompt
        receivedTools += tools
        val answer = answers[minOf(index, answers.lastIndex)]
        index++
        return answer
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        flow { receivedTools += tools }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        ModerationResult(isHarmful = false, categories = emptyMap())

    override fun close() = Unit
}

class KoogWeatherAgentTest {

    private val model = deepseekModel("deepseek-chat")

    private fun toolCall(args: String = """{"location":"Москва"}""") = Message.Assistant(
        parts = listOf(MessagePart.Tool.Call(id = "call-1", tool = "get_weather", args = args)),
        metaInfo = ResponseMetaInfo.Empty,
    )

    private fun textAnswer(text: String) = Message.Assistant(
        parts = listOf(MessagePart.Text(text)),
        metaInfo = ResponseMetaInfo.Empty,
    )

    private fun agent(
        executor: PromptExecutor,
        tool: SimpleTool<AgentToolArgs>,
        maxToolRounds: Int = 3,
    ) = KoogWeatherAgent(
        executor = executor,
        toolRegistry = ToolRegistry.builder().tool(tool).build(),
        model = model,
        maxToolRounds = maxToolRounds,
    )

    @Test
    fun `runs the tool once and logs one stage=tool line`() = runTest {
        val tool = AgentToolStub("Сейчас в Москве +12 °C")
        val executor = ScriptedPromptExecutor(listOf(toolCall(), textAnswer("Сейчас в Москве +12 °C.")))

        LogCapture().use { capture ->
            val answer = agent(executor, tool).answer("Какая погода в Москве?")

            assertEquals("Сейчас в Москве +12 °C.", answer)
            assertEquals(1, tool.invocations)
            val toolLines = capture.lines().filter { it.contains("stage=tool") }
            assertEquals(1, toolLines.size, capture.lines().toString())
            val line = toolLines.single()
            assertTrue(line.startsWith("req=- stage=tool tool=get_weather"), line)
            assertTrue(line.contains("""args={"location":"Москва"}"""), line)
            assertTrue(line.contains("""result="Сейчас в Москве +12 °C""""), line)
            assertTrue(line.contains("is_error=false"), line)
        }
    }

    @Test
    fun `the tool line carries the correlation id of the coroutine context`() = runTest {
        val tool = AgentToolStub("Сейчас в Москве +12 °C")
        val executor = ScriptedPromptExecutor(listOf(toolCall(), textAnswer("готово")))

        LogCapture().use { capture ->
            withContext(CallTrace("abc12345")) {
                agent(executor, tool).answer("Какая погода в Москве?")
            }

            val line = capture.lines().single { it.contains("stage=tool") }
            assertTrue(line.startsWith("req=abc12345 stage=tool"), line)
        }
    }

    @Test
    fun `a failing tool is logged with is_error=true and the loop still answers`() = runTest {
        val executor = ScriptedPromptExecutor(listOf(toolCall(), textAnswer("Не удалось получить погоду")))

        LogCapture().use { capture ->
            val answer = agent(executor, FailingAgentTool()).answer("Какая погода в Москве?")

            assertEquals("Не удалось получить погоду", answer)
            val line = capture.lines().single { it.contains("stage=tool") }
            assertTrue(line.contains("is_error=true"), line)
        }
    }

    @Test
    fun `long arguments and results are truncated`() = runTest {
        val longLocation = "М".repeat(5_000)
        val tool = AgentToolStub("я".repeat(5_000))
        val executor = ScriptedPromptExecutor(listOf(toolCall("""{"location":"$longLocation"}"""), textAnswer("готово")))

        LogCapture().use { capture ->
            agent(executor, tool).answer("Какая погода?")

            val line = capture.lines().single { it.contains("stage=tool") }
            val markers = Regex("""…\[truncated, (\d+) chars total]""")
                .findAll(line)
                .map { it.groupValues[1].toInt() }
                .toList()
            assertEquals(2, markers.size, line.takeLast(300))
            assertTrue(markers.all { it >= 5_000 }, markers.toString())
        }
    }

    @Test
    fun `the executor always receives the registry tools`() = runTest {
        val tool = AgentToolStub("Сейчас в Москве +12 °C")
        val executor = ScriptedPromptExecutor(listOf(toolCall(), textAnswer("готово")))

        agent(executor, tool).answer("Какая погода в Москве?")

        assertEquals(2, executor.receivedTools.size, executor.receivedTools.toString())
        executor.receivedTools.forEach { tools ->
            assertEquals(listOf("get_weather"), tools.map { it.name })
            assertTrue(tools.isNotEmpty(), "the agent path must never send an empty tools list")
        }
    }

    @Test
    fun `the prompt carries the user message and the system instruction`() = runTest {
        val tool = AgentToolStub("Сейчас в Москве +12 °C")
        val executor = ScriptedPromptExecutor(listOf(toolCall(), textAnswer("готово")))

        agent(executor, tool).answer("Какая погода в Москве?")

        val firstPrompt = executor.receivedPrompts.first()
        assertTrue(firstPrompt.messages.any { it is Message.System }, firstPrompt.messages.toString())
        assertTrue(
            firstPrompt.messages.any { message ->
                message is Message.User &&
                    message.parts.any { part -> part is MessagePart.Text && part.text.contains("Какая погода в Москве?") }
            },
            firstPrompt.messages.toString(),
        )
    }

    @Test
    fun `a non-converging tool loop is stopped with a warning and a fallback answer`() = runTest {
        val tool = AgentToolStub("Сейчас в Москве +12 °C")
        val executor = ScriptedPromptExecutor(listOf(toolCall(), toolCall(), toolCall(), toolCall()))

        LogCapture("com.aiturbo.weather.KoogWeatherAgent").use { agentCapture ->
            val answer = agent(executor, tool, maxToolRounds = 2).answer("Какая погода в Москве?")

            assertEquals("Не удалось сформировать ответ.", answer)
            assertEquals(2, tool.invocations)
            val warnings = agentCapture.lines().filter { it.contains("did not converge") }
            assertEquals(1, warnings.size, agentCapture.lines().toString())
        }
    }
}
