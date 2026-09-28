package com.aiturbo.weather

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import com.aiturbo.log.TraceLog
import org.slf4j.LoggerFactory

/** Answers weather questions; the Koog agent decides when to call its tools. */
fun interface WeatherAgent {
    suspend fun answer(message: String): String
}

private val SYSTEM_PROMPT = """
    Ты — ассистент по погоде. Отвечай на языке пользователя.
    На любой вопрос о погоде ОБЯЗАТЕЛЬНО вызывай инструмент get_weather
    и строй ответ только на его результате — никогда не выдумывай данные.
    Не упоминай названия инструментов и не выводи JSON.
""".trimIndent()

/**
 * Koog agent with a DeepSeek executor and the get_weather tool. The strategy
 * runs the LLM, executes any tool calls it makes (the tool records the request
 * in Postgres) and feeds the results back, up to [maxToolRounds] rounds.
 */
class KoogWeatherAgent(
    executor: PromptExecutor,
    toolRegistry: ToolRegistry,
    model: LLModel,
    maxToolRounds: Int = 3,
) : WeatherAgent {

    private val logger = LoggerFactory.getLogger(KoogWeatherAgent::class.java)

    private val agent: AIAgent<String, String> = AIAgent.builder()
        .id("weather-agent")
        .promptExecutor(executor)
        .llmModel(model)
        .systemPrompt(SYSTEM_PROMPT)
        .temperature(0.0)
        .maxIterations(10)
        .toolRegistry(toolRegistry)
        .functionalStrategy(
            functionalStrategy<String, String>("weather") { input ->
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
