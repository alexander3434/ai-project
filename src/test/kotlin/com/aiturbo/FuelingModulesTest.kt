package com.aiturbo

import ai.koog.agents.core.tools.ToolRegistry
import com.aiturbo.db.DbConfig
import com.aiturbo.db.ExposedStageFuelingRepository
import com.aiturbo.db.StageDbConfig
import com.aiturbo.db.StageFuelingRepository
import com.aiturbo.fueling.FuelingAgent
import com.aiturbo.fueling.KoogFuelingAgent
import com.aiturbo.tools.FindFuelingTool
import com.aiturbo.tools.ToolSpec
import com.aiturbo.tools.ToolSpecLoader
import com.aiturbo.weather.WeatherConfig
import com.aiturbo.weather.WeatherUnavailableException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Resolves the composed production graph (weather + fueling) offline. The
 * stage repository opens its connection per call, so building the graph must
 * work with no database, no API key and no network.
 */
class FuelingModulesTest {

    private val db = DbConfig(host = "localhost", port = 5439, database = "mydb2", user = "myuser", password = "secret")

    private val stageDb = StageDbConfig(
        host = "stage.example",
        port = 5432,
        database = "fueling",
        user = "fueling",
        password = "stage-secret",
    )

    private val deepseek = DeepseekConfig(baseUrl = "https://api.deepseek.com", apiKey = "", model = "deepseek-flash")

    private fun graph(apiKey: String = deepseek.apiKey): DI = DI {
        import(appModules(deepseek.copy(apiKey = apiKey), OllamaConfig(), db, WeatherConfig()))
        import(fuelingModule(stageDb = stageDb, apiKeyConfigured = apiKey.isNotBlank()))
    }

    @Test
    fun `the composed graph resolves offline and keeps both registries apart`() {
        val di = graph()

        assertEquals(listOf("get_weather"), di.direct.instance<ToolRegistry>().tools.map { it.name })
        assertEquals(
            listOf("find_fueling"),
            di.direct.instance<ToolRegistry>(tag = FUELING_TOOL_REGISTRY).tools.map { it.name },
        )
        assertNotSame(di.direct.instance<ToolRegistry>(), di.direct.instance<ToolRegistry>(tag = FUELING_TOOL_REGISTRY))
    }

    @Test
    fun `both tool specs are loaded once and come from their own resources`() {
        LogCapture("com.aiturbo.tools.ToolSpecLoader").use { capture ->
            val di = graph()
            assertEquals(2, capture.lines().size, capture.lines().toString())

            val weatherSpec = di.direct.instance<ToolSpec>()
            val fuelingSpec = di.direct.instance<ToolSpec>(tag = FUELING_TOOL_SPEC)
            assertEquals("get_weather", weatherSpec.name)
            assertEquals(ToolSpecLoader.load().name, weatherSpec.name)
            assertEquals(ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH).name, fuelingSpec.name)
            assertEquals("find_fueling", fuelingSpec.name)
            assertEquals(
                ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH).description,
                di.direct.instance<FindFuelingTool>().descriptor.description,
            )
            assertEquals("find_fueling", di.direct.instance<FindFuelingTool>().descriptor.name)
        }
    }

    @Test
    fun `the stage repository is the exposed implementation and is not touched at startup`() {
        val di = graph()

        assertTrue(di.direct.instance<StageFuelingRepository>() is ExposedStageFuelingRepository)
    }

    @Test
    fun `a blank key yields the unavailable fueling agent lambda`() = runTest {
        val di = graph(apiKey = "")
        val agent = di.direct.instance<FuelingAgent>()

        val error = assertFailsWith<WeatherUnavailableException> { agent.answer("Найди заказ") }
        assertTrue(error.message!!.contains("API key"), error.message)
    }

    @Test
    fun `a configured key yields the Koog fueling agent bound to the find_fueling registry`() {
        val di = graph(apiKey = "test-key")

        assertTrue(di.direct.instance<FuelingAgent>() is KoogFuelingAgent)
    }
}
