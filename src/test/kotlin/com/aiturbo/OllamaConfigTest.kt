package com.aiturbo

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaConfigTest {

    @Test
    fun `defaults are used without an ollama section`() {
        val config = OllamaConfig.from(MapApplicationConfig())

        assertEquals("http://localhost:11434", config.baseUrl)
        assertEquals("qwen3:8b", config.model)
        assertEquals(120, config.timeoutSeconds)
        assertEquals(OllamaConfig.DEFAULT_BASE_URL, config.baseUrl)
        assertEquals(OllamaConfig.DEFAULT_MODEL, config.model)
        assertEquals(OllamaConfig.DEFAULT_TIMEOUT_SECONDS, config.timeoutSeconds)
    }

    @Test
    fun `the ollama section overrides every value`() {
        val config = OllamaConfig.from(
            MapApplicationConfig(
                "ollama.baseUrl" to "http://ollama.example:11434",
                "ollama.model" to "llama3.1:8b",
                "ollama.timeoutSeconds" to "300",
            ),
        )

        assertEquals("http://ollama.example:11434", config.baseUrl)
        assertEquals("llama3.1:8b", config.model)
        assertEquals(300, config.timeoutSeconds)
    }

    @Test
    fun `a malformed timeout falls back to the default`() {
        val config = OllamaConfig.from(MapApplicationConfig("ollama.timeoutSeconds" to "soon"))

        assertEquals(OllamaConfig.DEFAULT_TIMEOUT_SECONDS, config.timeoutSeconds)
    }

    @Test
    fun `the chat endpoint label is ollama's api path`() {
        assertEquals("http://localhost:11434/api/chat", OllamaConfig().chatEndpoint)
        assertEquals("http://host:11434/api/chat", OllamaConfig(baseUrl = "http://host:11434/").chatEndpoint)
    }
}
