package com.aiturbo

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
import com.aiturbo.db.WeatherRecord
import com.aiturbo.db.WeatherRecordRepository
import com.aiturbo.log.LoggingPromptExecutor
import com.aiturbo.log.TraceLog
import com.aiturbo.time.BuiltinTimeZoneResolver
import com.aiturbo.time.CachingTimeZoneResolver
import com.aiturbo.time.CompositeTimeZoneResolver
import com.aiturbo.time.DirectZoneResolver
import com.aiturbo.time.LlmTimeZoneResolver
import com.aiturbo.time.TimeService
import com.aiturbo.time.TimeZoneResolver
import com.aiturbo.tools.GetWeatherTool
import com.aiturbo.tools.ToolJsonRenderer
import com.aiturbo.tools.ToolSpec
import com.aiturbo.tools.ToolSpecLoader
import com.aiturbo.tools.weatherToolDescriptor
import com.aiturbo.llm.deepseekModel
import com.aiturbo.weather.KoogWeatherAgent
import com.aiturbo.weather.WeatherAgent
import com.aiturbo.weather.WeatherClient
import com.aiturbo.weather.WeatherSnapshot
import com.aiturbo.weather.WeatherUnavailableException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import org.kodein.di.bind
import org.kodein.di.DI
import org.kodein.di.eagerSingleton
import org.kodein.di.instance
import org.kodein.di.singleton

private const val API_KEY_FIXTURE = "sk-chain-test-secret-value"
private const val DB_PASSWORD_FIXTURE = "chain-test-db-password"
private const val WEATHER_QUESTION = "Какая погода в Москве?"

private fun toolCall(args: String = """{"location":"Moscow"}""") = Message.Assistant(
    parts = listOf(MessagePart.Tool.Call(id = "call-1", tool = "get_weather", args = args)),
    metaInfo = ResponseMetaInfo.Empty,
)

private fun textAnswer(text: String) = Message.Assistant(
    parts = listOf(MessagePart.Text(text)),
    metaInfo = ResponseMetaInfo.Empty,
)

/** Scripted LLM: answers one queued [Message.Assistant] per call and records the inputs. */
private class ChainPromptExecutor(private vararg val answers: Message.Assistant) : PromptExecutor() {

    val prompts = mutableListOf<Prompt>()
    val receivedTools = mutableListOf<List<ToolDescriptor>>()
    private val queue = ArrayDeque(answers.toList())

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        prompts += prompt
        receivedTools += tools
        return queue.removeFirstOrNull() ?: error("unexpected LLM call #${prompts.size}")
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        flow { }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        ModerationResult(isHarmful = false, categories = emptyMap())

    override fun close() = Unit
}

private class ChainWeatherClient(private val description: String) : WeatherClient {
    override suspend fun currentWeather(location: String): WeatherSnapshot? = WeatherSnapshot(
        location = location,
        country = "Россия",
        latitude = 55.75204,
        longitude = 37.61781,
        temperatureC = 15.4,
        humidityPercent = 66,
        weatherCode = 3,
        description = description,
    )
}

private class ChainRepository : WeatherRecordRepository {
    override fun save(at: LocalDateTime): Int = 1
    override fun recent(limit: Int): List<WeatherRecord> = emptyList()
}

/**
 * The production graph shape with fakes at the edges: the real tool, tool
 * registry, agent and resolver chain, a fake LLM executor wrapped by the real
 * [LoggingPromptExecutor]. No database and no socket is touched.
 */
