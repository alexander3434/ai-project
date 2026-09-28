package com.aiturbo

import com.aiturbo.db.StageDbConfig
import com.aiturbo.db.resolveStageDbPassword
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stage connection settings are resolved fully offline: the values come
 * from a synthetic config and the password resolution is exercised through
 * [resolveStageDbPassword], so no test touches a real .env file, a socket or
 * the stage database.
 */
class StageDbConfigTest {

    @Test
    fun `from reads the stage values from the configuration`() {
        val stage = StageDbConfig.from(
            MapApplicationConfig(
                "stageDb.host" to "stage.example.com",
                "stageDb.port" to "25433",
                "stageDb.name" to "fueling_stage",
                "stageDb.user" to "reader",
                "stageDb.password" to "from-config",
                "stageDb.timeoutSeconds" to "7",
            )
        )

        assertEquals("stage.example.com", stage.host)
        assertEquals(25433, stage.port)
        assertEquals("fueling_stage", stage.database)
        assertEquals("reader", stage.user)
        assertEquals("from-config", stage.password)
        assertEquals(7, stage.timeoutSeconds)
        assertEquals("jdbc:postgresql://stage.example.com:25433/fueling_stage", stage.jdbcUrl)
    }

    @Test
    fun `the defaults match the stage database and the timeout budget`() {
        val stage = StageDbConfig()

        assertEquals("postgres.stage.turboapp.ru", stage.host)
        assertEquals(25432, stage.port)
        assertEquals("fueling", stage.database)
        assertEquals("fueling", stage.user)
        assertEquals("", stage.password)
        assertEquals(10, stage.timeoutSeconds)
        assertEquals("jdbc:postgresql://postgres.stage.turboapp.ru:25432/fueling", stage.jdbcUrl)
    }

    @Test
    fun `from falls back to the defaults when the stage block is absent`() {
        val stage = StageDbConfig.from(MapApplicationConfig())

        assertEquals(StageDbConfig.DEFAULT_HOST, stage.host)
        assertEquals(StageDbConfig.DEFAULT_PORT, stage.port)
        assertEquals(StageDbConfig.DEFAULT_DATABASE, stage.database)
        assertEquals(StageDbConfig.DEFAULT_USER, stage.user)
        assertEquals(StageDbConfig.DEFAULT_TIMEOUT_SECONDS, stage.timeoutSeconds)
    }

    @Test
    fun `non-numeric port and timeout fall back to the defaults`() {
        val stage = StageDbConfig.from(
            MapApplicationConfig(
                "stageDb.port" to "not-a-port",
                "stageDb.timeoutSeconds" to "soon",
            )
        )

        assertEquals(StageDbConfig.DEFAULT_PORT, stage.port)
        assertEquals(StageDbConfig.DEFAULT_TIMEOUT_SECONDS, stage.timeoutSeconds)
    }

    @Test
    fun `config password value takes priority over env and file`() {
        assertEquals("from-config", resolveStageDbPassword("from-config", "from-env", "from-file"))
    }

    @Test
    fun `env password is used when the config value is blank`() {
        assertEquals("from-env", resolveStageDbPassword("", "from-env", "from-file"))
        assertEquals("from-env", resolveStageDbPassword("   ", "from-env", "from-file"))
    }

    @Test
    fun `dotenv password is used when config and env are blank`() {
        assertEquals("from-file", resolveStageDbPassword("", "", "from-file"))
        assertEquals("from-file", resolveStageDbPassword("", null, "from-file"))
    }

    @Test
    fun `blank password when nothing is configured - there is no development default`() {
        assertEquals("", resolveStageDbPassword("", null, null))
    }
}
