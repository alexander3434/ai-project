package com.aiturbo

import ai.koog.prompt.executor.clients.LLMClientException
import com.aiturbo.chat.ChatAgent
import com.aiturbo.chat.ChatSession
import com.aiturbo.chat.ChatTurn
import com.aiturbo.chat.ChatTurnRole
import com.aiturbo.db.DatabaseUnavailableException
import com.aiturbo.llm.LlmTarget
import com.aiturbo.llm.ProviderAvailability
import com.aiturbo.llm.currentLlmTarget
import com.aiturbo.log.TraceLog
import com.aiturbo.plugins.writeFrame
import com.aiturbo.plugins.writeTerminal
import com.aiturbo.weather.WeatherUnavailableException
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ClosedWriteChannelException
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.singleton
import java.io.StringWriter
import java.io.Writer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WEATHER_TURN = """{"messages":[{"role":"user","content":"Какая погода в Москве?"}]}"""

class ChatRoutesTest {

    private var agentCalls = 0
    private var agentSessions = mutableListOf<ChatSession>()
    private var agentTraceId: String? = null
    private var agentTarget: LlmTarget? = null

    private fun modules(
        pieces: List<String> = listOf("Сейчас ", "в Москве +12 °C"),
        failure: ((ChatSession) -> Throwable?)? = null,
        deepseekConfigured: Boolean = true,
    ) = DI.Module("chat-routes-test") {
        bind<ProviderAvailability>() with singleton { ProviderAvailability(deepseekConfigured) }
        bind<ChatAgent>() with singleton {
            ChatAgent { session ->
                agentCalls++
                agentSessions += session
                flow {
                    agentTraceId = TraceLog.currentId()
                    agentTarget = currentLlmTarget()
                    pieces.forEach { emit(it) }
                    failure?.invoke(session)?.let { throw it }
                }
            }
        }
    }

    private fun requestId(lines: List<String>): String =
        lines.single { it.contains("stage=inbound") }.removePrefix("req=").substringBefore(' ')

    /** The `error` field of a pre-stream JSON body (the app serializer pretty-prints it). */
    private fun errorText(body: String): String? =
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content

    private fun frame(event: String, data: String) = "event: $event\ndata: $data\n\n"

