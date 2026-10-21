package com.aiturbo.chat

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import com.aiturbo.llm.StreamedAssistant
import com.aiturbo.log.TraceLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one Russian system prompt of the chat slice (FR-11): a general assistant that answers any
 * topic, routing weather questions to `get_weather`, fueling questions to `find_fueling` and
 * anything needing fresh or external facts to `web_search`.
 */
internal val CHAT_SYSTEM_PROMPT = """
    Ты — дружелюбный ассистент: общайся на любые темы и отвечай на языке пользователя.
    На любой вопрос о погоде ОБЯЗАТЕЛЬНО вызывай инструмент get_weather
    и строй ответ только на его результате — данные погоды никогда не выдумывай.
    Если вопрос про заказ на пролив (заправку) и содержит идентификатор заказа (GUID вида 8-4-4-4-12) —
    ОБЯЗАТЕЛЬНО вызывай инструмент find_fueling и строй ответ только на его результате;
    данные заказов никогда не выдумывай и не отвечай по памяти.
    Если вопрос про заказ, а идентификатора в нём нет — попроси прислать GUID заказа и не вызывай инструмент.
    Если для ответа нужны свежие или внешние сведения (новости, матчи, расписания, события, цены) —
    ОБЯЗАТЕЛЬНО вызывай инструмент web_search и строй ответ на его результате; не выдумывай факты.
    На прочие темы отвечай по своим знаниям.
    Используй предыдущие сообщения разговора: если город или заказ уже названы, не переспрашивай.
    Если инструмент вернул ошибку — объясни её простыми словами (некорректный идентификатор или
    временная недоступность данных).
    Не упоминай названия инструментов и не выводи JSON.
""".trimIndent()

/** The turn text shown when no provider round produced any visible text. */
internal const val CHAT_FALLBACK_ANSWER = "Не удалось сформировать ответ."

/** Everything one `POST /chat` turn needs: the replayed history, the new message and the sink. */
private data class ChatInput(
    val history: List<ChatTurn>,
    val message: String,
    val emit: suspend (String) -> Unit,
)

/**
 * Koog agent over the chat tool registry. Round 0 is the tool round and never
 * streams (its reply is appended to the prompt by `requestLLM` itself); every
 * round after tool results streams through `requestLLMStreaming`, which appends
 * only the tool results and *not* the reply, so the assembled assistant message
 * is appended by hand (D-04).
 */
class KoogChatAgent(
    executor: PromptExecutor,
    toolRegistry: ToolRegistry,
    model: LLModel,
    maxToolRounds: Int = 3,
) : ChatAgent {

    private val logger = LoggerFactory.getLogger(KoogChatAgent::class.java)

    private val agent: AIAgent<ChatInput, String> = AIAgent.builder()
        .id("chat-agent")
        .promptExecutor(executor)
        .llmModel(model)
        .systemPrompt(CHAT_SYSTEM_PROMPT)
        .temperature(0.0)
        .maxIterations(10)
        .toolRegistry(toolRegistry)
        .functionalStrategy(
            functionalStrategy<ChatInput, String>("chat") { input ->
                val traceId = TraceLog.currentId()
                appendPrompt {
                    input.history.forEach { turn ->
                        when (turn.role) {
                            ChatTurnRole.USER -> user(turn.content)
                            ChatTurnRole.ASSISTANT -> assistant(turn.content)
                        }
                    }
                }
                var response = requestLLM(input.message)
                if (response.parts.filterIsInstance<MessagePart.Tool.Call>().isEmpty()) {
                    // A direct answer arrives as one piece: round 0 never streams (D-04).
                    val answer = visibleText(response).ifBlank { CHAT_FALLBACK_ANSWER }
                    input.emit(answer)
                    return@functionalStrategy answer
                }
                var rounds = 0
                while (rounds < maxToolRounds) {
                    val calls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
                    if (calls.isEmpty()) break
                    val results = executeTools(calls)
                    calls.zip(results).forEach { (call, result) ->
                        TraceLog.tool(traceId, call.tool, call.args, result.output, result.toMessagePart().isError)
                    }
                    val collected = StreamedAssistant()
                    val frames = llm.writeSession {
                        appendPrompt { results.forEach { toolResult(it.toMessagePart()) } }
                        requestLLMStreaming()
                    }
                    frames.collect { frame ->
                        collected.accept(frame)
                        if (frame is StreamFrame.TextDelta && frame.text.isNotEmpty()) {
                            input.emit(frame.text)
                        }
                    }
                    response = collected.toMessage()
                    if (response.parts.isNotEmpty()) {
                        appendPrompt { message(response) }
                    }
                    rounds++
                }
                if (response.parts.filterIsInstance<MessagePart.Tool.Call>().isNotEmpty()) {
                    logger.warn("Tool loop did not converge after $maxToolRounds rounds")
                }
                visibleText(response).ifBlank { CHAT_FALLBACK_ANSWER }
            },
        )
        .build()

    override fun stream(session: ChatSession): Flow<String> = channelFlow {
        val emitted = AtomicBoolean(false)
        val input = ChatInput(session.history, session.message) { text ->
            emitted.set(true)
            send(text)
        }
        val answer = agent.run(input)
        if (!emitted.get()) send(answer)
    }

    private fun visibleText(message: Message.Assistant): String =
        message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
}
