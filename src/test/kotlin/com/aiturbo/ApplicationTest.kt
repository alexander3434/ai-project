package com.aiturbo

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
import java.time.ZoneOffset
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    private val testModules = module {
        single { Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC) }
        single<TimeZoneResolver> {
            // Offline resolver chain for route tests — no real HTTP calls.
            CompositeTimeZoneResolver(DirectZoneResolver(), BuiltinTimeZoneResolver())
        }
        single { TimeService(get()) }
    }

    @Test
    fun `GET time returns the local time for a known city`() = testApplication {
        application { module(listOf(testModules)) }

        val response = client.get("/time?location=Moscow")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"timezone\": \"Europe/Moscow\""), body)
        assertTrue(body.contains("\"date\": \"2026-09-09\""), body)
        assertTrue(body.contains("\"time\": \"15:00:00\""), body)
        assertTrue(body.contains("\"utcOffset\": \"+03:00\""), body)
    }

    @Test
    fun `GET time accepts an IANA zone id`() = testApplication {
        application { module(listOf(testModules)) }

        val response = client.get("/time?location=Asia/Tokyo")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"timezone\": \"Asia/Tokyo\""))
    }

    @Test
    fun `GET time without a location returns 400`() = testApplication {
        application { module(listOf(testModules)) }

        val response = client.get("/time")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("location"))
    }

    @Test
    fun `GET time with an unknown location returns 404`() = testApplication {
        application { module(listOf(testModules)) }

        val response = client.get("/time?location=Atlantis")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET root returns service info`() = testApplication {
        application { module(listOf(testModules)) }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Ai-Turbo"))
    }
}
