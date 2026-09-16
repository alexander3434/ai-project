package com.aiturbo.plugins

import com.aiturbo.db.DatabaseUnavailableException
import com.aiturbo.time.TimeService
import com.aiturbo.time.TimeZoneResolver
import com.aiturbo.weather.WeatherUnavailableException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import org.slf4j.event.Level

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class ServiceInfoResponse(
    val service: String,
    val usage: String,
)

fun Application.configureRouting() {
    val timeService by inject<TimeService>()
    val resolver by inject<TimeZoneResolver>()

    install(CallLogging) {
        level = Level.INFO
    }

    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request"))
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
        }
        exception<DatabaseUnavailableException> { call, cause ->
            call.application.environment.log.warn("Database is unavailable", cause)
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Database is unavailable"))
        }
        exception<WeatherUnavailableException> { call, cause ->
            call.application.environment.log.warn("Weather agent is unavailable", cause)
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(cause.message ?: "Weather agent is unavailable"),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }

    routing {
        weatherRoutes()

        get("/") {
            call.respond(
                ServiceInfoResponse(
                    service = "Ai-Turbo time service",
                    usage = "GET /time?location=<city or IANA time zone, e.g. Moscow or Europe/Paris>",
                )
            )
        }

        get("/time") {
            val location = call.request.queryParameters["location"]?.trim().orEmpty()
            if (location.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Query parameter 'location' is required"),
                )
                return@get
            }

            val zone = resolver.resolve(location)
            if (zone == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("Could not determine a time zone for location '$location'"),
                )
                return@get
            }

            call.respond(timeService.timeFor(location, zone))
        }
    }
}
