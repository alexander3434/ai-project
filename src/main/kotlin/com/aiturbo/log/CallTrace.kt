package com.aiturbo.log

import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Correlation id of one HTTP request. It travels in the coroutine context so the
 * route handler, the Koog agent strategy, the prompt-executor decorator and the
 * weather tool all print the same `req=<id>` marker. (MDC is thread-local and
 * would be lost on the dispatcher hops the agent and the executor make.)
 */
class CallTrace(val id: String) : AbstractCoroutineContextElement(CallTrace) {

    companion object Key : CoroutineContext.Key<CallTrace>

    override fun toString(): String = id
}

/** Short, log-friendly correlation id (8 characters). */
fun newTraceId(): String = UUID.randomUUID().toString().take(8)
