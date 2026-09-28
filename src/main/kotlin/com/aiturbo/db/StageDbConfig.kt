package com.aiturbo.db

import com.aiturbo.loadDotenv
import io.ktor.server.config.ApplicationConfig

/**
 * Stage PostgreSQL connection settings (read-only), read from application.conf.
 * Environment variables take precedence over the file values. Unlike [DbConfig]
 * there is no development default for the password — it is resolved in order:
 *   1. the STAGE_DB_PASSWORD environment variable,
 *   2. a local git-ignored .env file.
 * The password is never logged, rendered or written to a tracked file.
 *
 * Nothing connects at construction: the application starts with the stage
 * database absent (FR-12).
 */
data class StageDbConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val database: String = DEFAULT_DATABASE,
    val user: String = DEFAULT_USER,
    val password: String = "",
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {
    val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$database"

    companion object {
        const val DEFAULT_HOST = "postgres.stage.turboapp.ru"
        const val DEFAULT_PORT = 25432
        const val DEFAULT_DATABASE = "fueling"
        const val DEFAULT_USER = "fueling"
        const val DEFAULT_TIMEOUT_SECONDS = 10

        fun from(config: ApplicationConfig): StageDbConfig = StageDbConfig(
            host = config.propertyOrNull("stageDb.host")?.getString() ?: DEFAULT_HOST,
            port = config.propertyOrNull("stageDb.port")?.getString()?.toIntOrNull() ?: DEFAULT_PORT,
            database = config.propertyOrNull("stageDb.name")?.getString() ?: DEFAULT_DATABASE,
            user = config.propertyOrNull("stageDb.user")?.getString() ?: DEFAULT_USER,
            password = resolveStageDbPassword(
                configValue = config.propertyOrNull("stageDb.password")?.getString().orEmpty(),
                envValue = System.getenv("STAGE_DB_PASSWORD"),
                fileValue = loadDotenv()["STAGE_DB_PASSWORD"],
            ),
            timeoutSeconds = config.propertyOrNull("stageDb.timeoutSeconds")?.getString()?.toIntOrNull()
                ?: DEFAULT_TIMEOUT_SECONDS,
        )
    }
}

/**
 * Password resolution order:
 * 1. application.conf value (left empty on purpose),
 * 2. STAGE_DB_PASSWORD environment variable,
 * 3. local git-ignored .env file.
 * Blank when nothing is configured (no development default).
 */
fun resolveStageDbPassword(configValue: String, envValue: String?, fileValue: String?): String =
    configValue.ifBlank { envValue.orEmpty() }.ifBlank { fileValue.orEmpty() }
