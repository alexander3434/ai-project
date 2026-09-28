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
import com.aiturbo.db.DbConfig
import com.aiturbo.db.PartnerFuelingEvent
import com.aiturbo.db.RelatedRows
import com.aiturbo.db.StageDbConfig
import com.aiturbo.db.StageFuelingRepository
import com.aiturbo.fueling.FuelingAgent
import com.aiturbo.fueling.KoogFuelingAgent
import com.aiturbo.log.LoggingPromptExecutor
import com.aiturbo.log.TraceLog
import com.aiturbo.tools.FindFuelingTool
import com.aiturbo.tools.ToolJsonRenderer
import com.aiturbo.tools.ToolSpec
import com.aiturbo.tools.ToolSpecLoader
import com.aiturbo.llm.deepseekModel
import com.aiturbo.weather.WeatherConfig
import com.aiturbo.weather.WeatherUnavailableException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.kodein.di.bind
import org.kodein.di.DI
import org.kodein.di.eagerSingleton
import org.kodein.di.instance
import org.kodein.di.singleton

private const val API_KEY_FIXTURE = "sk-fueling-chain-secret"
private const val STAGE_PASSWORD_FIXTURE = "stage-chain-db-password"
private const val FUELING_QUESTION = "Найди данные по проливу для заказа $SAMPLE_FUELING_ID"
private const val ANSWER = "Заказ $SAMPLE_FUELING_ID найден в архиве: 45.00 л, АИ-95, статус SUCCESS."

private fun fuelingToolCall(args: String = """{"orderId":"$SAMPLE_FUELING_ID"}""") = Message.Assistant(
    parts = listOf(MessagePart.Tool.Call(id = "call-1", tool = "find_fueling", args = args)),
    metaInfo = ResponseMetaInfo.Empty,
)

private fun fuelingTextAnswer(text: String) = Message.Assistant(
    parts = listOf(MessagePart.Text(text)),
    metaInfo = ResponseMetaInfo.Empty,
)

/** Scripted LLM: answers one queued [Message.Assistant] per call and records the inputs. */
private class FuelingChainExecutor(private vararg val answers: Message.Assistant) : PromptExecutor() {

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

/**
 * The production graph shape for the fueling slice with fakes at the edges: the
 * real tool, qualified registry, agent and [LoggingPromptExecutor] around a
 * scripted executor, a fake repository instead of JDBC. No socket, no stage DB,
 * no `.env`, no live LLM.
 */
private fun fuelingChainModules(
    executor: PromptExecutor,
    repository: StageFuelingRepository,
    apiKey: String,
): DI.Module = DI.Module("fueling-chain") {
    bind<ToolSpec>(tag = FUELING_TOOL_SPEC) with eagerSingleton {
        ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH)
    }
    bind<ToolJsonRenderer>() with singleton { ToolJsonRenderer() }
    bind<LLModel>() with singleton { deepseekModel("deepseek-flash") }
    bind<PromptExecutor>() with singleton {
        LoggingPromptExecutor(
            delegate = executor,
            endpoint = "https://api.deepseek.com/chat/completions",
            toolJsonRenderer = instance(),
        )
    }
    bind<StageFuelingRepository>() with singleton { repository }
    bind<FindFuelingTool>() with singleton { FindFuelingTool(instance(), instance(tag = FUELING_TOOL_SPEC)) }
    bind<ToolRegistry>(tag = FUELING_TOOL_REGISTRY) with singleton {
        ToolRegistry.builder().tool(instance<FindFuelingTool>()).build()
    }
    bind<FuelingAgent>() with singleton {
        if (apiKey.isBlank()) {
            FuelingAgent { throw WeatherUnavailableException("DeepSeek API key is not configured") }
        } else {
            KoogFuelingAgent(instance(), instance(tag = FUELING_TOOL_REGISTRY), instance())
        }
    }
}

class FuelingChainIntegrationTest {

    private val stageDb = StageDbConfig(
        host = "stage.example",
        port = 5432,
        database = "fueling",
        user = "fueling",
        password = STAGE_PASSWORD_FIXTURE,
    )

    private val db = DbConfig(host = "localhost", port = 5439, database = "mydb2", user = "myuser", password = "secret")