private fun chainModules(
    executor: PromptExecutor,
    apiKey: String,
    description: String = "облачно",
): DI.Module = DI.Module("trace-chain") {
    bind<Clock>() with singleton { Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC) }
    bind<ToolSpec>() with eagerSingleton { ToolSpecLoader.load() }
    bind<ToolJsonRenderer>() with singleton { ToolJsonRenderer() }
    bind<WeatherClient>() with singleton { ChainWeatherClient(description) }
    bind<WeatherRecordRepository>() with singleton { ChainRepository() }
    bind<GetWeatherTool>() with singleton { GetWeatherTool(instance(), instance(), instance(), instance(), instance()) }
    bind<ToolRegistry>() with singleton { ToolRegistry.builder().tool(instance<GetWeatherTool>()).build() }
    bind<LLModel>() with singleton { deepseekModel("deepseek-chat") }
    bind<PromptExecutor>() with singleton {
        LoggingPromptExecutor(
            delegate = executor,
            endpoint = "https://api.deepseek.com/chat/completions",
            toolJsonRenderer = instance(),
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
                    apiKeyConfigured = apiKey.isNotBlank(),
                )
            ),
        )
    }
    bind<TimeService>() with singleton { TimeService(instance()) }
    bind<WeatherAgent>() with singleton {
        if (apiKey.isBlank()) {
            WeatherAgent { throw WeatherUnavailableException("DeepSeek API key is not configured") }
        } else {
            KoogWeatherAgent(instance(), instance(), instance())
        }
    }
}

class TraceChainIntegrationTest {

    private fun stageOf(line: String): String =
        Regex("""stage=(\S+)""").find(line)!!.groupValues[1]

    private fun idOf(line: String): String = line.removePrefix("req=").substringBefore(' ')

    private fun assertOneId(lines: List<String>) {
        assertEquals(1, lines.map { idOf(it) }.toSet().size, lines.toString())
    }

    private fun assertNoEmptyTools(lines: List<String>) {
        assertEquals(emptyList(), lines.filter { it.contains("tools=[]") || it.contains("tools_count=0") })
    }

    private fun assertNoSecrets(lines: List<String>) {
        val leaked = lines.filter { it.contains(API_KEY_FIXTURE) || it.contains(DB_PASSWORD_FIXTURE) }
        assertEquals(emptyList(), leaked)
    }

    @Test
    fun `a weather request writes the five-stage chain with one id`() = testApplication {
        val executor = ChainPromptExecutor(toolCall(), textAnswer("Сейчас в Москве +15.4°C."))
        application { module(listOf(chainModules(executor, apiKey = API_KEY_FIXTURE))) }

        LogCapture().use { capture ->
            val response = client.post("/weather") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$WEATHER_QUESTION"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Сейчас в Москве +15.4°C."), response.bodyAsText())

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
            assertOneId(lines)
            assertNoEmptyTools(lines)
            assertNoSecrets(lines)

            val inbound = lines.single { it.contains("stage=inbound") }
            assertTrue(inbound.contains("""body={"message":"$WEATHER_QUESTION"}"""), inbound)

