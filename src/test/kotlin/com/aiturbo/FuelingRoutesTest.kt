package com.aiturbo

import com.aiturbo.fueling.FuelingAgent
import com.aiturbo.llm.LlmTarget
import com.aiturbo.llm.currentLlmTarget
import com.aiturbo.log.TraceLog
import com.aiturbo.plugins.FuelingResponse
import com.aiturbo.weather.WeatherUnavailableException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.singleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FuelingRoutesTest {

    private var agentCalls = 0
    private var agentTraceId: String? = null
    private var agentTarget: LlmTarget? = null

    private val modules = DI.Module("fueling-routes-test") {
        bind<FuelingAgent>() with singleton {
            FuelingAgent { message ->
                agentCalls++
                agentTraceId = TraceLog.currentId()
                agentTarget = currentLlmTarget()
                "Ответ по заказу на: $message"
            }
        }
    }

    /** Correlation id of the first line of a captured request chain. */
    private fun requestId(lines: List<String>): String =
        lines.single { it.contains("stage=inbound") }.removePrefix("req=").substringBefore(' ')

    @Test
    fun `POST fueling returns the agent answer and logs the request chain`() = testApplication {
        application { module(listOf(modules)) }

        LogCapture().use { capture ->
            val response = client.post("/fueling") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$FUELING_QUESTION"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Ответ по заказу на: $FUELING_QUESTION"), response.bodyAsText())

            val lines = capture.lines()
            assertEquals(2, lines.size, lines.toString())
            val inbound = lines.single { it.contains("stage=inbound") }
            val outbound = lines.single { it.contains("stage=outbound") }
            val id = requestId(lines)
            assertTrue(inbound.contains("method=POST path=/fueling"), inbound)
            assertTrue(inbound.contains("""body={"message":"$FUELING_QUESTION"}"""), inbound)
            assertTrue(outbound.startsWith("req=$id stage=outbound status=200"), outbound)
            assertTrue(
                outbound.contains("""body={"message":"$FUELING_QUESTION","answer":"Ответ по заказу на: $FUELING_QUESTION"}"""),
                outbound,
            )
            assertEquals(id, agentTraceId, "the agent must run inside withContext(trace)")
            assertEquals(1, agentCalls)
        }
    }

    @Test
    fun `POST fueling trims the message in the response`() = testApplication {
        application { module(listOf(modules)) }

        val response = client.post("/fueling") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"  $FUELING_QUESTION  "}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<FuelingResponse>(response.bodyAsText())
        assertEquals(FUELING_QUESTION, body.message)
        assertEquals("Ответ по заказу на: $FUELING_QUESTION", body.answer)
    }

    @Test
    fun `POST fueling without a message returns 400 and never calls the agent`() = testApplication {
        application { module(listOf(modules)) }

        LogCapture().use { capture ->
            val response = client.post("/fueling") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"   "}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("message"), response.bodyAsText())

            val lines = capture.lines()
            assertEquals(2, lines.size, lines.toString())
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=400"), outbound)
            assertTrue(outbound.contains("""body={"error":"Field 'message' is required"}"""), outbound)
            assertEquals(0, agentCalls)
        }
    }

    @Test
    fun `POST fueling with a malformed body returns 400 and logs no inbound body`() = testApplication {
        application { module(listOf(modules)) }

        LogCapture().use { capture ->
            val response = client.post("/fueling") {
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
    fun `POST fueling returns 503 when the agent is unavailable and writes no deepseek lines`() = testApplication {
        application {
            module(
                listOf(
                    DI.Module("fueling-routes-unavailable") {
                        bind<FuelingAgent>() with singleton {
                            FuelingAgent { throw WeatherUnavailableException("DeepSeek API key is not configured") }
                        }
                    }
                )
            )
        }

        LogCapture().use { capture ->
            val response = client.post("/fueling") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$FUELING_QUESTION"}""")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("DeepSeek API key is not configured"), response.bodyAsText())

            val lines = capture.lines()
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=503"), outbound)
            assertTrue(outbound.contains("""body={"error":"DeepSeek API key is not configured"}"""), outbound)
            assertEquals(0, lines.count { it.contains("stage=deepseek") })
            assertEquals(0, agentCalls)
        }
    }

    @Test
    fun `POST fueling with an unknown model returns 400 without calling the agent`() = testApplication {
        application { module(listOf(modules)) }

        LogCapture().use { capture ->
            val response = client.post("/fueling") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$FUELING_QUESTION","model":"ollama"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                response.bodyAsText().contains("Field 'model' must be one of: local, deepseek"),
                response.bodyAsText(),
            )

            val lines = capture.lines()
            assertEquals(2, lines.size, lines.toString())
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=400"), outbound)
            assertEquals(0, agentCalls)
            assertEquals(0, lines.count { it.contains("stage=deepseek") })
        }
    }

    @Test
    fun `POST fueling routes a local model to the local target`() = testApplication {
        application { module(listOf(modules)) }

        val response = client.post("/fueling") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"$FUELING_QUESTION","model":"local"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(LlmTarget.LOCAL, agentTarget)
        assertEquals(1, agentCalls)
    }

    @Test
    fun `POST fueling without a model targets deepseek`() = testApplication {
        application { module(listOf(modules)) }

        val response = client.post("/fueling") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"$FUELING_QUESTION"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(LlmTarget.DEEPSEEK, agentTarget)
        assertEquals(1, agentCalls)
    }

    @Test
    fun `the weather route is still registered next to the fueling one`() = testApplication {
        application {
            module(
                listOf(
                    modules,
                    DI.Module("fueling-routes-weather") {
                        bind<com.aiturbo.weather.WeatherAgent>() with singleton {
                            com.aiturbo.weather.WeatherAgent { "погода: $it" }
                        }
                    },
                )
            )
        }

        val response = client.post("/weather") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"Какая погода в Москве?"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("погода: Какая погода в Москве?"), response.bodyAsText())
    }
}

private const val FUELING_QUESTION = "Найди данные по проливу для заказа $SAMPLE_FUELING_ID"
