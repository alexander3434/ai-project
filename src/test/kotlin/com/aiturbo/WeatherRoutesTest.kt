package com.aiturbo

import ai.koog.prompt.executor.clients.LLMClientException
import ch.qos.logback.classic.Logger
import com.aiturbo.db.WeatherRecord
import com.aiturbo.db.WeatherRecordRepository
import com.aiturbo.llm.LlmTarget
import com.aiturbo.llm.currentLlmTarget
import com.aiturbo.log.TraceLog
import com.aiturbo.plugins.WeatherResponse
import com.aiturbo.weather.WeatherAgent
import com.aiturbo.weather.WeatherUnavailableException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.singleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WEATHER_QUESTION = "Какая погода в Москве?"

private class FakeHistoryRepository : WeatherRecordRepository {
    override fun save(at: LocalDateTime): Int = 1

    override fun recent(limit: Int): List<WeatherRecord> = listOf(
        WeatherRecord(id = 2, data = "2026-09-14 20:00:01", time = "20:00:01", createdAt = "2026-09-14 20:00:01"),
        WeatherRecord(id = 1, data = "2026-09-14 20:00:00", time = "20:00:00", createdAt = "2026-09-14 20:00:00"),
    ).take(limit)
}

class WeatherRoutesTest {

    private var agentCalls = 0
    private var agentTraceId: String? = null
    private var agentTarget: LlmTarget? = null

    private val modules = DI.Module("weather-routes-test") {
        bind<WeatherAgent>() with singleton {
            WeatherAgent { message ->
                agentCalls++
                agentTraceId = TraceLog.currentId()
                agentTarget = currentLlmTarget()
                "Ответ ассистента на: $message"
            }
        }
        bind<WeatherRecordRepository>() with singleton { FakeHistoryRepository() }
    }

    /** Correlation id of the first line of a captured request chain. */
    private fun requestId(lines: List<String>): String =
        lines.single { it.contains("stage=inbound") }.removePrefix("req=").substringBefore(' ')

    @Test
    fun `POST weather returns the agent answer and logs the request chain`() = testApplication {
        application { module(listOf(modules)) }

        LogCapture().use { capture ->
            val response = client.post("/weather") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$WEATHER_QUESTION"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Ответ ассистента на: $WEATHER_QUESTION"))

