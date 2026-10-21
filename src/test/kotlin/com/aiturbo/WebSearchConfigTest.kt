package com.aiturbo

import com.aiturbo.tools.WebSearchConfig
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** The web-search settings are resolved fully offline from a synthetic config. */
class WebSearchConfigTest {

    @Test
    fun `from reads the web search values from the configuration`() {
        val config = WebSearchConfig.from(
            MapApplicationConfig(
                "webSearch.mojeekBaseUrl" to "https://search.example/mojeek",
                "webSearch.baseUrl" to "https://search.example/html/",
                "webSearch.fallbackBaseUrl" to "https://search.example/lite/",
                "webSearch.maxResults" to "3",
                "webSearch.timeoutSeconds" to "7",
            )
        )

        assertEquals("https://search.example/mojeek", config.mojeekBaseUrl)
        assertEquals("https://search.example/html/", config.baseUrl)
        assertEquals("https://search.example/lite/", config.fallbackBaseUrl)
        assertEquals(3, config.maxResults)
        assertEquals(7, config.timeoutSeconds)
    }

    @Test
    fun `the defaults point at the mojeek and duckduckgo endpoints`() {
        val config = WebSearchConfig()

        assertEquals("https://www.mojeek.com/search", config.mojeekBaseUrl)
        assertEquals("https://html.duckduckgo.com/html/", config.baseUrl)
        assertEquals("https://lite.duckduckgo.com/lite/", config.fallbackBaseUrl)
        assertEquals(5, config.maxResults)
        assertEquals(10, config.timeoutSeconds)
    }

    @Test
    fun `a missing block and non-numeric values fall back to the defaults`() {
        val missing = WebSearchConfig.from(MapApplicationConfig())
        val broken = WebSearchConfig.from(
            MapApplicationConfig(
                "webSearch.maxResults" to "many",
                "webSearch.timeoutSeconds" to "soon",
            )
        )

        assertEquals(WebSearchConfig.DEFAULT_MOJEEK_BASE_URL, missing.mojeekBaseUrl)
        assertEquals(WebSearchConfig.DEFAULT_BASE_URL, missing.baseUrl)
        assertEquals(WebSearchConfig.DEFAULT_FALLBACK_BASE_URL, missing.fallbackBaseUrl)
        assertEquals(WebSearchConfig.DEFAULT_MAX_RESULTS, broken.maxResults)
        assertEquals(WebSearchConfig.DEFAULT_TIMEOUT_SECONDS, broken.timeoutSeconds)
    }
}
