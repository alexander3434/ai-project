package com.aiturbo

import com.aiturbo.log.CallTrace
import com.aiturbo.plugins.beginTrace
import com.aiturbo.plugins.configureSerialization
import com.aiturbo.plugins.respondTraced
import com.aiturbo.plugins.traceOrNull
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class TracingPayload(val text: String)

/**
 * Fails exactly one serialization on demand, so the "rendering the log body
 * must never change the response" rule (NFR-04) can be exercised: the first
 * serialization is the best-effort one inside `respondTraced`, the second one
 * is the real response.
 */
@Serializable(with = FlakyPayloadSerializer::class)
private class FlakyPayload(val text: String)

private object FlakyPayloadSerializer : KSerializer<FlakyPayload> {

    var failNextSerialization = false

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.aiturbo.FlakyPayload", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: FlakyPayload) {
        if (failNextSerialization) {
            failNextSerialization = false
            throw IllegalStateException("cannot render the payload for the log")
        }
        encoder.encodeString(value.text)
    }

    override fun deserialize(decoder: Decoder): FlakyPayload = FlakyPayload(decoder.decodeString())
}

class RequestTracingTest {

    @Test
    fun `beginTrace logs one inbound line stores the trace and is idempotent`() = testApplication {
        var before: CallTrace? = null
        var first: CallTrace? = null
        var second: CallTrace? = null

        application {
            configureSerialization()
            routing {
                get("/traced") {
                    before = call.traceOrNull()
                    first = call.beginTrace("""{"message":"привет"}""")
                    second = call.beginTrace()
                    call.respondTraced(TracingPayload("ответ"))
                }
            }
        }

        LogCapture().use { capture ->
            val response = client.get("/traced?x=1")

            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(before, "traceOrNull() is null before beginTrace()")
            assertEquals(first?.id, second?.id, "beginTrace() must return the same trace on a second call")

            val inboundLines = capture.lines().filter { it.contains("stage=inbound") }
            assertEquals(1, inboundLines.size, capture.lines().toString())
            val inbound = inboundLines.single()
            assertTrue(inbound.startsWith("req=${first?.id} stage=inbound method=GET path=/traced"), inbound)
            assertTrue(inbound.contains("query=x=1"), inbound)
            assertTrue(Regex("""client=\S+:\d+""").containsMatchIn(inbound), inbound)
            assertTrue(inbound.contains("""body={"message":"привет"}"""), inbound)
        }
    }

    @Test
    fun `an inbound body is omitted when the handler has none`() = testApplication {
        application {
            configureSerialization()
            routing {
                get("/no-body") { call.beginTrace(); call.respondTraced(TracingPayload("ответ")) }
            }
        }

        LogCapture().use { capture ->
            client.get("/no-body")

            val inbound = capture.lines().single { it.contains("stage=inbound") }
            assertTrue(!inbound.contains("body="), inbound)
            assertTrue(inbound.contains("query=-"), inbound)
        }
    }

    @Test
    fun `respondTraced logs the outbound line and responds with the same object and status`() = testApplication {
        application {
            configureSerialization()
            routing {
                get("/created") {
                    call.beginTrace()
                    call.respondTraced(TracingPayload("ответ"), HttpStatusCode.Created)
                }
            }
        }

        LogCapture().use { capture ->
            val response = client.get("/created")

            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.bodyAsText().contains("ответ"), response.bodyAsText())

            val lines = capture.lines()
            val inbound = lines.single { it.contains("stage=inbound") }
            val outbound = lines.single { it.contains("stage=outbound") }
            val id = inbound.removePrefix("req=").substringBefore(' ')
            assertTrue(outbound.startsWith("req=$id stage=outbound status=201"), outbound)
            assertTrue(outbound.contains("""body={"text":"ответ"}"""), outbound)
        }
    }

    @Test
    fun `a request without beginTrace still gets an outbound line`() = testApplication {
        application {
            configureSerialization()
            routing {
                get("/implicit") { call.respondTraced(TracingPayload("ответ")) }
            }
        }

        LogCapture().use { capture ->
            val response = client.get("/implicit")

            assertEquals(HttpStatusCode.OK, response.status)
            val lines = capture.lines()
            val inbound = lines.single { it.contains("stage=inbound") }
            val outbound = lines.single { it.contains("stage=outbound") }
            val id = inbound.removePrefix("req=").substringBefore(' ')
            assertTrue(outbound.startsWith("req=$id stage=outbound status=200"), outbound)
        }
    }

    @Test
    fun `long request and response bodies are truncated in the log only`() = testApplication {
        val longText = "м".repeat(5_000)

        application {
            configureSerialization()
            routing {
                get("/long") {
                    call.beginTrace("""{"message":"$longText"}""")
                    call.respondTraced(TracingPayload(longText))
                }
            }
        }

        LogCapture().use { capture ->
            val response = client.get("/long")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(longText), "the client must get the untruncated body")

            val inbound = capture.lines().single { it.contains("stage=inbound") }
            val outbound = capture.lines().single { it.contains("stage=outbound") }
            assertTrue(inbound.contains("…[truncated,"), inbound.takeLast(80))
            assertTrue(outbound.contains("…[truncated,"), outbound.takeLast(80))
        }
    }

    @Test
    fun `a failure while rendering the log body does not change the response`() = testApplication {
        application {
            configureSerialization()
            routing {
                get("/flaky") {
                    call.beginTrace()
                    FlakyPayloadSerializer.failNextSerialization = true
                    call.respondTraced(FlakyPayload("ответ"))
                }
            }
        }

        LogCapture().use { capture ->
            val response = client.get("/flaky")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("ответ"), response.bodyAsText())
            assertTrue(capture.lines().none { it.contains("stage=outbound") }, capture.lines().toString())
        }
    }
}
