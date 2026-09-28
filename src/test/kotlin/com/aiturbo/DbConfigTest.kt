package com.aiturbo

import com.aiturbo.db.DbConfig
import com.aiturbo.db.resolveDbPassword
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class DbConfigTest {

    @Test
    fun `from reads values from the configuration`() {
        val db = DbConfig.from(
            MapApplicationConfig(
                "db.host" to "db.example.com",
                "db.port" to "5544",
                "db.name" to "weather",
                "db.user" to "app",
                "db.password" to "secret",
            )
        )

        assertEquals("db.example.com", db.host)
        assertEquals(5544, db.port)
        assertEquals("weather", db.database)
        assertEquals("app", db.user)
        assertEquals("secret", db.password)
        assertEquals("jdbc:postgresql://db.example.com:5544/weather", db.jdbcUrl)
    }

    @Test
    fun `from falls back to the local Docker defaults`() {
        val db = DbConfig.from(MapApplicationConfig())

        assertEquals("localhost", db.host)
        assertEquals(5439, db.port)
        assertEquals("mydb2", db.database)
        assertEquals("myuser", db.user)
        assertEquals("jdbc:postgresql://localhost:5439/mydb2", db.jdbcUrl)
    }

    @Test
    fun `from falls back to the default port when it is not numeric`() {
        val db = DbConfig.from(MapApplicationConfig("db.port" to "not-a-port"))

        assertEquals(5439, db.port)
    }

    @Test
    fun `config password value takes priority over env and file`() {
        assertEquals("from-config", resolveDbPassword("from-config", "from-env", "from-file"))
    }

    @Test
    fun `env password is used when the config value is blank`() {
        assertEquals("from-env", resolveDbPassword("", "from-env", "from-file"))
        assertEquals("from-env", resolveDbPassword("   ", "from-env", "from-file"))
    }

    @Test
    fun `dotenv password is used when config and env are blank`() {
        assertEquals("from-file", resolveDbPassword("", "", "from-file"))
        assertEquals("from-file", resolveDbPassword("", null, "from-file"))
    }

    @Test
    fun `local development default when nothing is configured`() {
        assertEquals(DbConfig.DEFAULT_PASSWORD, resolveDbPassword("", null, null))
    }
}
