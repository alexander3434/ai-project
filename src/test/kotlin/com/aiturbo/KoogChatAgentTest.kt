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
import com.aiturbo.chat.CHAT_FALLBACK_ANSWER
import com.aiturbo.chat.CHAT_SYSTEM_PROMPT
import com.aiturbo.chat.ChatSession
import com.aiturbo.chat.ChatTurn
import com.aiturbo.chat.ChatTurnRole
import com.aiturbo.chat.KoogChatAgent
import com.aiturbo.llm.deepseekModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class ChatWeatherArgs(
    @property:LLMDescription("Город или регион")
    val location: String,
)

@Serializable
private data class ChatFuelingArgs(
    @property:LLMDescription("Идентификатор заказа")
    val orderId: String,
)

@Serializable
private data class ChatWebSearchArgs(
    @property:LLMDescription("Поисковый запрос")
    val query: String,
)

private class ChatWeatherToolStub(private val result: String) : SimpleTool<ChatWeatherArgs>(
    argsType = typeToken<ChatWeatherArgs>(),
    name = "get_weather",
    description = "Возвращает текущую погоду и местное время для указанного города или региона",
) {
    var invocations = 0

    override suspend fun execute(args: ChatWeatherArgs): String {
        invocations++
        return result
    }
}

private class ChatFuelingToolStub(private val result: String) : SimpleTool<ChatFuelingArgs>(
    argsType = typeToken<ChatFuelingArgs>(),
    name = "find_fueling",
    description = "Ищет заказ на пролив по идентификатору",
) {
    var invocations = 0

    override suspend fun execute(args: ChatFuelingArgs): String {
        invocations++
        return result
    }
}

private class ChatWebSearchToolStub(private val result: String) : SimpleTool<ChatWebSearchArgs>(
    argsType = typeToken<ChatWebSearchArgs>(),
    name = "web_search",
    description = "Ищет информацию в интернете и возвращает краткие результаты",
) {
    var invocations = 0
    var lastQuery: String? = null

    override suspend fun execute(args: ChatWebSearchArgs): String {
        invocations++
        lastQuery = args.query
        return result
    }
}

/** Serves the scripted non-streamed answers, then the scripted streams (the last one repeats). */
private class ChatScriptedExecutor(
    private val answers: List<Message.Assistant>,
    private val streams: List<List<StreamFrame>> = emptyList(),
) : PromptExecutor() {

    val receivedPrompts = mutableListOf<Prompt>()
    val receivedTools = mutableListOf<List<ToolDescriptor>>()
    var executeCalls = 0
    var streamingCalls = 0
    private var answerIndex = 0
    private var streamIndex = 0

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        receivedPrompts += prompt
        receivedTools += tools
        executeCalls++
        val answer = answers[minOf(answerIndex, answers.lastIndex)]
        answerIndex++
        return answer
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        flow {
            receivedPrompts += prompt
            receivedTools += tools
            streamingCalls++
            streams[minOf(streamIndex, streams.lastIndex)].forEach { emit(it) }
            streamIndex++
        }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        ModerationResult(isHarmful = false, categories = emptyMap())

    override fun close() = Unit
}

class KoogChatAgentTest {

    private val model = deepseekModel("deepseek-chat")

    private fun toolCall(tool: String = "get_weather", args: String = """{"location":"Москва"}""") =
        Message.Assistant(
            parts = listOf(MessagePart.Tool.Call(id = "call-1", tool = tool, args = args)),
            metaInfo = ResponseMetaInfo.Empty,
        )

    private fun textAnswer(text: String) = Message.Assistant(
        parts = listOf(MessagePart.Text(text)),
        metaInfo = ResponseMetaInfo.Empty,
    )

