package com.aiturbo

import com.aiturbo.plugins.configureRouting
import com.aiturbo.plugins.configureSerialization
import com.aiturbo.time.BuiltinTimeZoneResolver
import com.aiturbo.time.CachingTimeZoneResolver
import com.aiturbo.time.CompositeTimeZoneResolver
import com.aiturbo.time.DirectZoneResolver
import com.aiturbo.time.LlmTimeZoneResolver
import com.aiturbo.time.TimeService
import com.aiturbo.time.TimeZoneResolver
import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Clock

/**
 * DeepSeek (LLM) HTTP API settings, read from application.conf.
 * Environment variables take precedence over the file values.
 */
data class DeepseekConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"

        fun from(config: ApplicationConfig): DeepseekConfig = DeepseekConfig(
            baseUrl = config.propertyOrNull("deepseek.baseUrl")?.getString() ?: DEFAULT_BASE_URL,
            apiKey = resolveApiKey(
                configValue = config.propertyOrNull("deepseek.apiKey")?.getString().orEmpty(),
                envValue = System.getenv("DEEPSEEK_API_KEY"),
                fileValue = loadDotenv()["DEEPSEEK_API_KEY"],
            ),
            model = config.propertyOrNull("deepseek.model")?.getString() ?: DEFAULT_MODEL,
        )
    }
}

/**
 * API key resolution order:
 * 1. application.conf value (left empty on purpose),
 * 2. DEEPSEEK_API_KEY environment variable,
 * 3. local git-ignored .env file.
 * Keeps the secret out of the repository.
 */
fun resolveApiKey(configValue: String, envValue: String?, fileValue: String?): String =
    configValue.ifBlank { envValue.orEmpty() }.ifBlank { fileValue.orEmpty() }

private fun loadDotenv(): Dotenv = dotenv {
    directory = System.getProperty("user.dir")
    ignoreIfMissing = true
}

/**
 * Production dependency graph. Tests inject their own modules instead.
 */
fun appModules(config: DeepseekConfig): Module = module {
    single { Clock.systemUTC() }
    single {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
    single<TimeZoneResolver> {
        CompositeTimeZoneResolver(
            listOf(
                DirectZoneResolver(),
                BuiltinTimeZoneResolver(),
                CachingTimeZoneResolver(LlmTimeZoneResolver(get(), config)),
            )
        )
    }
    singleOf(::TimeService)
}

/**
 * Application entry point, loaded by EngineMain from application.conf.
 * [overrideModules] lets tests replace the dependency graph.
 */
fun Application.module(overrideModules: List<Module> = emptyList()) {
    install(Koin) {
        val koinModules = if (overrideModules.isEmpty()) {
            listOf(appModules(DeepseekConfig.from(environment.config)))
        } else {
            overrideModules
        }
        modules(koinModules)
    }
    configureSerialization()
    configureRouting()
}
