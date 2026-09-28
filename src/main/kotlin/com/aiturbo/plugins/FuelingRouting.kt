package com.aiturbo.plugins

import com.aiturbo.fueling.FuelingAgent
import com.aiturbo.llm.LlmTarget
import com.aiturbo.llm.LlmTargetContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import org.kodein.di.instance

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FuelingRequest(
    val message: String = "",
    /** Provider selector; `@EncodeDefault(NEVER)` keeps an absent field out of the trace body. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val model: String? = null,
)

@Serializable
data class FuelingResponse(val message: String, val answer: String)

/**
 * POST /fueling — the user's message goes to the Koog fueling agent, which
 * calls the find_fueling tool when the question carries an order GUID and
 * answers in natural language with the stage-data summary.
 *
 * The contract mirrors POST /weather: a blank/absent `message` is rejected
 * with 400 before the agent runs, and all business outcomes (found, not found,
 * invalid id, stage database unavailable) are a 200 with a plain-language
 * answer. The bean is resolved lazily, so the route can be registered without
 * the production dependency graph.
 */
fun Route.fuelingRoutes() {
    val agent: FuelingAgent by di.instance()

    route("/fueling") {
        post {
            val request = call.receive<FuelingRequest>()
            val trace = call.beginTrace(traceJson.encodeToJsonElement<FuelingRequest>(request).toString())
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
            call.respondTraced(FuelingResponse(message = message, answer = answer))
        }
    }
}
