package com.aiturbo

import com.aiturbo.log.TraceLog
import com.aiturbo.time.BuiltinTimeZoneResolver
import com.aiturbo.time.CompositeTimeZoneResolver
import com.aiturbo.time.DirectZoneResolver
import com.aiturbo.time.TimeService
import com.aiturbo.time.TimeZoneResolver
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records the correlation id of the coroutine context the route runs it in. */
private class RecordingResolver(private val delegate: TimeZoneResolver) : TimeZoneResolver {

    var observedTraceId: String? = null

    override suspend fun resolve(location: String): ZoneId? {
        observedTraceId = TraceLog.currentId()
        return delegate.resolve(location)
    }
}

class ApplicationTest {

    private val recordingResolver =
        RecordingResolver(CompositeTimeZoneResolver(DirectZoneResolver(), BuiltinTimeZoneResolver()))

    private val testModules = module {
        single { Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC) }
        // Offline resolver chain for route tests — no real HTTP calls.
        single<TimeZoneResolver> { recordingResolver }
        single { TimeService(get()) }
    }

    private fun requestId(lines: List<String>): String =
        lines.single { it.contains("stage=inbound") }.removePrefix("req=").substringBefore(' ')

    @Test
    fun `GET time returns the local time for a known city and logs the chain`() = testApplication {
        application { module(listOf(testModules)) }

        LogCapture().use { capture ->
            val response = client.get("/time?location=Moscow")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"timezone\": \"Europe/Moscow\""), body)
            assertTrue(body.contains("\"date\": \"2026-09-09\""), body)
            assertTrue(body.contains("\"time\": \"15:00:00\""), body)
            assertTrue(body.contains("\"utcOffset\": \"+03:00\""), body)

            val lines = capture.lines()
            assertEquals(2, lines.size, lines.toString())
            val inbound = lines.single { it.contains("stage=inbound") }
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(inbound.contains("method=GET path=/time query=location=Moscow"), inbound)
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=200"), outbound)
            assertTrue(outbound.contains(""""location":"Moscow","timezone":"Europe/Moscow""""), outbound)
            assertEquals(requestId(lines), recordingResolver.observedTraceId, "the resolver must run inside withContext(trace)")
        }
    }

    @Test
    fun `GET time accepts an IANA zone id`() = testApplication {
        application { module(listOf(testModules)) }

        val response = client.get("/time?location=Asia/Tokyo")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"timezone\": \"Asia/Tokyo\""))
    }

    @Test
    fun `GET time without a location returns 400 and logs the error body`() = testApplication {
        application { module(listOf(testModules)) }

        LogCapture().use { capture ->
            val response = client.get("/time")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("location"))

            val lines = capture.lines()
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=400"), outbound)
            assertTrue(outbound.contains("""body={"error":"Query parameter 'location' is required"}"""), outbound)
        }
    }

    @Test
    fun `GET time with an unknown location returns 404 and logs the error body`() = testApplication {
        application { module(listOf(testModules)) }

        LogCapture().use { capture ->
            val response = client.get("/time?location=Atlantis")

            assertEquals(HttpStatusCode.NotFound, response.status)

            val lines = capture.lines()
            assertEquals(2, lines.size, lines.toString())
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=404"), outbound)
            assertTrue(
                outbound.contains("""body={"error":"Could not determine a time zone for location 'Atlantis'"}"""),
                outbound,
            )
        }
    }

    @Test
    fun `GET root returns service info and logs the response`() = testApplication {
        application { module(listOf(testModules)) }

        LogCapture().use { capture ->
            val response = client.get("/")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Ai-Turbo"))

            val lines = capture.lines()
            val outbound = lines.single { it.contains("stage=outbound") }
            assertTrue(outbound.startsWith("req=${requestId(lines)} stage=outbound status=200"), outbound)
            assertTrue(outbound.contains("Ai-Turbo"), outbound)
        }
    }
}
