package com.aiturbo.weather

import kotlinx.serialization.Serializable

/** Current weather for a location, as reported by the weather provider. */
@Serializable
data class WeatherSnapshot(
    val location: String,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
    val temperatureC: Double,
    val humidityPercent: Int?,
    val weatherCode: Int,
    val description: String,
)

interface WeatherClient {

    /**
     * Returns the current weather for [location], or null when the place is
     * unknown or the upstream service failed.
     */
    suspend fun currentWeather(location: String): WeatherSnapshot?
}