            val requests = lines.filter { it.contains("stage=deepseek-request") }
            assertEquals(2, requests.size, lines.toString())
            val description = ToolSpecLoader.load().description
            requests.forEach { request ->
                assertTrue(request.contains("tools_count=1"), request)
                assertTrue(request.contains(""""name":"get_weather""""), request)
                assertTrue(request.contains(""""description":"$description""""), request)
            }

            val db = lines.single { it.contains("stage=db") }
            assertTrue(db.contains("req=${idOf(lines.first())} stage=db tool=get_weather saved=true id=1"), db)

            val tool = lines.single { it.contains("stage=tool") }
            assertTrue(tool.contains("""tool=get_weather args={"location":"Moscow"} is_error=false"""), tool)

            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.contains("status=200"), outbound)
            assertTrue(outbound.contains("""body={"message":"$WEATHER_QUESTION","answer":"Сейчас в Москве +15.4°C."}"""), outbound)

            assertEquals(2, executor.prompts.size, executor.prompts.toString())
            assertEquals(listOf("get_weather"), executor.receivedTools.first().map { it.name })
        }
    }

    @Test
    fun `an unknown location is resolved through Koog with tool_choice=none`() = testApplication {
        val executor = ChainPromptExecutor(textAnswer("""{"timezone":"Africa/Nairobi"}"""))
        application { module(listOf(chainModules(executor, apiKey = API_KEY_FIXTURE))) }

        LogCapture().use { capture ->
            val response = client.get("/time?location=Kisumu")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Africa/Nairobi"), response.bodyAsText())

            val lines = capture.lines()
            assertEquals(
                listOf("inbound", "deepseek-request", "deepseek-response", "outbound"),
                lines.map { stageOf(it) },
                lines.toString(),
            )
            assertOneId(lines)
            assertNoEmptyTools(lines)
            assertNoSecrets(lines)

            val request = lines.single { it.contains("stage=deepseek-request") }
            assertTrue(request.contains("tool_choice=none"), request)
            assertTrue(request.contains("tools_count=1"), request)
            assertTrue(request.contains(""""name":"get_weather""""), request)
            assertTrue(lines.none { it.contains("stage=tool") }, lines.toString())
        }
    }

    @Test
    fun `a null timezone from the model becomes a 404 without a tool line`() = testApplication {
        val executor = ChainPromptExecutor(textAnswer("""{"timezone":null}"""))
        application { module(listOf(chainModules(executor, apiKey = API_KEY_FIXTURE))) }

        LogCapture().use { capture ->
            val response = client.get("/time?location=Kisumu")

            assertEquals(HttpStatusCode.NotFound, response.status)

            val lines = capture.lines()
            assertEquals(
                listOf("inbound", "deepseek-request", "deepseek-response", "outbound"),
                lines.map { stageOf(it) },
                lines.toString(),
            )
            assertOneId(lines)
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.contains("status=404"), outbound)
        }
    }

    @Test
    fun `a blank api key returns the documented statuses without any deepseek line`() = testApplication {
        val executor = ChainPromptExecutor()
        application { module(listOf(chainModules(executor, apiKey = ""))) }

        LogCapture().use { capture ->
            val time = client.get("/time?location=Kisumu")
            val weather = client.post("/weather") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$WEATHER_QUESTION"}""")
            }

            assertEquals(HttpStatusCode.NotFound, time.status)
            assertEquals(HttpStatusCode.ServiceUnavailable, weather.status)

            val lines = capture.lines()
            assertEquals(4, lines.size, lines.toString())
            assertTrue(lines.none { it.contains("stage=deepseek") }, lines.toString())
            assertTrue(lines.none { it.contains("stage=tool") }, lines.toString())
            assertEquals(0, executor.prompts.size)
            assertOneId(lines.filter { it.contains("path=/time") })
            assertOneId(lines.filter { it.contains("path=/weather") })
        }
    }

    @Test
    fun `long payloads stay bounded in the chain`() = testApplication {
        val executor = ChainPromptExecutor(toolCall(), textAnswer("Готово."))
        application { module(listOf(chainModules(executor, apiKey = API_KEY_FIXTURE, description = "о".repeat(6_000)))) }

        LogCapture().use { capture ->
            val response = client.post("/weather") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$WEATHER_QUESTION"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val lines = capture.lines()
            lines.forEach { line ->
                assertTrue(
                    line.length <= 2 * TraceLog.MAX_BODY_CHARS + 1024,
                    "unbounded line (${line.length} chars): ${line.take(120)}",
                )
            }
            // The long tool result and the message echoing it must be cut with the marker.
            lines.filter { it.length > TraceLog.MAX_BODY_CHARS }.forEach { line ->
                assertTrue(line.contains("…[truncated,"), line.takeLast(120))
            }
        }
    }

    @Test
    fun `the chain is offline - every stage line comes from the fakes`() = testApplication {
        val executor = ChainPromptExecutor(toolCall(), textAnswer("Готово."))
        application { module(listOf(chainModules(executor, apiKey = API_KEY_FIXTURE))) }

        LogCapture().use { capture ->
            client.post("/weather") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$WEATHER_QUESTION"}""")
            }

            assertEquals(8, capture.lines().size, capture.lines().toString())
        }
    }
}
