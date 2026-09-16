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
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

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

    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.beginTrace()
            call.respondTraced(ErrorResponse("Invalid request"), HttpStatusCode.BadRequest)
        }
        exception<ContentTransformationException> { call, _ ->
            call.beginTrace()
            call.respondTraced(ErrorResponse("Invalid request body"), HttpStatusCode.BadRequest)
        }
        exception<DatabaseUnavailableException> { call, cause ->
            call.application.environment.log.warn("Database is unavailable", cause)
            call.beginTrace()
            call.respondTraced(ErrorResponse("Database is unavailable"), HttpStatusCode.ServiceUnavailable)
        }
        exception<WeatherUnavailableException> { call, cause ->
            call.application.environment.log.warn("Weather agent is unavailable", cause)
            call.beginTrace()
            call.respondTraced(
                ErrorResponse(cause.message ?: "Weather agent is unavailable"),
                HttpStatusCode.ServiceUnavailable,
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.beginTrace()
            call.respondTraced(ErrorResponse("Internal server error"), HttpStatusCode.InternalServerError)
        }
    }

    routing {
        weatherRoutes()

        get("/") {
            call.beginTrace()
            call.respondTraced(
                ServiceInfoResponse(
                    service = "Ai-Turbo time service",
                    usage = "GET /time?location=<city or IANA time zone, e.g. Moscow or Europe/Paris>",
                )
            )
        }

        get("/time") {
            val trace = call.beginTrace()
            val location = call.request.queryParameters["location"]?.trim().orEmpty()
            if (location.isEmpty()) {
                call.respondTraced(
                    ErrorResponse("Query parameter 'location' is required"),
                    HttpStatusCode.BadRequest,
                )
                return@get
            }

            val zone = withContext(trace) { resolver.resolve(location) }
            if (zone == null) {
                call.respondTraced(
                    ErrorResponse("Could not determine a time zone for location '$location'"),
                    HttpStatusCode.NotFound,
                )
                return@get
            }

            call.respondTraced(timeService.timeFor(location, zone))
        }
    }
}
