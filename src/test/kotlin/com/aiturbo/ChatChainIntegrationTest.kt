package com.aiturbo

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.aiturbo.chat.CHAT_SYSTEM_PROMPT
import com.aiturbo.chat.ChatAgent
import com.aiturbo.chat.KoogChatAgent
import com.aiturbo.db.StageFuelingRepository
import com.aiturbo.db.WeatherRecord
import com.aiturbo.db.WeatherRecordRepository
import com.aiturbo.llm.ProviderAvailability
import com.aiturbo.llm.ProviderRoutingPromptExecutor
import com.aiturbo.llm.deepseekModel
import com.aiturbo.llm.ollamaModel
import com.aiturbo.log.LoggingPromptExecutor
import com.aiturbo.time.BuiltinTimeZoneResolver
import com.aiturbo.time.CachingTimeZoneResolver
import com.aiturbo.time.CompositeTimeZoneResolver
import com.aiturbo.time.DirectZoneResolver
import com.aiturbo.time.LlmTimeZoneResolver
import com.aiturbo.time.TimeService
import com.aiturbo.time.TimeZoneResolver
import com.aiturbo.tools.FindFuelingTool
import com.aiturbo.tools.GetWeatherTool
import com.aiturbo.tools.ToolJsonRenderer
import com.aiturbo.tools.ToolSpec
import com.aiturbo.tools.ToolSpecLoader
import com.aiturbo.tools.WebSearchConfig
import com.aiturbo.tools.WebSearchTool
import com.aiturbo.tools.weatherToolDescriptor
import com.aiturbo.weather.WeatherClient
import com.aiturbo.weather.WeatherSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.eagerSingleton
import org.kodein.di.instance
import org.kodein.di.singleton

private const val API_KEY_FIXTURE = "sk-chat-chain-secret-value"
private const val WEATHER_QUESTION = "Какая погода в Москве?"
private const val HISTORY_ANSWER = "Сейчас в Москве +15.4°C."

private fun chatToolCall(args: String = """{"location":"Moscow"}""") = Message.Assistant(
    parts = listOf(MessagePart.Tool.Call(id = "call-1", tool = "get_weather", args = args)),
    metaInfo = ResponseMetaInfo.Empty,
)

/** Scripted LLM: one queued answer per `execute`, one frame list per streamed round. */
private class ChatChainPromptExecutor(
    private val answers: List<Message.Assistant>,
    private val frames: List<StreamFrame> = emptyList(),
) : PromptExecutor() {

    val prompts = mutableListOf<Prompt>()
    val receivedTools = mutableListOf<List<ToolDescriptor>>()
    var streamingCalls = 0
    private var answerIndex = 0

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        prompts += prompt
        receivedTools += tools
        val answer = answers[minOf(answerIndex, answers.lastIndex)]
        answerIndex++
        return answer
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        flow {
            prompts += prompt
            receivedTools += tools
            streamingCalls++
            frames.forEach { emit(it) }
        }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        ModerationResult(isHarmful = false, categories = emptyMap())

    override fun close() = Unit
}

private class ChatChainWeatherClient : WeatherClient {
    override suspend fun currentWeather(location: String): WeatherSnapshot? = WeatherSnapshot(
        location = location,
        country = "Россия",
        latitude = 55.75204,
        longitude = 37.61781,
        temperatureC = 15.4,
        humidityPercent = 66,
        weatherCode = 3,
        description = "облачно",
    )
}

private class NoWeatherClient : WeatherClient {
    override suspend fun currentWeather(location: String): WeatherSnapshot? = null
}

private class ChatChainRepository : WeatherRecordRepository {
    override fun save(at: LocalDateTime): Int = 7
    override fun recent(limit: Int): List<WeatherRecord> = emptyList()
}

/**
 * The production chat graph shape with fakes at the edges: the real three-tool
 * registry, the real [KoogChatAgent] and the real routing/logging executors over
 * a scripted LLM. No database, no network, no live model — the web-search HTTP
 * client is a MockEngine that is never called here.
 */