            val lines = capture.lines()
            assertEquals(2, lines.size, lines.toString())
            val inbound = lines.single { it.contains("stage=inbound") }
            val outbound = lines.single { it.contains("stage=outbound") }
            val id = requestId(lines)
            assertTrue(inbound.contains("method=POST path=/weather"), inbound)
            assertTrue(inbound.contains("""body={"message":"$WEATHER_QUESTION"}"""), inbound)
            assertTrue(outbound.startsWith("req=$id stage=outbound status=200"), outbound)
            assertTrue(
                outbound.contains(
                    """body={"message":"$WEATHER_QUESTION","answer":"Ответ ассистента на: $WEATHER_QUESTION"}"""
                ),
                outbound,
            )
            assertEquals(id, agentTraceId, "the agent must run inside withContext(trace)")
            assertEquals(1, agentCalls)
        }
    }

    @Test
    fun `POST weather without a message returns 400 and never calls the agent`() = testApplication {
        application { module(listOf(modules)) }

        LogCapture().use { capture ->
            val response = client.post("/weather") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"   "}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("message"))

            val lines = capture.lines()
            assertEquals(2, lines.size, lines.toString())
            val inbound = lines.single { it.contains("stage=inbound") }
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(inbound.contains("""body={"message":"   "}"""), inbound)
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=400"), outbound)
            assertTrue(outbound.contains("""body={"error":"Field 'message' is required"}"""), outbound)
            assertEquals(0, agentCalls)
        }
    }

    @Test
    fun `POST weather with a malformed body returns 400 and logs no inbound body`() = testApplication {
        application { module(listOf(modules)) }

        LogCapture().use { capture ->
            val response = client.post("/weather") {
                contentType(ContentType.Application.Json)
                setBody("not json")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)

            val lines = capture.lines()
            val inbound = lines.single { it.contains("stage=inbound") }
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(!inbound.contains("body="), inbound)
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=400"), outbound)
            assertTrue(outbound.contains("""body={"error":"""), outbound)
            assertEquals(0, agentCalls)
        }
    }

    @Test
    fun `POST weather returns 503 when the agent is unavailable`() = testApplication {
        application {
            module(
                listOf(
                    DI.Module("weather-routes-unavailable") {
                        bind<WeatherAgent>() with singleton {
                            WeatherAgent { throw WeatherUnavailableException("DeepSeek API key is not configured") }
                        }
                        bind<WeatherRecordRepository>() with singleton { FakeHistoryRepository() }
                    }
                )
            )
        }

        LogCapture().use { capture ->
            val response = client.post("/weather") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$WEATHER_QUESTION"}""")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("DeepSeek API key is not configured"))

            val lines = capture.lines()
            val inbound = lines.single { it.contains("stage=inbound") }
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(inbound.contains("""body={"message":"$WEATHER_QUESTION"}"""), inbound)
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=503"), outbound)
            assertTrue(outbound.contains("""body={"error":"DeepSeek API key is not configured"}"""), outbound)
            assertEquals(0, capture.lines().count { it.contains("stage=deepseek") })
        }
    }

    @Test
    fun `POST weather returns 503 when the LLM client is unavailable`() = testApplication {
        application {
            module(
                listOf(
                    DI.Module("weather-routes-llm-unavailable") {
                        bind<WeatherAgent>() with singleton {
                            WeatherAgent {
                                throw LLMClientException("OllamaClient", "Error from client: OllamaClient")
                            }
                        }
                        bind<WeatherRecordRepository>() with singleton { FakeHistoryRepository() }
                    }
                )
            )
        }

        LogCapture().use { capture ->
            val response = client.post("/weather") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$WEATHER_QUESTION"}""")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("LLM provider is unavailable"), response.bodyAsText())

            val lines = capture.lines()
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=503"), outbound)
            assertTrue(outbound.contains("""body={"error":"LLM provider is unavailable"}"""), outbound)
        }
    }

    @Test
    fun `POST weather with an unknown model returns 400 without calling the agent`() = testApplication {
        application { module(listOf(modules)) }

        LogCapture().use { capture ->
            val response = client.post("/weather") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$WEATHER_QUESTION","model":"gpt-4"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("Field 'model' must be one of: local, deepseek"),
                response.bodyAsText(),
            )

            val lines = capture.lines()
            assertEquals(2, lines.size, lines.toString())
            val inbound = lines.single { it.contains("stage=inbound") }
            val outbound = lines.single { it.contains("stage=outbound") }
            // The canary for @EncodeDefault: a sent field is rendered in the trace body.
            assertTrue(inbound.contains("""body={"message":"$WEATHER_QUESTION","model":"gpt-4"}"""), inbound)
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=400"), outbound)
            assertEquals(0, agentCalls)
            assertEquals(0, lines.count { it.contains("stage=deepseek") })
        }
    }

    @Test
    fun `POST weather routes a padded local model to the local target`() = testApplication {
        application { module(listOf(modules)) }

        val response = client.post("/weather") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"$WEATHER_QUESTION","model":" LOCAL "}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(LlmTarget.LOCAL, agentTarget)
        assertEquals(1, agentCalls)
        // No provider echo: the response contract is unchanged (ASM-14).
        val body = Json.decodeFromString<WeatherResponse>(response.bodyAsText())
        assertEquals(WEATHER_QUESTION, body.message)
        assertEquals("Ответ ассистента на: $WEATHER_QUESTION", body.answer)
    }

    @Test
    fun `POST weather without or with a blank model targets deepseek`() = testApplication {
        application { module(listOf(modules)) }

        val absent = client.post("/weather") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"$WEATHER_QUESTION"}""")
        }
        assertEquals(HttpStatusCode.OK, absent.status)
        assertEquals(LlmTarget.DEEPSEEK, agentTarget)

        agentTarget = null
        val blank = client.post("/weather") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"$WEATHER_QUESTION","model":"   "}""")
        }
        assertEquals(HttpStatusCode.OK, blank.status)
        assertEquals(LlmTarget.DEEPSEEK, agentTarget)
        assertEquals(2, agentCalls)
    }

    @Test
    fun `GET weather history returns the recent records and logs the response`() = testApplication {
        application { module(listOf(modules)) }

        LogCapture().use { capture ->
            val response = client.get("/weather/history?limit=1")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"id\": 2"), body)
            assertTrue(!body.contains("\"id\": 1"), body)

            val lines = capture.lines()
            val inbound = lines.single { it.contains("stage=inbound") }
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(inbound.contains("method=GET path=/weather/history query=limit=1"), inbound)
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=200"), outbound)
            assertTrue(outbound.contains(""""id":2"""), outbound)
        }
    }

    @Test
    fun `GET weather history with a non-numeric limit returns 400 and logs the error body`() = testApplication {
        application { module(listOf(modules)) }

        LogCapture().use { capture ->
            val response = client.get("/weather/history?limit=abc")

            assertEquals(HttpStatusCode.BadRequest, response.status)

            val lines = capture.lines()
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=400"), outbound)
            assertTrue(
                outbound.contains("""body={"error":"Query parameter 'limit' must be an integer between 1 and 100"}"""),
                outbound,
            )
        }
    }

    @Test
    fun `the old CallLogging plugin is gone`() = testApplication {
        application { module(listOf(modules)) }

        LogCapture(Logger.ROOT_LOGGER_NAME).use { capture ->
            client.get("/weather/history?limit=1")

            val oldStyleLines = capture.lines().filter { Regex("""\d+ OK: (GET|POST) /""").containsMatchIn(it) }
            assertEquals(emptyList(), oldStyleLines)
        }

        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("CallLogging") }
            .map { it.path }
            .toList()
        assertEquals(emptyList(), offenders)
    }
}
