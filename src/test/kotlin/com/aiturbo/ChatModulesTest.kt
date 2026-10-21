package com.aiturbo

import ai.koog.agents.core.tools.ToolRegistry
import com.aiturbo.chat.ChatAgent
import com.aiturbo.chat.KoogChatAgent
import com.aiturbo.db.DbConfig
import com.aiturbo.db.StageDbConfig
import com.aiturbo.llm.LlmTarget
import com.aiturbo.llm.ProviderAvailability
import com.aiturbo.tools.FindFuelingTool
import com.aiturbo.tools.GetWeatherTool
import com.aiturbo.tools.WebSearchConfig
import com.aiturbo.tools.WebSearchTool
import com.aiturbo.weather.WeatherConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Resolves the composed production graph (weather + fueling + chat) offline.
 * Nothing connects at build time, so the graph must come up with no database,
 * no stage database and no API key.
 */
class ChatModulesTest {

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
        import(chatModule(webSearch = WebSearchConfig()))
    }

    @Test
    fun `the chat registry holds all three tools while the other registries stay single-tool`() {
        val di = graph()

        assertEquals(
            listOf("get_weather", "find_fueling", "web_search"),
            di.direct.instance<ToolRegistry>(tag = CHAT_TOOL_REGISTRY).tools.map { it.name },
        )
        assertEquals(listOf("get_weather"), di.direct.instance<ToolRegistry>().tools.map { it.name })
        assertEquals(
            listOf("find_fueling"),
            di.direct.instance<ToolRegistry>(tag = FUELING_TOOL_REGISTRY).tools.map { it.name },
        )
    }

    @Test
    fun `the chat registry shares the tool singletons instead of copying them`() {
        val di = graph()
        val registry = di.direct.instance<ToolRegistry>(tag = CHAT_TOOL_REGISTRY)

        assertSame(di.direct.instance<GetWeatherTool>(), registry.getTool("get_weather"))
        assertSame(di.direct.instance<FindFuelingTool>(), registry.getTool("find_fueling"))
        assertSame(di.direct.instance<WebSearchTool>(), registry.getTool("web_search"))
    }

    @Test
    fun `the agent binding resolves to the Koog chat agent over the chat registry`() {
        val di = graph(apiKey = "test-key")

        assertTrue(di.direct.instance<ChatAgent>() is KoogChatAgent)
    }

    @Test
    fun `a blank key still builds the graph and flips only the deepseek availability flag`() {
        val di = graph(apiKey = "")
        val availability = di.direct.instance<ProviderAvailability>()

        assertTrue(di.direct.instance<ChatAgent>() is KoogChatAgent)
        assertFalse(availability.deepseekConfigured)
        assertNull(availability.unavailableReason(LlmTarget.LOCAL))
        assertEquals(
            "DeepSeek API key is not configured",
            availability.unavailableReason(LlmTarget.DEEPSEEK),
        )
    }

    @Test
    fun `a configured key makes the deepseek target available`() {
        val availability = graph(apiKey = "test-key").direct.instance<ProviderAvailability>()

        assertTrue(availability.deepseekConfigured)
        assertNull(availability.unavailableReason(LlmTarget.DEEPSEEK))
    }
}
