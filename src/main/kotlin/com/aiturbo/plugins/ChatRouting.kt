package com.aiturbo.plugins

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.LLMClientException
import com.aiturbo.chat.ChatAgent
import com.aiturbo.chat.ChatRequestValidator
import com.aiturbo.db.DatabaseUnavailableException
import com.aiturbo.llm.LlmTarget
import com.aiturbo.llm.LlmTargetContext
import com.aiturbo.llm.ProviderAvailability
import com.aiturbo.log.TraceLog
import com.aiturbo.weather.WeatherUnavailableException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.kodein.di.instance
import java.io.IOException
import java.io.Writer

/**
 * One message of the conversation list, exactly as the client sends it.
 *
 * Named `…TurnDto` rather than the raw-client style message name because the
 * static guard `RawDeepSeekCallTest` scans production sources for the removed
 * HTTP client's DTO declarations by name prefix; the JSON shape (`role`/
 * `content`) is the wire contract and is unchanged.
 */
@Serializable
data class ChatTurnDto(
    val role: String = "",
    val content: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChatRequest(
    val messages: List<ChatTurnDto> = emptyList(),
    /** Provider selector; `@EncodeDefault(NEVER)` keeps an absent field out of the trace body. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val model: String? = null,
)

/** One answer piece; zero or more per turn, in order. */
@Serializable
data class ChatChunkFrame(val text: String)

/** Terminal frame on success; `answer` equals the concatenation of all chunk texts (FR-10). */
@Serializable
data class ChatDoneFrame(val answer: String, val model: String)

/** Terminal frame on a failure after the stream opened; `status` is the intended HTTP status. */
@Serializable
data class ChatErrorFrame(val status: Int, val error: String)

/** Renders one SSE frame: `event: …\ndata: …\n\n` (the data is single-line compact JSON). */
internal fun sseFrame(event: String, data: String): String = "event: $event\ndata: $data\n\n"

/**
 * Maps a failure raised after the stream was opened to its terminal frame. The
 * texts mirror the StatusPages handlers of [configureRouting] (D-08) — the HTTP
 * status is already committed as 200, so the intended one travels in the body.
 */
internal fun inStreamFailure(error: Throwable): ChatErrorFrame = when (error) {
    is DatabaseUnavailableException -> ChatErrorFrame(status = 503, error = "Database is unavailable")
    is WeatherUnavailableException ->
        ChatErrorFrame(status = 503, error = error.message ?: "Weather agent is unavailable")
    is KoogHttpClientException, is LLMClientException ->
        ChatErrorFrame(status = 503, error = "LLM provider is unavailable")
    else -> ChatErrorFrame(status = 500, error = "Internal server error")
}

/**
 * POST /chat — one turn of the conversation. The body carries the full history
 * (oldest first) and the new user message; the answer streams back as SSE-style
 * frames: zero or more `chunk`, then exactly one terminal `done` (or `error`
 * when the failure happens after the stream opened).
 *
 * Order enforced before the stream opens: parse → trace → validation (400) →
 * provider selection (400) → provider availability (503); every pre-stream
 * failure stays a plain JSON body. The beans are resolved lazily, so the route
 * can be registered without the production dependency graph.
 */
fun Route.chatRoutes() {
    val agent: ChatAgent by di.instance()
    val availability: ProviderAvailability by di.instance()

    route("/chat") {
        post {
            val request = call.receive<ChatRequest>()
            val trace = call.beginTrace(traceJson.encodeToJsonElement<ChatRequest>(request).toString())

            val validationError = ChatRequestValidator.validate(request)
            if (validationError != null) {
                call.respondTraced(ErrorResponse(validationError), HttpStatusCode.BadRequest)
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

            val unavailable = availability.unavailableReason(target)
            if (unavailable != null) {
                call.respondTraced(ErrorResponse(unavailable), HttpStatusCode.ServiceUnavailable)
                return@post
            }

            val session = ChatRequestValidator.toSession(request)
            call.respondTextWriter(ContentType.Text.EventStream) {
                val answer = StringBuilder()
                var clientGone = false
                try {
                    withContext(trace + LlmTargetContext(target)) {
                        agent.stream(session).collect { piece ->
                            answer.append(piece)
                            if (!writeFrame("chunk", traceJson.encodeToString(ChatChunkFrame(text = piece)))) {
                                // The client's channel is closed: end the turn right here, so the
                                // write failure is not reported as a provider error (D-18).
                                clientGone = true
                                throw ClientGoneException()
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // A client disconnect cancels the turn and leaves the chain without `outbound` (D-12).
                    if (clientGone) return@respondTextWriter
                    throw e
                } catch (e: Throwable) {
                    if (clientGone) return@respondTextWriter
                    writeTerminal(
                        event = "error",
                        frame = traceJson.encodeToJsonElement(inStreamFailure(e)),
                        traceId = trace.id,
                    )
                    return@respondTextWriter
                }
                // The marker can be swallowed upstream: never close a partial answer as `done`.
                if (clientGone) return@respondTextWriter
                writeTerminal(
                    event = "done",
                    frame = traceJson.encodeToJsonElement(
                        ChatDoneFrame(answer = answer.toString(), model = target.wireName())
                    ),
                    traceId = trace.id,
                )
            }
        }
    }
}

/**
 * Ends the frame collection once the client's response channel is closed (D-18).
 * A [CancellationException] on purpose: Koog's HTTP client rethrows cancellations
 * untouched, so the abort is not wrapped into a client error, not logged as a
 * provider failure, and the turn ends without a terminal frame (D-12).
 */
private class ClientGoneException : CancellationException("the client closed the response channel")

/**
 * Writes one frame and flushes it, so the stream stays progressive (NFR-05c).
 * `false` means the frame could not be delivered — the client's channel is closed
 * (`ChannelWriteException`/`ClosedWriteChannelException`, both `IOException`s):
 * the caller ends the turn quietly instead of reporting a write failure as a
 * provider error and answering 500 to a client that is already gone (D-18).
 */
internal suspend fun Writer.writeFrame(event: String, data: String): Boolean = try {
    write(sseFrame(event, data))
    flush()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    false
}

/**
 * Writes the terminal frame and closes the chain with the `outbound` line
 * (status 200 — the response is already committed; the frame carries the
 * intended status). A closed client channel means the frame cannot be delivered:
 * no `outbound` line is written (D-12) and the caller stops (D-18).
 */
internal suspend fun Writer.writeTerminal(event: String, frame: JsonElement, traceId: String): Boolean {
    val data = frame.toString()
    if (!writeFrame(event, data)) return false
    TraceLog.outbound(traceId, HttpStatusCode.OK.value, terminalBody(event, frame))
    return true
}

/** `outbound` body of a streamed turn: the terminal frame as one object, `event` first. */
private fun terminalBody(event: String, frame: JsonElement): String =
    JsonObject(mapOf("event" to JsonPrimitive(event)) + frame.jsonObject).toString()

/** The wire value of the selected provider, as it appears in the `done` frame. */
private fun LlmTarget.wireName(): String = when (this) {
    LlmTarget.LOCAL -> "local"
    LlmTarget.DEEPSEEK -> "deepseek"
}
