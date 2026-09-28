package com.aiturbo.weather

import io.ktor.server.config.ApplicationConfig

/**
 * Open-Meteo API settings (free weather service, no API key required),
 * read from application.conf. Environment variables take precedence.
 */
data class WeatherConfig(
    val geocodingBaseUrl: String = DEFAULT_GEOCODING_BASE_URL,
    val forecastBaseUrl: String = DEFAULT_FORECAST_BASE_URL,
    val language: String = "ru",
) {
    companion object {
        const val DEFAULT_GEOCODING_BASE_URL = "https://geocoding-api.open-meteo.com"
        const val DEFAULT_FORECAST_BASE_URL = "https://api.open-meteo.com"

        fun from(config: ApplicationConfig): WeatherConfig = WeatherConfig(
            geocodingBaseUrl = config.propertyOrNull("weather.geocodingBaseUrl")?.getString()
                ?: DEFAULT_GEOCODING_BASE_URL,
            forecastBaseUrl = config.propertyOrNull("weather.forecastBaseUrl")?.getString()
                ?: DEFAULT_FORECAST_BASE_URL,
            language = config.propertyOrNull("weather.language")?.getString() ?: "ru",
        )
    }
}