    private fun agent(
        executor: PromptExecutor,
        weather: ChatWeatherToolStub = ChatWeatherToolStub("Сейчас в Москве +12 °C"),
        fueling: ChatFuelingToolStub = ChatFuelingToolStub("Заказ 42 в работе"),
        webSearch: ChatWebSearchToolStub = ChatWebSearchToolStub("1. Матч — https://sports.example/1"),
        maxToolRounds: Int = 3,
    ) = KoogChatAgent(
        executor = executor,
        toolRegistry = ToolRegistry.builder().tool(weather).tool(fueling).tool(webSearch).build(),
        model = model,
        maxToolRounds = maxToolRounds,
    )

    @Test
    fun `history is replayed in order behind the system prompt with the new message last`() = runTest {
        val executor = ChatScriptedExecutor(listOf(textAnswer("Сейчас в Москве +12 °C.")))
        val session = ChatSession(
            history = listOf(
                ChatTurn(ChatTurnRole.USER, "Какая погода в Москве?"),
                ChatTurn(ChatTurnRole.ASSISTANT, "Сейчас в Москве +12 °C."),
            ),
            message = "А завтра?",
        )

        val pieces = agent(executor).stream(session).toList()

        assertEquals(listOf("Сейчас в Москве +12 °C."), pieces)
        assertEquals(1, executor.executeCalls)
        assertEquals(0, executor.streamingCalls)
        val messages = executor.receivedPrompts.single().messages
        assertEquals(4, messages.size, messages.toString())
        assertEquals(CHAT_SYSTEM_PROMPT, messages[0].textContent())
        assertEquals(listOf("Какая погода в Москве?", "Сейчас в Москве +12 °C.", "А завтра?"), messages.drop(1).map { it.textContent() })
        assertEquals(listOf(Message.Role.User, Message.Role.Assistant, Message.Role.User), messages.drop(1).map { it.role })
    }

