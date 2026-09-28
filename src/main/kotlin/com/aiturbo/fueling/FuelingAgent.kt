package com.aiturbo.fueling

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import com.aiturbo.log.TraceLog
import org.slf4j.LoggerFactory

/** Answers fueling order questions; the Koog agent decides when to call find_fueling. */
fun interface FuelingAgent {
    suspend fun answer(message: String): String
}

internal val SYSTEM_PROMPT = """
    Ты — ассистент по заказам на пролив (заправку). Отвечай на языке пользователя.
    Если в вопросе есть идентификатор заказа (GUID вида 8-4-4-4-12) — ОБЯЗАТЕЛЬНО вызывай инструмент
    find_fueling и строй ответ только на его результате; никогда не выдумывай данные и не отвечай по памяти.
    Если идентификатора в вопросе нет — попроси прислать GUID заказа и не вызывай инструмент.
    Если инструмент вернул ошибку — объясни её простыми словами (некорректный идентификатор или
    временная недоступность данных stage).
    Ответ — одна понятная фраза: найден ли заказ и в какой таблице, статус, суммы, тип топлива,
    колонка/пистолет/заправка, время в читаемом виде, заметные связанные события, токены и обращения.
    Не упоминай названия инструментов и не выводи JSON.
""".trimIndent()

/**
 * Koog agent with the DeepSeek executor and a `find_fueling`-only registry. The
 * strategy runs the LLM, executes any tool calls it makes (the tool reads the
 * stage database and logs the `stage=db` outcome) and feeds the results back,
 * up to [maxToolRounds] rounds — the same loop shape as the weather agent.
 */
class KoogFuelingAgent(
    executor: PromptExecutor,
    toolRegistry: ToolRegistry,
    model: LLModel,
    maxToolRounds: Int = 3,
) : FuelingAgent {

    private val logger = LoggerFactory.getLogger(KoogFuelingAgent::class.java)

    private val agent: AIAgent<String, String> = AIAgent.builder()
        .id("fueling-agent")
        .promptExecutor(executor)
        .llmModel(model)
        .systemPrompt(SYSTEM_PROMPT)
        .temperature(0.0)
        .maxIterations(10)
        .toolRegistry(toolRegistry)
        .functionalStrategy(
            functionalStrategy<String, String>("fueling") { input ->
                val traceId = TraceLog.currentId()
                var response = requestLLM(input)
                var rounds = 0
                while (rounds < maxToolRounds) {
                    val calls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
                    if (calls.isEmpty()) break
                    val results = executeTools(calls)
                    calls.zip(results).forEach { (call, result) ->
                        TraceLog.tool(traceId, call.tool, call.args, result.output, result.toMessagePart().isError)
                    }
                    response = sendToolResults(results)
                    rounds++
                }
                if (response.parts.filterIsInstance<MessagePart.Tool.Call>().isNotEmpty()) {
                    logger.warn("Tool loop did not converge after $maxToolRounds rounds")
                }
                response.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
                    .ifBlank { "Не удалось сформировать ответ." }
            },
        )
        .build()

    override suspend fun answer(message: String): String = agent.run(message)
}