    @Test
    fun `two chunks are streamed as byte-exact frames and closed by the done frame`() = testApplication {
        application { module(listOf(modules())) }

        LogCapture().use { capture ->
            val response = client.post("/chat") {
                contentType(ContentType.Application.Json)
                setBody(WEATHER_TURN)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
            assertEquals(
                frame("chunk", """{"text":"Сейчас "}""") +
                    frame("chunk", """{"text":"в Москве +12 °C"}""") +
                    frame("done", """{"answer":"Сейчас в Москве +12 °C","model":"deepseek"}"""),
                response.bodyAsText(),
            )

            val lines = capture.lines()
            assertEquals(2, lines.size, lines.toString())
            val inbound = lines.single { it.contains("stage=inbound") }
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(inbound.contains("method=POST path=/chat"), inbound)
            assertTrue(inbound.contains("""body=$WEATHER_TURN"""), inbound)
            val id = requestId(lines)
            assertEquals(id, agentTraceId, "the agent must run inside withContext(trace)")
            assertTrue(outbound.startsWith("req=$id stage=outbound status=200"), outbound)
            assertTrue(
                outbound.contains("""body={"event":"done","answer":"Сейчас в Москве +12 °C","model":"deepseek"}"""),
                outbound,
            )
        }

        assertEquals(LlmTarget.DEEPSEEK, agentTarget)
        assertEquals(1, agentCalls)
        assertEquals(1, agentSessions.size)
        assertEquals("Какая погода в Москве?", agentSessions.single().message)
        assertEquals(emptyList(), agentSessions.single().history)
    }

    @Test
    fun `the history is mapped to the session and model=local is echoed`() = testApplication {
        application { module(listOf(modules(pieces = listOf("готово")))) }

        val response = client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"messages":[
                    {"role":"user","content":"  Какая погода в Москве?  "},
                    {"role":"ASSISTANT","content":"+12 °C"},
                    {"role":"user","content":"А завтра?"}
                ],"model":"LOCAL"}
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            frame("chunk", """{"text":"готово"}""") +
                frame("done", """{"answer":"готово","model":"local"}"""),
            response.bodyAsText(),
        )
        assertEquals(LlmTarget.LOCAL, agentTarget)
        assertEquals("А завтра?", agentSessions.single().message)
        assertEquals(
            listOf(
                ChatTurn(ChatTurnRole.USER, "Какая погода в Москве?"),
                ChatTurn(ChatTurnRole.ASSISTANT, "+12 °C"),
            ),
            agentSessions.single().history,
        )
    }

    @Test
    fun `validation failures stay JSON and never open the stream`() = testApplication {
        application { module(listOf(modules())) }

        val cases = listOf(
            """{"messages":[]}""" to "Field 'messages' must not be empty",
            """{"messages":[{"role":"system","content":"инструкции"}]}""" to
                "Field 'messages[0].role' must be one of: user, assistant",
            """{"messages":[{"role":"user","content":"  "}]}""" to
                "Field 'messages[0].content' must not be blank",
            """{"messages":[{"role":"user","content":"вопрос"},{"role":"assistant","content":"ответ"}]}""" to
                "The last message must be from the user",
            """{"messages":[{"role":"user","content":"вопрос"}],"model":"gpt"}""" to
                "Field 'model' must be one of: local, deepseek",
        )

        LogCapture().use { capture ->
            cases.forEach { (body, error) ->
                agentCalls = 0
                val response = client.post("/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                assertEquals(HttpStatusCode.BadRequest, response.status, body)
                assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters(), body)
                assertEquals(error, errorText(response.bodyAsText()), body)
                assertEquals(0, agentCalls, body)
            }

            val lines = capture.lines()
            assertEquals(cases.size * 2, lines.size, lines.toString())
            assertTrue(lines.none { it.contains("stage=deepseek") || it.contains("stage=tool") }, lines.toString())
            assertTrue(lines.none { it.contains("event: ") }, lines.toString())
        }
    }

    @Test
    fun `a blank deepseek key answers 503 JSON while local still streams`() = testApplication {
        application { module(listOf(modules(pieces = listOf("локально"), deepseekConfigured = false))) }

        LogCapture().use { capture ->
            val refused = client.post("/chat") {
                contentType(ContentType.Application.Json)
                setBody(WEATHER_TURN)
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
            assertEquals(ContentType.Application.Json, refused.contentType()?.withoutParameters())
            assertEquals("DeepSeek API key is not configured", errorText(refused.bodyAsText()))
            assertEquals(0, agentCalls)

            val outbound = capture.lines().single { it.contains("stage=outbound") }
            assertTrue(outbound.startsWith("req=${requestId(capture.lines())} stage=outbound status=503"), outbound)
            assertTrue(
                outbound.contains("""body={"error":"DeepSeek API key is not configured"}"""),
                outbound,
            )

            val local = client.post("/chat") {
                contentType(ContentType.Application.Json)
                setBody("""{"messages":[{"role":"user","content":"привет"}],"model":"local"}""")
            }

            assertEquals(HttpStatusCode.OK, local.status)
            assertEquals(
                frame("chunk", """{"text":"локально"}""") +
                    frame("done", """{"answer":"локально","model":"local"}"""),
                local.bodyAsText(),
            )
        }
    }

    @Test
    fun `an in-stream failure becomes one error frame with the mirrored text`() = testApplication {
        val cases = listOf<Pair<String, Pair<Throwable, String>>>(
            "weather" to (WeatherUnavailableException("DeepSeek API key is not configured") to
                """{"status":503,"error":"DeepSeek API key is not configured"}"""),
            "database" to (DatabaseUnavailableException("stage is down") to
                """{"status":503,"error":"Database is unavailable"}"""),
            "provider" to (LLMClientException("provider failed") to
                """{"status":503,"error":"LLM provider is unavailable"}"""),
            "unexpected" to (IllegalStateException("boom") to """{"status":500,"error":"Internal server error"}"""),
        )
        val failures = cases.associate { (name, value) -> name to value.first }

        application {
            module(listOf(modules(pieces = listOf("начало"), failure = { session -> failures[session.message] })))
        }

        LogCapture().use { capture ->
            cases.forEach { (name, value) ->
                val expected = value.second
                val response = client.post("/chat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"messages":[{"role":"user","content":"$name"}]}""")
                }

                assertEquals(HttpStatusCode.OK, response.status, name)
                assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
                assertEquals(
                    frame("chunk", """{"text":"начало"}""") + frame("error", expected),
                    response.bodyAsText(),
                    name,
                )
            }

            val outbound = capture.lines().filter { it.contains("stage=outbound") }
            assertEquals(cases.size, outbound.size, outbound.toString())
            outbound.forEach { line ->
                assertTrue(line.startsWith("req=") && line.contains("stage=outbound status=200"), line)
                assertTrue(line.contains("""body={"event":"error","""), line)
            }
        }
    }

    @Test
    fun `a cancelled stream ends without a terminal frame and without an outbound line`() = testApplication {
        application {
            module(listOf(modules(pieces = listOf("часть"), failure = { CancellationException("client gone") })))
        }

        LogCapture().use { capture ->
            val response = client.post("/chat") {
                contentType(ContentType.Application.Json)
                setBody(WEATHER_TURN)
            }

            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            assertTrue(body == frame("chunk", """{"text":"часть"}""") || body.isEmpty(), body)
            assertTrue(!body.contains("event: done") && !body.contains("event: error"), body)
            assertTrue(capture.lines().none { it.contains("stage=outbound") }, capture.lines().toString())
        }
    }

    @Test
    fun `a malformed body keeps the StatusPages answer and logs no inbound body`() = testApplication {
        application { module(listOf(modules(pieces = listOf("ок")))) }

        LogCapture().use { capture ->
            val response = client.post("/chat") {
                contentType(ContentType.Application.Json)
                setBody("not json")
            }
            // The same body against an existing route: /chat inherits the frozen handling.
            val reference = client.post("/weather") {
                contentType(ContentType.Application.Json)
                setBody("not json")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(HttpStatusCode.BadRequest, reference.status)
            assertEquals("Invalid request", errorText(response.bodyAsText()))
            assertEquals(errorText(reference.bodyAsText()), errorText(response.bodyAsText()))
            val inbound = capture.lines().first { it.contains("stage=inbound") }
            assertTrue(!inbound.contains("body="), inbound)
            assertEquals(0, agentCalls)
        }
    }

    @Test
    fun `a closed client channel stops the frame writes without an outbound line`() = runTest {
        LogCapture().use { capture ->
            val closed = ClosedWriteChannelException(IllegalStateException("closed"))
            val writer = object : Writer() {
                override fun write(cbuf: CharArray, off: Int, len: Int): Unit = throw closed

                override fun flush(): Unit = throw closed

                override fun close() = Unit
            }

            assertFalse(writer.writeFrame("chunk", """{"text":"поздно"}"""))
            assertFalse(
                writer.writeTerminal(
                    event = "done",
                    frame = Json.parseToJsonElement("""{"answer":"поздно","model":"local"}"""),
                    traceId = "req-closed",
                ),
            )
            assertTrue(capture.lines().isEmpty(), capture.lines().toString())
        }
    }

    @Test
    fun `a delivered terminal frame closes the chain with the outbound line`() = runTest {
        LogCapture().use { capture ->
            val writer = StringWriter()

            assertTrue(writer.writeFrame("chunk", """{"text":"готово"}"""))
            assertTrue(
                writer.writeTerminal(
                    event = "done",
                    frame = Json.parseToJsonElement("""{"answer":"готово","model":"local"}"""),
                    traceId = "req-open",
                ),
            )

            assertEquals(
                frame("chunk", """{"text":"готово"}""") +
                    frame("done", """{"answer":"готово","model":"local"}"""),
                writer.toString(),
            )
            val outbound = capture.lines().single { it.contains("stage=outbound") }
            assertTrue(outbound.startsWith("req=req-open stage=outbound status=200"), outbound)
            assertTrue(
                outbound.contains("""body={"event":"done","answer":"готово","model":"local"}"""),
                outbound,
            )
        }
    }

    @Test
    fun `a cancellation during a frame write is rethrown`() = runTest {
        val writer = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int): Unit =
                throw CancellationException("cancelled")

            override fun flush() = Unit

            override fun close() = Unit
        }

        assertFailsWith<CancellationException> { writer.writeFrame("chunk", """{"text":"x"}""") }
    }

    @Test
    fun `a client that stops reading ends the turn without an error frame or a 500`() = testApplication {
        val gate = CompletableDeferred<Unit>()
        application {
            module(
                listOf(
                    DI.Module("chat-abort-test") {
                        bind<ProviderAvailability>() with singleton { ProviderAvailability(true) }
                        bind<ChatAgent>() with singleton {
                            ChatAgent { _ ->
                                flow {
                                    emit("первый")
                                    gate.await()
                                    emit("второй")
                                }
                            }
                        }
                    },
                ),
            )
        }

        LogCapture().use { capture ->
            coroutineScope {
                launch {
                    runCatching {
                        client.preparePost("/chat") {
                            contentType(ContentType.Application.Json)
                            setBody(WEATHER_TURN)
                        }.execute { response ->
                            val channel = response.bodyAsChannel()
                            channel.readUTF8Line()
                            channel.cancel(CancellationException("the client stopped reading"))
                            gate.complete(Unit)
                        }
                    }
                }.join()
            }

            assertTrue(capture.lines().none { it.contains("stage=outbound") }, capture.lines().toString())
            assertTrue(capture.lines().none { it.contains("status=500") }, capture.lines().toString())
        }
    }

    @Test
    fun `the session of an empty history keeps the new message only`() = testApplication {
        application { module(listOf(modules(pieces = listOf("ок")))) }

        client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"messages":[{"role":"user","content":"привет"}],"unknown":1}""")
        }

        assertEquals("привет", agentSessions.single().message)
        assertNull(agentSessions.single().history.firstOrNull())
    }
}
