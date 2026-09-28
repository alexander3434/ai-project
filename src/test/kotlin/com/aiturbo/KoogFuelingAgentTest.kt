package com.aiturbo

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.Prompt
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import com.aiturbo.tools.FindFuelingArgs
import com.aiturbo.fueling.KoogFuelingAgent
import com.aiturbo.fueling.StageDatabaseUnavailableException
import com.aiturbo.fueling.SYSTEM_PROMPT
import com.aiturbo.log.CallTrace
import com.aiturbo.tools.FindFuelingTool
import com.aiturbo.tools.ToolSpecLoader
import com.aiturbo.llm.deepseekModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

private const val QUESTION = "Найди данные по проливу для заказа $SAMPLE_FUELING_ID"

private class FuelingToolStub(private val result: String) : SimpleTool<FindFuelingArgs>(
    argsType = typeToken<FindFuelingArgs>(),
    name = "find_fueling",
    description = "Находит заказ на пролив (заправку) по идентификатору (GUID) в данных stage",
) {
    var invocations = 0

    override suspend fun execute(args: FindFuelingArgs): String {
        invocations++
        return result
    }
}

private class FailingFuelingTool : SimpleTool<FindFuelingArgs>(
    argsType = typeToken<FindFuelingArgs>(),
    name = "find_fueling",
    description = "Находит заказ на пролив (заправку) по идентификатору (GUID) в данных stage",
) {
    override suspend fun execute(args: FindFuelingArgs): String =
        throw StageDatabaseUnavailableException("Данные stage временно недоступны")
}

/** Returns the scripted answers in order and keeps repeating the last one. */
private class FuelingScriptedExecutor(private val answers: List<Message.Assistant>) : PromptExecutor() {

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

class KoogFuelingAgentTest {

    private val model = deepseekModel("deepseek-flash")

    private fun toolCall(args: String = """{"orderId":"$SAMPLE_FUELING_ID"}""") = Message.Assistant(
        parts = listOf(MessagePart.Tool.Call(id = "call-1", tool = "find_fueling", args = args)),
        metaInfo = ResponseMetaInfo.Empty,
    )

    private fun textAnswer(text: String) = Message.Assistant(
        parts = listOf(MessagePart.Text(text)),
        metaInfo = ResponseMetaInfo.Empty,
    )

    private fun agent(
        executor: PromptExecutor,
        tool: SimpleTool<FindFuelingArgs>,
        maxToolRounds: Int = 3,
    ) = KoogFuelingAgent(
        executor = executor,
        toolRegistry = ToolRegistry.builder().tool(tool).build(),
        model = model,
        maxToolRounds = maxToolRounds,
    )

