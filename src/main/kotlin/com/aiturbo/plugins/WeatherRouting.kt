package com.aiturbo.plugins

import com.aiturbo.db.WeatherRecord
import com.aiturbo.db.WeatherRecordRepository
import com.aiturbo.llm.LlmTarget
import com.aiturbo.llm.LlmTargetContext
import com.aiturbo.weather.WeatherAgent
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import org.kodein.di.instance

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WeatherRequest(
    val message: String = "",
    /** Provider selector; `@EncodeDefault(NEVER)` keeps an absent field out of the trace body. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val model: String? = null,
)

@Serializable
data class WeatherResponse(val message: String, val answer: String)

@Serializable
data class WeatherHistoryResponse(val records: List<WeatherRecord>)

/**
 * POST /weather — the user's message goes to the Koog agent, which calls the
 * get_weather tool when asked about the weather (the tool records the request
 * in Postgres) and answers in natural language.
 *
 * GET /weather/history — the most recent request records from the database.
 * Beans are resolved lazily (only inside handlers), so the routes can be
 * registered without the production dependency graph.
 */
fun Route.weatherRoutes() {
    val agent: WeatherAgent by di.instance()
    val repository: WeatherRecordRepository by di.instance()

    route("/weather") {
        post {
            val request = call.receive<WeatherRequest>()
            val trace = call.beginTrace(traceJson.encodeToJsonElement<WeatherRequest>(request).toString())
            val message = request.message.trim()
            if (message.isEmpty()) {
                call.respondTraced(ErrorResponse("Field 'message' is required"), HttpStatusCode.BadRequest)
                return@post
            }

            val target = LlmTarget.fromRequest(request.model)
            if (target == null) {
                call.respondTraced(
                    ErrorResponse("Field '${LlmTarget.FIELD}' must be one of: ${LlmTarget.ALLOWED_VALUES}"),
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val answer = withContext(trace + LlmTargetContext(target)) { agent.answer(message) }
            call.respondTraced(WeatherResponse(message = message, answer = answer))
        }

        get("/history") {
            call.beginTrace()
            val rawLimit = call.request.queryParameters["limit"]
            val limit = if (rawLimit == null) {
                20
            } else {
                rawLimit.toIntOrNull()?.takeIf { it in 1..100 } ?: run {
                    call.respondTraced(
                        ErrorResponse("Query parameter 'limit' must be an integer between 1 and 100"),
                        HttpStatusCode.BadRequest,
                    )
                    return@get
                }
            }

            val records = withContext(Dispatchers.IO) { repository.recent(limit) }
            call.respondTraced(WeatherHistoryResponse(records))
        }
    }
}