private fun chatChainModules(
    deepseek: DeepseekConfig,
    executor: PromptExecutor,
    weatherClient: WeatherClient = ChatChainWeatherClient(),
): DI.Module = DI.Module("chat-chain") {
    bind<Clock>() with singleton { Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC) }
    bind<ToolSpec>() with eagerSingleton { ToolSpecLoader.load() }
    bind<ToolSpec>(tag = FUELING_TOOL_SPEC) with eagerSingleton {
        ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH)
    }
    bind<ToolSpec>(tag = WEB_SEARCH_TOOL_SPEC) with eagerSingleton {
        ToolSpecLoader.load(ToolSpecLoader.WEB_SEARCH_RESOURCE_PATH)
    }
    bind<ToolJsonRenderer>() with singleton { ToolJsonRenderer() }
    bind<WeatherClient>() with singleton { weatherClient }
    bind<WeatherRecordRepository>() with singleton { ChatChainRepository() }
    bind<GetWeatherTool>() with singleton { GetWeatherTool(instance(), instance(), instance(), instance(), instance()) }
    // The registry is shared with the real fueling tool; only its descriptor matters here.
    bind<StageFuelingRepository>() with singleton { FakeStageFuelingRepository() }
    bind<FindFuelingTool>() with singleton { FindFuelingTool(instance(), instance(tag = FUELING_TOOL_SPEC)) }
    bind<HttpClient>() with singleton {
        HttpClient(MockEngine { respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) })
    }
    bind<WebSearchTool>() with singleton {
        WebSearchTool(instance(), WebSearchConfig(), instance(tag = WEB_SEARCH_TOOL_SPEC))
    }
    bind<ToolRegistry>(tag = CHAT_TOOL_REGISTRY) with singleton {
        ToolRegistry.builder()
            .tool(instance<GetWeatherTool>())
            .tool(instance<FindFuelingTool>())
            .tool(instance<WebSearchTool>())
            .build()
    }
    bind<ProviderAvailability>() with singleton { ProviderAvailability(deepseek.apiKey.isNotBlank()) }
    bind<ChatAgent>() with singleton {
        KoogChatAgent(instance(), instance(tag = CHAT_TOOL_REGISTRY), instance())
    }

    bind<LLModel>() with singleton { deepseekModel(deepseek.model) }
    bind<PromptExecutor>(tag = DEEPSEEK_EXECUTOR_TAG) with singleton {
        LoggingPromptExecutor(
            delegate = executor,
            endpoint = "${deepseek.baseUrl.trimEnd('/')}/chat/completions",
            toolJsonRenderer = instance(),
        )
    }
    // Only the DeepSeek branch is exercised here; the local delegate stays the raw script.
    bind<PromptExecutor>() with singleton {
        ProviderRoutingPromptExecutor(
            deepseek = instance(tag = DEEPSEEK_EXECUTOR_TAG),
            local = executor,
            localModel = ollamaModel("qwen3:8b"),
            deepseekConfigured = deepseek.apiKey.isNotBlank(),
        )
    }
    bind<TimeZoneResolver>() with singleton {
        CompositeTimeZoneResolver(
            DirectZoneResolver(),
            BuiltinTimeZoneResolver(),
            CachingTimeZoneResolver(
                LlmTimeZoneResolver(
                    promptExecutor = instance(),
                    model = instance(),
                    // Same as the production binding: the registry is a Kodein dependency
                    // loop for this resolver, the descriptor comes from the tool spec.
                    toolDescriptorsProvider = { listOf(weatherToolDescriptor(instance())) },
                    apiKeyConfigured = deepseek.apiKey.isNotBlank(),
                )
            ),
        )
    }
    bind<TimeService>() with singleton { TimeService(instance()) }
}

class ChatChainIntegrationTest {

