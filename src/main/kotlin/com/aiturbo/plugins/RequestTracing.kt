package com.aiturbo.plugins

import com.aiturbo.log.CallTrace
import com.aiturbo.log.TraceLog
import com.aiturbo.log.newTraceId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

private val TraceAttribute = AttributeKey<CallTrace>("aiturbo.trace")

/** Compact serializer used only to render a body for the log line. */
@PublishedApi
internal val traceJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Starts the log chain of the current call: creates the correlation id, stores
 * it on the call and emits the `stage=inbound` line. Idempotent — a handler and
 * a StatusPages handler may both call it.
 */
fun ApplicationCall.beginTrace(body: String? = null): CallTrace {
    attributes.getOrNull(TraceAttribute)?.let { return it }

    val trace = CallTrace(newTraceId())
    attributes.put(TraceAttribute, trace)
    // A logging failure must never break the request (NFR-04).
    runCatching {
        val origin = request.origin
        TraceLog.inbound(
            id = trace.id,
            method = request.httpMethod.value,
            path = request.path(),
            query = request.queryString().ifBlank { null },
            client = "${origin.remoteHost}:${origin.remotePort}",
            body = body,
        )
    }
    return trace
}

/** Trace of the current call, or null when the handler never called [beginTrace]. */
fun ApplicationCall.traceOrNull(): CallTrace? = attributes.getOrNull(TraceAttribute)

/** Logs `stage=outbound` (status and JSON body) and then responds unchanged. */
suspend inline fun <reified T : Any> ApplicationCall.respondTraced(
    body: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    val id = traceOrNull()?.id ?: beginTrace().id
    // Rendering for the log is best-effort: it can never change the response.
    runCatching {
        TraceLog.outbound(id, status.value, traceJson.encodeToJsonElement<T>(body).toString())
    }
    respond(status, body)
}
