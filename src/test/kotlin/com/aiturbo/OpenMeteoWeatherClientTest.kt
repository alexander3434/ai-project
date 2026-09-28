package com.aiturbo

import com.aiturbo.weather.OpenMeteoWeatherClient
import com.aiturbo.weather.WeatherConfig
import com.aiturbo.weather.weatherCodeDescription
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenMeteoWeatherClientTest {

    private val config = WeatherConfig()

    private fun client(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        payload: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = payload,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun `fetches and parses current weather for a location`() = runTest {
        val client = client { request ->
            when (request.url.encodedPath) {
                "/v1/search" -> {
                    assertEquals("Moscow", request.url.parameters["name"])
                    assertEquals("1", request.url.parameters["count"])
                    assertEquals("ru", request.url.parameters["language"])
                    respondJson(
                        """{"results":[{"name":"Москва","country":"Россия","latitude":55.75204,"longitude":37.61781}]}"""
                    )
                }
                "/v1/forecast" -> {
                    assertEquals("55.75204", request.url.parameters["latitude"])
                    assertEquals("37.61781", request.url.parameters["longitude"])
                    assertTrue(request.url.parameters["current"]!!.contains("temperature_2m"))
                    respondJson(
                        """{"current":{"temperature_2m":15.4,"weather_code":3,"relative_humidity_2m":66}}"""
                    )
                }
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        val snapshot = OpenMeteoWeatherClient(client, config).currentWeather("Moscow")

        assertNotNull(snapshot)
        assertEquals("Москва", snapshot.location)
        assertEquals("Россия", snapshot.country)
        assertEquals(15.4, snapshot.temperatureC)
        assertEquals(66, snapshot.humidityPercent)
        assertEquals(3, snapshot.weatherCode)
        assertEquals("облачно", snapshot.description)
    }

    @Test
    fun `returns null when the location is unknown`() = runTest {
        val client = client { request ->
            if (request.url.encodedPath == "/v1/search") respondJson("""{"results":[]}""")
            else error("Forecast must not be called for an unknown location")
        }

        assertNull(OpenMeteoWeatherClient(client, config).currentWeather("Atlantis"))
    }

    @Test
    fun `returns null when the forecast has no current weather`() = runTest {
        val client = client { request ->
            when (request.url.encodedPath) {
                "/v1/search" -> respondJson(
                    """{"results":[{"name":"Москва","latitude":55.75204,"longitude":37.61781}]}"""
                )
                "/v1/forecast" -> respondJson("""{"current":null}""")
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }

        assertNull(OpenMeteoWeatherClient(client, config).currentWeather("Moscow"))
    }

    @Test
    fun `returns null on upstream HTTP error`() = runTest {
        val client = client { request ->
            if (request.url.encodedPath == "/v1/search") {
                respondJson("""{"error":"boom"}""", HttpStatusCode.InternalServerError)
            } else {
                respondJson("""{}""")
            }
        }

        assertNull(OpenMeteoWeatherClient(client, config).currentWeather("Moscow"))
    }

    @Test
    fun `returns null on malformed response`() = runTest {
        val client = client { respondJson("not json") }

        assertNull(OpenMeteoWeatherClient(client, config).currentWeather("Moscow"))
    }

    @Test
    fun `maps WMO weather codes to Russian descriptions`() {
        assertEquals("ясно", weatherCodeDescription(0))
        assertEquals("переменная облачность", weatherCodeDescription(2))
        assertEquals("облачно", weatherCodeDescription(3))
        assertEquals("туман", weatherCodeDescription(45))
        assertEquals("дождь", weatherCodeDescription(61))
        assertEquals("снег", weatherCodeDescription(71))
        assertEquals("гроза", weatherCodeDescription(95))
        assertEquals("гроза с градом", weatherCodeDescription(99))
        assertEquals("код 999", weatherCodeDescription(999))
    }
}
