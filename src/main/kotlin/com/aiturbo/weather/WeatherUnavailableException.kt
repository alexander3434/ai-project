package com.aiturbo.weather

/** The LLM is not reachable or misconfigured — mapped to 503 on the routes. */
class WeatherUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