    private val deepseek = DeepseekConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = API_KEY_FIXTURE,
        model = "deepseek-chat",
    )

    private val frames = listOf<StreamFrame>(
        StreamFrame.TextDelta("Сейчас "),
        StreamFrame.TextDelta("в Москве "),
        StreamFrame.TextDelta("+15.4°C, облачно"),
        StreamFrame.End(),
    )

    private fun stageOf(line: String): String =
        Regex("""stage=(\S+)""").find(line)!!.groupValues[1]

    private fun idOf(line: String): String = line.removePrefix("req=").substringBefore(' ')

    private fun frame(event: String, data: String) = "event: $event\ndata: $data\n\n"

    @Test
    fun `one chat turn writes the whole chain and streams the answer`() = testApplication {
        val executor = ChatChainPromptExecutor(listOf(chatToolCall()), frames)
        application { module(listOf(chatChainModules(deepseek, executor))) }

        LogCapture().use { capture ->
            val response = client.post("/chat") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {"messages":[
                        {"role":"user","content":"$WEATHER_QUESTION"},
                        {"role":"assistant","content":"$HISTORY_ANSWER"},
                        {"role":"user","content":"А завтра?"}
                    ]}
                    """.trimIndent(),
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                frame("chunk", """{"text":"Сейчас "}""") +
                    frame("chunk", """{"text":"в Москве "}""") +
                    frame("chunk", """{"text":"+15.4°C, облачно"}""") +
                    frame("done", """{"answer":"Сейчас в Москве +15.4°C, облачно","model":"deepseek"}"""),
                response.bodyAsText(),
            )

            val lines = capture.lines()
            assertEquals(
                listOf(
                    "inbound",
                    "deepseek-request",
                    "deepseek-response",
                    "db",
                    "tool",
                    "deepseek-request",
                    "deepseek-response",
                    "outbound",
                ),
                lines.map { stageOf(it) },
                lines.toString(),
            )
            assertEquals(1, lines.map { idOf(it) }.toSet().size, lines.toString())
            assertEquals(emptyList(), lines.filter { it.contains("tools=[]") || it.contains("tools_count=0") })
            assertEquals(emptyList(), lines.filter { it.contains(API_KEY_FIXTURE) }, "the key must never reach the log")
            assertFalse(response.bodyAsText().contains(API_KEY_FIXTURE), "the key must never reach the frames")

            val requests = lines.filter { it.contains("stage=deepseek-request") }
            assertEquals(2, requests.size, lines.toString())
            requests.forEach { request ->
                assertTrue(request.contains("tools_count=3"), request)
                assertTrue(request.contains(""""name":"get_weather""""), request)
                assertTrue(request.contains(""""name":"find_fueling""""), request)
                assertTrue(request.contains(""""name":"web_search""""), request)
            }

            // Round 0 is the non-streamed tool round; it replays the history in order (FR-12).
            val first = requests.first()
            assertFalse(first.contains("streaming=true"), first)
            // The system prompt is multi-line, so its newlines are escaped and the closing
            // quote sits after the last line: only its opening line can be matched here.
            val systemFirstLine = CHAT_SYSTEM_PROMPT.lineSequence().first()
            assertTrue(first.contains("messages=[system: \"$systemFirstLine"), first)
            assertTrue(first.contains("""assistant: "$HISTORY_ANSWER", user: "А завтра?"]"""), first)

            // The post-tool round is the streamed one (D-04).
            val second = requests.last()
            assertTrue(second.contains("streaming=true"), second)

            val responses = lines.filter { it.contains("stage=deepseek-response") }
            assertEquals(2, responses.size, lines.toString())
            assertTrue(
                responses.first().contains("""tool_calls=[{"name":"get_weather","args":"{\"location\":\"Moscow\"}"}]"""),
                responses.first(),
            )
            assertTrue(responses.last().contains("""text="Сейчас в Москве +15.4°C, облачно""""), responses.last())

            val db = lines.single { it.contains("stage=db") }
            assertTrue(db.contains("stage=db tool=get_weather saved=true id=7"), db)

            val tool = lines.single { it.contains("stage=tool") }
            assertTrue(tool.contains("""tool=get_weather args={"location":"Moscow"} is_error=false"""), tool)

            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.contains("status=200"), outbound)
            assertTrue(
                outbound.contains(
                    """body={"event":"done","answer":"Сейчас в Москве +15.4°C, облачно","model":"deepseek"}"""
                ),
                outbound,
            )

            assertEquals(1, executor.streamingCalls)
            assertEquals(2, executor.prompts.size, executor.prompts.toString())
            assertEquals(
                listOf("system", "user", "assistant", "user"),
                executor.prompts.first().messages.map { it.role.name.lowercase() },
                executor.prompts.first().messages.toString(),
            )
            assertEquals(2, executor.receivedTools.size, executor.receivedTools.toString())
            executor.receivedTools.forEach { tools ->
                assertEquals(listOf("get_weather", "find_fueling", "web_search"), tools.map { it.name })
            }
        }
    }

    @Test
    fun `at least two chunks arrive before the terminal frame`() = testApplication {
        val executor = ChatChainPromptExecutor(listOf(chatToolCall()), frames)
        application { module(listOf(chatChainModules(deepseek, executor))) }

        val response = client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"messages":[{"role":"user","content":"$WEATHER_QUESTION"}]}""")
        }
        val body = response.bodyAsText()

        assertEquals(3, Regex("event: chunk").findAll(body).count(), body)
        assertTrue(
            body.endsWith(frame("done", """{"answer":"Сейчас в Москве +15.4°C, облачно","model":"deepseek"}""")),
            body,
        )
        assertEquals(1, Regex("event: done").findAll(body).count(), body)
        assertEquals(0, Regex("event: error").findAll(body).count(), body)
    }

    @Test
    fun `a tool that returns no data still ends the turn with the done frame`() = testApplication {
        // The registry keeps the real tools; the fake client has no weather this time,
        // so the tool reports that itself (an answer, not a tool error) and writes no `db` line.
        val executor = ChatChainPromptExecutor(
            answers = listOf(chatToolCall()),
            frames = listOf(StreamFrame.TextDelta("Погода сейчас недоступна."), StreamFrame.End()),
        )
        application { module(listOf(chatChainModules(deepseek, executor, weatherClient = NoWeatherClient()))) }

        LogCapture().use { capture ->
            val response = client.post("/chat") {
                contentType(ContentType.Application.Json)
                setBody("""{"messages":[{"role":"user","content":"$WEATHER_QUESTION"}]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                frame("chunk", """{"text":"Погода сейчас недоступна."}""") +
                    frame("done", """{"answer":"Погода сейчас недоступна.","model":"deepseek"}"""),
                response.bodyAsText(),
            )
            val lines = capture.lines()
            val tool = lines.single { it.contains("stage=tool") }
            assertTrue(tool.contains("is_error=false"), tool)
            assertTrue(tool.contains("Не удалось получить погоду для 'Moscow'."), tool)
            assertEquals(emptyList(), lines.filter { it.contains("stage=db") }, lines.toString())
            assertTrue(lines.single { it.contains("stage=outbound") }.contains("status=200"))
        }
    }
}
