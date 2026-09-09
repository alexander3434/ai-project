package com.aiturbo

import com.aiturbo.time.LlmTimeZoneResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LlmTimeZoneResolverTest {

    private val config = DeepseekConfig(
        baseUrl = "http://localhost:9999",
        apiKey = "test-key",
        model = "deepseek-chat",
    )

    private fun clientReturning(payload: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient =
        HttpClient(
            MockEngine { request ->
                assertEquals("/chat/completions", request.url.encodedPath)
                assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
                respond(
                    content = payload,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    @Test
    fun `parses the time zone returned by the LLM`() = runTest {
        val client = clientReturning(
            """{"choices":[{"message":{"role":"assistant","content":"{\"timezone\":\"Europe/Paris\"}"}}]}"""
        )

        assertEquals(ZoneId.of("Europe/Paris"), LlmTimeZoneResolver(client, config).resolve("Paris"))
    }

    @Test
    fun `handles markdown fences around the JSON`() = runTest {
        val client = clientReturning(
            """{"choices":[{"message":{"role":"assistant","content":"```json\n{\"timezone\":\"Asia/Tokyo\"}\n```"}}]}"""
        )

        assertEquals(ZoneId.of("Asia/Tokyo"), LlmTimeZoneResolver(client, config).resolve("Tokyo"))
    }

    @Test
    fun `returns null when the LLM does not know the location`() = runTest {
        val client = clientReturning(
            """{"choices":[{"message":{"role":"assistant","content":"{\"timezone\":null}"}}]}"""
        )

        assertNull(LlmTimeZoneResolver(client, config).resolve("Atlantis"))
    }

    @Test
    fun `returns null on malformed LLM response`() = runTest {
        val client = clientReturning("""{"choices":[{"message":{"role":"assistant","content":"not json"}}]}""")

        assertNull(LlmTimeZoneResolver(client, config).resolve("Atlantis"))
    }

    @Test
    fun `returns null on HTTP error`() = runTest {
        val client = clientReturning("""{"error":"invalid api key"}""", HttpStatusCode.Unauthorized)

        assertNull(LlmTimeZoneResolver(client, config).resolve("Atlantis"))
    }

    @Test
    fun `returns null without calling the API when the key is not configured`() = runTest {
        val client = clientReturning("{}")
        val resolver = LlmTimeZoneResolver(client, config.copy(apiKey = ""))

        assertNull(resolver.resolve("Atlantis"))
    }
}
