package com.aiturbo

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class DeepseekConfigTest {

    @Test
    fun `config value takes priority over env and file`() {
        assertEquals("from-config", resolveApiKey("from-config", "from-env", "from-file"))
    }

    @Test
    fun `env variable is used when config value is blank`() {
        assertEquals("from-env", resolveApiKey("", "from-env", "from-file"))
        assertEquals("from-env", resolveApiKey("   ", "from-env", "from-file"))
    }

    @Test
    fun `dotenv file value is used when config and env are blank`() {
        assertEquals("from-file", resolveApiKey("", "", "from-file"))
        assertEquals("from-file", resolveApiKey("", null, "from-file"))
    }

    @Test
    fun `empty key when nothing is configured`() {
        assertEquals("", resolveApiKey("", null, null))
    }

    @Test
    fun `the default model is the cheapest deepseek tier`() {
        assertEquals("deepseek-flash", DeepseekConfig.DEFAULT_MODEL)
        assertEquals("deepseek-flash", DeepseekConfig.from(MapApplicationConfig()).model)
    }

    @Test
    fun `an explicit model value wins over the default`() {
        val config = DeepseekConfig.from(MapApplicationConfig("deepseek.model" to "deepseek-chat"))

        assertEquals("deepseek-chat", config.model)
    }
}