    private fun stageOf(line: String): String =
        Regex("""stage=(\S+)""").find(line)!!.groupValues[1]

    private fun idOf(line: String): String = line.removePrefix("req=").substringBefore(' ')

    private fun assertOneId(lines: List<String>) {
        assertEquals(1, lines.map { idOf(it) }.toSet().size, lines.toString())
    }

    private fun assertNoSecrets(lines: List<String>) {
        val leaked = lines.filter { it.contains(API_KEY_FIXTURE) || it.contains(STAGE_PASSWORD_FIXTURE) }
        assertEquals(emptyList(), leaked)
    }

    private fun assertBounded(lines: List<String>) {
        lines.forEach { line ->
            assertTrue(
                line.length <= 2 * TraceLog.MAX_BODY_CHARS + 1024,
                "unbounded line (${line.length} chars): ${line.take(120)}",
            )
        }
        lines.filter { it.length > TraceLog.MAX_BODY_CHARS }.forEach { line ->
            assertTrue(line.contains("…[truncated,"), line.takeLast(120))
        }
    }

    @Test
    fun `a fueling request writes the eight-stage chain with one id`() = testApplication {
        val executor = FuelingChainExecutor(fuelingToolCall(), fuelingTextAnswer(ANSWER))
        val repository = FakeStageFuelingRepository(sampleLookupResult())
        application { module(listOf(fuelingChainModules(executor, repository, apiKey = API_KEY_FIXTURE))) }

        LogCapture().use { capture ->
            val response = client.post("/fueling") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$FUELING_QUESTION"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(ANSWER), response.bodyAsText())

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
            assertNoSecrets(lines)
            assertBounded(lines)

            val description = ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH).description
            val requests = lines.filter { it.contains("stage=deepseek-request") }
            assertEquals(2, requests.size, lines.toString())
            requests.forEach { request ->
                assertTrue(request.contains("tools_count=1"), request)
                assertTrue(request.contains(""""name":"find_fueling""""), request)
                assertTrue(request.contains(""""description":"$description""""), request)
                assertTrue(request.contains("model=deepseek-flash"), request)
            }
            lines.filter { it.contains("stage=deepseek-response") }.forEach { response ->
                assertTrue(response.contains("model=deepseek-flash"), response)
            }

            val db = lines.single { it.contains("stage=db") }
            assertTrue(
                db.contains("req=${idOf(lines.first())} stage=db tool=find_fueling lookup=found records=1 "),
                db,
            )
            assertTrue(db.contains("tables=fuelings_archive events=2 tokens=1 feedback=0"), db)
            assertTrue(!db.contains("capped=true"), db)

            val tool = lines.single { it.contains("stage=tool") }
            assertTrue(
                tool.contains("""tool=find_fueling args={"orderId":"$SAMPLE_FUELING_ID"} is_error=false"""),
                tool,
            )
            assertTrue(tool.contains("""result="НАЙДЕНО: 1 (fuelings_archive)"""), tool)

            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.contains("status=200"), outbound)
            assertTrue(outbound.contains(""""answer":"$ANSWER""""), outbound)

            assertEquals(listOf(SAMPLE_FUELING_ID), repository.calls)
            assertEquals(2, executor.prompts.size, executor.prompts.toString())
            assertEquals(listOf("find_fueling"), executor.receivedTools.first().map { it.name })
        }
    }

    @Test
    fun `an invalid id answers 200 with is_error=true and no db line`() = testApplication {
        val executor = FuelingChainExecutor(
            fuelingToolCall("""{"orderId":"12345"}"""),
            fuelingTextAnswer("Идентификатор не похож на GUID — пришлите заказ в формате 8-4-4-4-12."),
        )
        val repository = FakeStageFuelingRepository()
        application { module(listOf(fuelingChainModules(executor, repository, apiKey = API_KEY_FIXTURE))) }

        LogCapture().use { capture ->
            val response = client.post("/fueling") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"Найди заказ 12345"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Идентификатор не похож на GUID"), response.bodyAsText())

            val lines = capture.lines()
            assertEquals(
                listOf(
                    "inbound",
                    "deepseek-request",
                    "deepseek-response",
                    "tool",
                    "deepseek-request",
                    "deepseek-response",
                    "outbound",
                ),
                lines.map { stageOf(it) },
                lines.toString(),
            )
            assertOneId(lines)
            assertNoSecrets(lines)

            assertEquals(0, lines.count { it.contains("stage=db") }, lines.toString())
            val tool = lines.single { it.contains("stage=tool") }
            assertTrue(tool.contains("""args={"orderId":"12345"}"""), tool)
            assertTrue(tool.contains("is_error=true"), tool)

            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.contains("status=200"), outbound)

            assertEquals(emptyList(), repository.calls)
        }
    }

    @Test
    fun `a stage failure answers 200 with a readable reason and is_error=true`() = testApplication {
        val executor = FuelingChainExecutor(
            fuelingToolCall(),
            fuelingTextAnswer("Данные stage временно недоступны, повторите запрос позже."),
        )
        val repository = FakeStageFuelingRepository().apply { unavailableReason = "connection refused" }
        application { module(listOf(fuelingChainModules(executor, repository, apiKey = API_KEY_FIXTURE))) }

        LogCapture().use { capture ->
            val response = client.post("/fueling") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$FUELING_QUESTION"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val lines = capture.lines()
            assertOneId(lines)
            assertNoSecrets(lines)

            val db = lines.single { it.contains("stage=db") }
            assertTrue(db.contains("lookup=unavailable reason=\"stage lookup failed: connection refused\""), db)
            assertTrue(lines.single { it.contains("stage=tool") }.contains("is_error=true"), lines.toString())
        }
    }

    @Test
    fun `long payloads stay bounded in the chain`() = testApplication {
        val executor = FuelingChainExecutor(fuelingToolCall(), fuelingTextAnswer("Готово."))
        val bigEvent = "x".repeat(3_000)
        val related = RelatedRows(
            events = (1..3).map { index ->
                PartnerFuelingEvent(
                    eventId = "evt-$index",
                    fuelingId = SAMPLE_FUELING_ID,
                    partnerId = "partner-9",
                    eventName = "FUELING_DELIVERED",
                    deliveryStatus = "DELIVERED",
                    data = bigEvent,
                    createdAt = EPOCH_2026_09_21_143300,
                    updatedAt = EPOCH_2026_09_21_143300,
                )
            },
            feedback = emptyList(),
            tokens = emptyList(),
        )
        val repository = FakeStageFuelingRepository(sampleLookupResult(related = related))
        application { module(listOf(fuelingChainModules(executor, repository, apiKey = API_KEY_FIXTURE))) }

        LogCapture().use { capture ->
            val response = client.post("/fueling") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$FUELING_QUESTION"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val lines = capture.lines()
            assertBounded(lines)
            assertTrue(lines.any { it.contains("…[truncated,") }, lines.map { it.length }.toString())
            assertOneId(lines)
            assertNoSecrets(lines)
        }
    }

    @Test
    fun `the composed production graph with a blank key answers 503 without deepseek tool or db lines`() =
        testApplication {
            application {
                module(
                    listOf(
                        appModules(
                            deepseek = DeepseekConfig(
                                baseUrl = "https://api.deepseek.com",
                                apiKey = "",
                                model = "deepseek-flash",
                            ),
                            ollama = OllamaConfig(),
                            db = db,
                            weather = WeatherConfig(),
                        ),
                        fuelingModule(stageDb = stageDb, apiKeyConfigured = false),
                    )
                )
            }

            LogCapture().use { capture ->
                val response = client.post("/fueling") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"message":"$FUELING_QUESTION"}""")
                }

                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                assertTrue(response.bodyAsText().contains("API key"), response.bodyAsText())

                val lines = capture.lines()
                assertEquals(2, lines.size, lines.toString())
                assertTrue(lines.none { it.contains("stage=deepseek") }, lines.toString())
                assertTrue(lines.none { it.contains("stage=tool") }, lines.toString())
                assertTrue(lines.none { it.contains("stage=db") }, lines.toString())
                assertNoSecrets(lines)
                assertOneId(lines)
                assertTrue(lines.single { it.contains("stage=outbound") }.contains("status=503"), lines.toString())
            }
        }
}
