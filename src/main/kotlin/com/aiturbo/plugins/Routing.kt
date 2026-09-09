package com.aiturbo.plugins

import com.aiturbo.time.TimeService
import com.aiturbo.time.TimeZoneResolver
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.application
import io.ktor.server.application.install
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
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }

    routing {
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
