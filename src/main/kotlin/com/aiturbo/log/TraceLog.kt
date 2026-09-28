package com.aiturbo.log

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.message.Message
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.coroutineContext

/**
 * The single logger for the request chain. Every entry is one line shaped
 * `req=<id> stage=<marker> <key=value …>` on the dedicated logger
 * [LOGGER_NAME], so one request can be followed stage by stage:
 *
 * `inbound` (Ktor) → `deepseek-request` / `deepseek-response` (Koog executor
 * decorator) → `tool` (agent strategy) / `db` (tool) → `outbound` (Ktor).
 *
 * No configuration object, header or secret is ever passed here — only the
 * endpoint label, model ids and the payloads themselves (NFR-02).
 */
object TraceLog {

    const val LOGGER_NAME = "com.aiturbo.trace"
    const val MAX_BODY_CHARS = 4096

    private const val MISSING_ID = "-"

    val logger: Logger = LoggerFactory.getLogger(LOGGER_NAME)

    /** Correlation id of the current request, or null outside a traced call. */
    suspend fun currentId(): String? = coroutineContext[CallTrace]?.id

    /** Stage (a): the HTTP request that arrived, body included when parsed. */
    fun inbound(id: String, method: String, path: String, query: String?, client: String?, body: String?) {
        logger.info(
            "req=$id stage=inbound method=$method path=$path query=${query ?: MISSING_ID} " +
                "client=${client ?: MISSING_ID}" + (body?.let { " body=${truncate(it)}" } ?: "")
        )
    }

    /** Stage (b): the request about to be sent to DeepSeek. */
    fun deepseekRequest(
        id: String?,
        endpoint: String,
        model: String,
        messages: List<Message>,
        toolsCount: Int,
        tools: String,
        toolChoice: String?,
        streaming: Boolean = false,
    ) {
        logger.info(
            "req=${id ?: MISSING_ID} stage=deepseek-request endpoint=$endpoint model=$model " +
                "tool_choice=${toolChoice ?: MISSING_ID} tools_count=$toolsCount " +
                "tools=${truncate(tools)} messages=${truncate(renderMessages(messages))}" +
                if (streaming) " streaming=true" else ""
        )
    }

    /** Stage (c): the model's answer, text and requested tool calls. */
    fun deepseekResponse(id: String?, model: String, response: Message.Assistant) {
        logger.info(
            "req=${id ?: MISSING_ID} stage=deepseek-response model=$model " +
                truncate(renderAssistant(response))
        )
    }

    /** Stage (c) on failure: the same marker with the error instead of an answer. */
    fun deepseekFailure(id: String?, model: String, error: Throwable) {
        logger.info(
            "req=${id ?: MISSING_ID} stage=deepseek-response model=$model " +
                "error=${truncate(describeError(error))}"
        )
    }

    /** Stage (d): one tool invocation with its arguments and result. */
    fun tool(id: String?, name: String, args: String, result: String, isError: Boolean) {
        logger.info(
            "req=${id ?: MISSING_ID} stage=tool tool=$name args=${truncate(args)} is_error=$isError " +
                "result=\"${truncate(result)}\""
        )
    }

    /** Tool-internal detail: whether the database insert succeeded. */
    fun toolDb(id: String?, tool: String, saved: Boolean, recordId: Int?, reason: String?) {
        logger.info(
            "req=${id ?: MISSING_ID} stage=db tool=$tool saved=$saved" +
                (recordId?.let { " id=$it" } ?: "") +
                (reason?.let { " reason=${truncate(it)}" } ?: "")
        )
    }

    /**
     * Stage (d) for the fueling lookup: at least one match. [tables] are the
     * source tables that held the order (the record count), [events]/[tokens]/
     * [feedback] the related-row counts; [capped] marks that the rendered
     * related rows were cut at the render cap.
     */
    fun fuelingLookupFound(
        id: String?,
        tool: String,
        tables: List<String>,
        events: Int,
        tokens: Int,
        feedback: Int,
        capped: Boolean,
    ) {
        logger.info(
            "req=${id ?: MISSING_ID} stage=db tool=$tool lookup=found records=${tables.size} " +
                "tables=${renderTables(tables)} events=$events tokens=$tokens feedback=$feedback" +
                if (capped) " capped=true" else ""
        )
    }

    /** Stage (d) for the fueling lookup: nothing matched, no related rows were fetched. */
    fun fuelingLookupNotFound(id: String?, tool: String, tables: List<String>) {
        logger.info(
            "req=${id ?: MISSING_ID} stage=db tool=$tool lookup=not_found records=0 " +
                "tables=${renderTables(tables)}"
        )
    }

    /** Stage (d) for the fueling lookup: the stage database could not be read (FR-12). */
    fun fuelingLookupUnavailable(id: String?, tool: String, reason: String?) {
        logger.info(
            "req=${id ?: MISSING_ID} stage=db tool=$tool lookup=unavailable " +
                "reason=\"${truncate(reason ?: MISSING_ID)}\""
        )
    }

    /** Comma-joined table names, `-` when there is nothing to name. */
    private fun renderTables(tables: List<String>): String =
        if (tables.isEmpty()) MISSING_ID else tables.joinToString(",")

    /** Stage (e): the response sent back to the client. */
    fun outbound(id: String, status: Int, body: String) {
        logger.info("req=$id stage=outbound status=$status body=${truncate(body)}")
    }

    /** Caps a body-like field and marks the truncation explicitly (NFR-03). */
    internal fun truncate(text: String, max: Int = MAX_BODY_CHARS): String =
        if (text.length <= max) text else text.take(max) + "…[truncated, ${text.length} chars total]"

    /** Renders a throwable (and its causes) as `SimpleName: message` for the log. */
    internal fun describeError(t: Throwable): String = buildString {
        var current: Throwable? = t
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            if (isNotEmpty()) append(" <- ")
            append(current::class.simpleName).append(": ").append(current.message.orEmpty())
            if (current is KoogHttpClientException) {
                current.statusCode?.let { append(" status=").append(it) }
                current.errorBody?.let { append(" body=").append(truncate(it)) }
            }
            current = current.cause
        }
    }
}
