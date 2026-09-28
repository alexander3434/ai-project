package com.aiturbo.weather

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
private data class GeocodingResponse(val results: List<GeocodingResult> = emptyList())

@Serializable
private data class GeocodingResult(
    val name: String,
    val country: String? = null,
    val latitude: Double,
    val longitude: Double,
)

@Serializable
private data class ForecastResponse(val current: CurrentWeather? = null)

@Serializable
private data class CurrentWeather(
    @SerialName("temperature_2m") val temperatureC: Double,
    @SerialName("weather_code") val weatherCode: Int,
    @SerialName("relative_humidity_2m") val humidityPercent: Int? = null,
)

/**
 * Fetches the current weather from Open-Meteo (free, no API key):
 * geocoding resolves the location into coordinates, then the forecast
 * endpoint returns the current temperature, humidity and weather code.
 */
class OpenMeteoWeatherClient(
    private val client: HttpClient,
    private val config: WeatherConfig,
) : WeatherClient {

    private val logger = LoggerFactory.getLogger(OpenMeteoWeatherClient::class.java)

    override suspend fun currentWeather(location: String): WeatherSnapshot? {
        try {
            val place = geocode(location) ?: return null
            val current = forecast(place) ?: return null

            return WeatherSnapshot(
                location = place.name,
                country = place.country,
                latitude = place.latitude,
                longitude = place.longitude,
                temperatureC = current.temperatureC,
                humidityPercent = current.humidityPercent,
                weatherCode = current.weatherCode,
                description = weatherCodeDescription(current.weatherCode),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Weather lookup failed for '$location': {}", e.message)
            return null
        }
    }

    private suspend fun geocode(location: String): GeocodingResult? {
        val response = client.get("${config.geocodingBaseUrl.trimEnd('/')}/v1/search") {
            parameter("name", location)
            parameter("count", 1)
            parameter("language", config.language)
        }
        if (!response.status.isSuccess()) {
            logger.warn("Geocoding request failed with HTTP ${response.status.value}")
            return null
        }
        return response.body<GeocodingResponse>().results.firstOrNull()
    }

    private suspend fun forecast(place: GeocodingResult): CurrentWeather? {
        val response = client.get("${config.forecastBaseUrl.trimEnd('/')}/v1/forecast") {
            parameter("latitude", place.latitude)
            parameter("longitude", place.longitude)
            parameter("current", "temperature_2m,weather_code,relative_humidity_2m")
            parameter("timezone", "auto")
        }
        if (!response.status.isSuccess()) {
            logger.warn("Forecast request failed with HTTP ${response.status.value}")
            return null
        }
        return response.body<ForecastResponse>().current
    }
}

/** Maps a WMO weather code to a short Russian description. */
internal fun weatherCodeDescription(code: Int): String = when (code) {
    0 -> "ясно"
    1, 2 -> "переменная облачность"
    3 -> "облачно"
    45, 48 -> "туман"
    51, 53, 55, 56, 57 -> "морось"
    61, 63, 65, 66, 67 -> "дождь"
    71, 73, 75, 77 -> "снег"
    80, 81, 82 -> "ливень"
    85, 86 -> "снегопад"
    95 -> "гроза"
    96, 99 -> "гроза с градом"
    else -> "код $code"
}