    @Test
    fun `a tool call is executed non-streamed and the next round streams its result back`() = runTest {
        val weather = ChatWeatherToolStub("Сейчас в Москве +12 °C")
        val executor = ChatScriptedExecutor(
            answers = listOf(toolCall()),
            streams = listOf(
                listOf(StreamFrame.TextDelta("Сейчас "), StreamFrame.TextDelta("+12 °C"), StreamFrame.End()),
            ),
        )

        LogCapture().use { capture ->
            val pieces = agent(executor, weather = weather).stream(ChatSession(emptyList(), "Какая погода в Москве?"))
                .toList()

            assertEquals(listOf("Сейчас ", "+12 °C"), pieces)
            assertEquals(1, weather.invocations)
            assertEquals(1, executor.executeCalls, "round 0 must not stream")
            assertEquals(1, executor.streamingCalls, "the post-tool round must stream")
            val toolLine = capture.lines().single { it.contains("stage=tool") }
            assertTrue(toolLine.startsWith("req=- stage=tool tool=get_weather"), toolLine)
            assertTrue(toolLine.contains("""args={"location":"Москва"}"""), toolLine)
            assertTrue(toolLine.contains("""result="Сейчас в Москве +12 °C""""), toolLine)
            assertTrue(toolLine.contains("is_error=false"), toolLine)
        }

        val streamedPrompt = executor.receivedPrompts[1].messages
        val toolResult = streamedPrompt.last()
        assertTrue(toolResult.role == Message.Role.User, toolResult.toString())
        assertTrue(
            toolResult.parts.any { it is MessagePart.Tool.Result && it.output.contains("Сейчас в Москве +12 °C") },
            toolResult.toString(),
        )
    }

    @Test
    fun `the assembled streamed answer is appended by hand before the next round`() = runTest {
        val fueling = ChatFuelingToolStub("Заказ 42 в работе")
        val executor = ChatScriptedExecutor(
            answers = listOf(toolCall()),
            streams = listOf(
                // The streamed round itself asks for another tool: streaming never appends
                // the reply, so the next request may only see it because the agent appends it.
                listOf(
                    StreamFrame.ToolCallComplete(
                        id = "call-2",
                        name = "find_fueling",
                        content = """{"orderId":"42"}""",
                    ),
                    StreamFrame.End(),
                ),
                listOf(StreamFrame.TextDelta("Заказ 42 в работе."), StreamFrame.End()),
            ),
        )

        val pieces = agent(executor, fueling = fueling).stream(ChatSession(emptyList(), "Что с заказом 42?"))
            .toList()

        assertEquals(listOf("Заказ 42 в работе."), pieces)
        assertEquals(2, executor.streamingCalls)
        assertEquals(1, fueling.invocations)
        val thirdPrompt = executor.receivedPrompts[2].messages
        assertTrue(
            thirdPrompt.any { message ->
                message is Message.Assistant &&
                    message.parts.any { part -> part is MessagePart.Tool.Call && part.tool == "find_fueling" }
            },
            thirdPrompt.toString(),
        )
        assertTrue(
            thirdPrompt.last().parts.any { it is MessagePart.Tool.Result && it.output.contains("Заказ 42 в работе") },
            thirdPrompt.toString(),
        )
    }

    @Test
    fun `a direct answer is emitted as one piece without any streamed round`() = runTest {
        val executor = ChatScriptedExecutor(listOf(textAnswer("Привет! Чем помочь?")))

        val pieces = agent(executor).stream(ChatSession(emptyList(), "Привет")).toList()

        assertEquals(listOf("Привет! Чем помочь?"), pieces)
        assertEquals(1, executor.executeCalls)
        assertEquals(0, executor.streamingCalls)
    }

    @Test
    fun `a general question triggers the web search tool call and streams its result`() = runTest {
        val webSearch = ChatWebSearchToolStub("1. Матч «Зенит» — «Оренбург» — https://sports.example/1 — 20 сентября 2026.")
        val executor = ChatScriptedExecutor(
            answers = listOf(toolCall(tool = "web_search", args = """{"query":"когда матч Зенит Оренбург"}""")),
            streams = listOf(
                listOf(StreamFrame.TextDelta("Матч состоится 20 сентября 2026 года."), StreamFrame.End()),
            ),
        )

        val pieces = agent(executor, webSearch = webSearch)
            .stream(ChatSession(emptyList(), "Когда матч «Зенит» — «Оренбург»?"))
            .toList()

        assertEquals(listOf("Матч состоится 20 сентября 2026 года."), pieces)
        assertEquals(1, webSearch.invocations, "a question needing external facts must call web_search")
        assertEquals("когда матч Зенит Оренбург", webSearch.lastQuery)
        assertEquals(1, executor.executeCalls, "round 0 must not stream")
        assertEquals(1, executor.streamingCalls, "the post-tool round must stream")
        assertEquals(
            listOf("get_weather", "find_fueling", "web_search"),
            executor.receivedTools.first().map { it.name },
        )
    }

    @Test
    fun `a blank streamed answer falls back to the shared text`() = runTest {
        val executor = ChatScriptedExecutor(
            answers = listOf(toolCall()),
            streams = listOf(listOf(StreamFrame.End())),
        )

        val pieces = agent(executor).stream(ChatSession(emptyList(), "Какая погода в Москве?")).toList()

        assertEquals(listOf(CHAT_FALLBACK_ANSWER), pieces)
        assertEquals("Не удалось сформировать ответ.", pieces.single())
    }

    @Test
    fun `a non-converging tool loop is stopped with the same warning`() = runTest {
        val weather = ChatWeatherToolStub("Сейчас в Москве +12 °C")
        val executor = ChatScriptedExecutor(
            answers = listOf(toolCall()),
            streams = listOf(listOf(StreamFrame.ToolCallComplete(id = "call-2", name = "get_weather", content = """{"location":"Москва"}"""))),
        )

        LogCapture("com.aiturbo.chat.KoogChatAgent").use { capture ->
            val pieces = agent(executor, weather = weather, maxToolRounds = 2)
                .stream(ChatSession(emptyList(), "Какая погода в Москве?"))
                .toList()

            assertEquals(listOf(CHAT_FALLBACK_ANSWER), pieces)
            assertEquals(2, weather.invocations)
            val warnings = capture.lines().filter { it.contains("did not converge") }
            assertEquals(1, warnings.size, capture.lines().toString())
        }
    }
}