    @Test
    fun `runs the lookup once and logs one stage=tool line with the guid`() = runTest {
        val tool = FuelingToolStub("НАЙДЕНО: 1 (fuelings_archive)")
        val executor = FuelingScriptedExecutor(listOf(toolCall(), textAnswer("Заказ найден в архиве.")))

        LogCapture().use { capture ->
            val answer = agent(executor, tool).answer(QUESTION)

            assertEquals("Заказ найден в архиве.", answer)
            assertEquals(1, tool.invocations)
            val line = capture.lines().single { it.contains("stage=tool") }
            assertTrue(line.startsWith("req=- stage=tool tool=find_fueling"), line)
            assertTrue(line.contains("""args={"orderId":"$SAMPLE_FUELING_ID"}"""), line)
            assertTrue(line.contains("""result="НАЙДЕНО: 1 (fuelings_archive)""""), line)
            assertTrue(line.contains("is_error=false"), line)
        }
    }

    @Test
    fun `the tool line carries the correlation id of the coroutine context`() = runTest {
        val tool = FuelingToolStub("НАЙДЕНО: 1 (fuelings)")
        val executor = FuelingScriptedExecutor(listOf(toolCall(), textAnswer("готово")))

        LogCapture().use { capture ->
            withContext(CallTrace("abc12345")) {
                agent(executor, tool).answer(QUESTION)
            }

            val line = capture.lines().single { it.contains("stage=tool") }
            assertTrue(line.startsWith("req=abc12345 stage=tool"), line)
        }
    }

    @Test
    fun `a failing tool is logged with is_error=true and the loop still answers`() = runTest {
        val executor = FuelingScriptedExecutor(
            listOf(toolCall(), textAnswer("Данные stage временно недоступны, попробуйте позже."))
        )

        LogCapture().use { capture ->
            val answer = agent(executor, FailingFuelingTool()).answer(QUESTION)

            assertEquals("Данные stage временно недоступны, попробуйте позже.", answer)
            val line = capture.lines().single { it.contains("stage=tool") }
            assertTrue(line.contains("is_error=true"), line)
        }
    }

    @Test
    fun `the real tool and the agent write the db line before the tool line`() = runTest {
        val repository = FakeStageFuelingRepository()
        val tool = FindFuelingTool(repository, ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH))
        val executor = FuelingScriptedExecutor(listOf(toolCall(), textAnswer("Заказ найден.")))

        LogCapture().use { capture ->
            agent(executor, tool).answer(QUESTION)

            val lines = capture.lines()
            assertEquals(2, lines.size, lines.toString())
            assertTrue(lines[0].contains("stage=db tool=find_fueling lookup=found"), lines[0])
            assertTrue(lines[1].contains("stage=tool tool=find_fueling"), lines[1])
            assertTrue(lines[1].contains("is_error=false"), lines[1])
            assertEquals(listOf(SAMPLE_FUELING_ID), repository.calls)
        }
    }

    @Test
    fun `the first prompt carries the system prompt and the user message`() = runTest {
        val tool = FuelingToolStub("НАЙДЕНО: 1 (fuelings)")
        val executor = FuelingScriptedExecutor(listOf(toolCall(), textAnswer("готово")))

        agent(executor, tool).answer(QUESTION)

        val firstPrompt = executor.receivedPrompts.first()
        val system = firstPrompt.messages.filterIsInstance<Message.System>().single()
        assertEquals(SYSTEM_PROMPT, system.textContent())
        assertTrue(system.textContent().contains("find_fueling"), system.textContent())
        assertTrue(
            firstPrompt.messages.any { message ->
                message is Message.User &&
                    message.parts.any { part -> part is MessagePart.Text && part.text.contains(SAMPLE_FUELING_ID) }
            },
            firstPrompt.messages.toString(),
        )
    }

    @Test
    fun `the executor always receives the registry tools`() = runTest {
        val tool = FuelingToolStub("НАЙДЕНО: 1 (fuelings)")
        val executor = FuelingScriptedExecutor(listOf(toolCall(), textAnswer("готово")))

        agent(executor, tool).answer(QUESTION)

        assertEquals(2, executor.receivedTools.size, executor.receivedTools.toString())
        executor.receivedTools.forEach { tools ->
            assertEquals(listOf("find_fueling"), tools.map { it.name })
            assertTrue(tools.isNotEmpty(), "the agent path must never send an empty tools list")
        }
    }

    @Test
    fun `a non-converging tool loop is stopped with a warning and a fallback answer`() = runTest {
        val tool = FuelingToolStub("НАЙДЕНО: 1 (fuelings)")
        val executor = FuelingScriptedExecutor(listOf(toolCall(), toolCall(), toolCall(), toolCall()))

        LogCapture("com.aiturbo.fueling.KoogFuelingAgent").use { agentCapture ->
            val answer = agent(executor, tool, maxToolRounds = 2).answer(QUESTION)

            assertEquals("Не удалось сформировать ответ.", answer)
            assertEquals(2, tool.invocations)
            val warnings = agentCapture.lines().filter { it.contains("did not converge") }
            assertEquals(1, warnings.size, agentCapture.lines().toString())
        }
    }
}
