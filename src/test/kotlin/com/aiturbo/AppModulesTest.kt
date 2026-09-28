package com.aiturbo

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.aiturbo.db.DbConfig
import com.aiturbo.llm.ProviderRoutingPromptExecutor
import com.aiturbo.log.LoggingPromptExecutor
import com.aiturbo.time.CompositeTimeZoneResolver
import com.aiturbo.time.TimeZoneResolver
import com.aiturbo.tools.GetWeatherTool
import com.aiturbo.tools.ToolSpec
import com.aiturbo.tools.ToolSpecLoader
import com.aiturbo.weather.KoogWeatherAgent
import com.aiturbo.weather.WeatherAgent
import com.aiturbo.weather.WeatherConfig
import com.aiturbo.weather.WeatherUnavailableException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Resolves the production dependency graph offline. Nothing connects: the
 * repository and the HTTP clients open their connections per call, so
 * building the graph must work without a database or an API key.
 */
class AppModulesTest {

    private val db = DbConfig(host = "localhost", port = 5439, database = "mydb2", user = "myuser", password = "secret")

    private val deepseek = DeepseekConfig(baseUrl = "https://api.deepseek.com", apiKey = "", model = "deepseek-chat")

    private fun graph(apiKey: String = deepseek.apiKey): DI =
        DI { import(appModules(deepseek.copy(apiKey = apiKey), OllamaConfig(), db, WeatherConfig())) }

    @Test
    fun `the production graph resolves offline without a cycle`() {
        val di = graph()

        // Resolved first on purpose: with an eager descriptor provider this would
        // hit resolver -> ToolRegistry -> GetWeatherTool -> resolver and fail.
        assertTrue(di.direct.instance<TimeZoneResolver>() is CompositeTimeZoneResolver)

        assertTrue(di.direct.instance<PromptExecutor>() is ProviderRoutingPromptExecutor)
        assertEquals(listOf("get_weather"), di.direct.instance<ToolRegistry>().tools.map { it.name })
        assertEquals(ToolSpecLoader.load().description, di.direct.instance<GetWeatherTool>().descriptor.description)
        assertEquals(ToolSpecLoader.load().name, di.direct.instance<ToolSpec>().name)
    }

    @Test
    fun `both provider executors are bound under their tags with their endpoint labels`() {
        val di = graph(apiKey = "test-key")

        val deepseek = di.direct.instance<PromptExecutor>(tag = DEEPSEEK_EXECUTOR_TAG)
        val local = di.direct.instance<PromptExecutor>(tag = LOCAL_EXECUTOR_TAG)
        assertTrue(deepseek is LoggingPromptExecutor, deepseek::class.toString())
        assertTrue(local is LoggingPromptExecutor, local::class.toString())

        assertEquals("https://api.deepseek.com/chat/completions", (deepseek as LoggingPromptExecutor).endpoint)
        assertEquals("http://localhost:11434/api/chat", local.endpoint)
        assertNotSame(deepseek, local)
        assertSame(di.direct.instance<PromptExecutor>(tag = DEEPSEEK_EXECUTOR_TAG), deepseek)
        assertSame(di.direct.instance<PromptExecutor>(tag = LOCAL_EXECUTOR_TAG), local)
    }

    @Test
    fun `the ollama configuration shapes the local executor and model`() {
        val ollama = OllamaConfig(baseUrl = "http://ollama.example:11434/", model = "llama3.1:8b")
        val di = DI { import(appModules(deepseek.copy(apiKey = "test-key"), ollama, db, WeatherConfig())) }

        val local = di.direct.instance<PromptExecutor>(tag = LOCAL_EXECUTOR_TAG)
        assertTrue(local is LoggingPromptExecutor, local::class.toString())

        assertEquals("http://ollama.example:11434/api/chat", (local as LoggingPromptExecutor).endpoint)
    }

    @Test
    fun `the tool spec is loaded once and reused`() {
        LogCapture("com.aiturbo.tools.ToolSpecLoader").use { capture ->
            val di = graph()
            assertEquals(1, capture.lines().size, capture.lines().toString())
            assertSame(di.direct.instance<ToolSpec>(), di.direct.instance<ToolSpec>())
        }
    }

    @Test
    fun `a blank key keeps the agent and fails only on the deepseek path`() = runTest {
        val di = graph(apiKey = "")

        // The agent is unconditional (the local path works without a key)...
        assertTrue(di.direct.instance<WeatherAgent>() is KoogWeatherAgent)

        // ...and the blank-key contract moved into the routing executor: the DeepSeek
        // path throws before any delegate call, the local path is unaffected (FR-09).
        val executor = di.direct.instance<PromptExecutor>()
        val error = assertFailsWith<WeatherUnavailableException> {
            executor.execute(
                prompt = Prompt.build(id = "app-modules-test") { user("Какая погода?") },
                model = di.direct.instance<LLModel>(),
                tools = emptyList(),
            )
        }
        assertTrue(error.message!!.contains("API key"), error.message)
    }

    @Test
    fun `a configured key yields the Koog agent`() {
        val di = graph(apiKey = "test-key")

        assertTrue(di.direct.instance<WeatherAgent>() is KoogWeatherAgent)
    }
}
