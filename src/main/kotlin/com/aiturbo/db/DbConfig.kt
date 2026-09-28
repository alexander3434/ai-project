package com.aiturbo.db

import com.aiturbo.loadDotenv
import io.ktor.server.config.ApplicationConfig

/**
 * PostgreSQL connection settings, read from application.conf.
 * Environment variables take precedence over the file values.
 */
data class DbConfig(
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String,
) {
    val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$database"

    companion object {
        const val DEFAULT_HOST = "localhost"
        const val DEFAULT_PORT = 5439
        const val DEFAULT_DATABASE = "mydb2"
        const val DEFAULT_USER = "myuser"
        const val DEFAULT_PASSWORD = "mysecret"

        fun from(config: ApplicationConfig): DbConfig = DbConfig(
            host = config.propertyOrNull("db.host")?.getString() ?: DEFAULT_HOST,
            port = config.propertyOrNull("db.port")?.getString()?.toIntOrNull() ?: DEFAULT_PORT,
            database = config.propertyOrNull("db.name")?.getString() ?: DEFAULT_DATABASE,
            user = config.propertyOrNull("db.user")?.getString() ?: DEFAULT_USER,
            password = resolveDbPassword(
                configValue = config.propertyOrNull("db.password")?.getString().orEmpty(),
                envValue = System.getenv("DB_PASSWORD"),
                fileValue = loadDotenv()["DB_PASSWORD"],
            ),
        )
    }
}

/**
 * Password resolution order:
 * 1. application.conf value (left empty on purpose),
 * 2. DB_PASSWORD environment variable,
 * 3. local git-ignored .env file,
 * 4. the local development default (the bundled Docker container).
 */
fun resolveDbPassword(configValue: String, envValue: String?, fileValue: String?): String =
    configValue.ifBlank { envValue.orEmpty() }
        .ifBlank { fileValue.orEmpty() }
        .ifBlank { DbConfig.DEFAULT_PASSWORD }
