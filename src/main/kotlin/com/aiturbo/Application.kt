package com.aiturbo

import com.aiturbo.db.DbConfig
import com.aiturbo.db.JdbcWeatherRecordRepository
import com.aiturbo.db.WeatherRecordRepository
import com.aiturbo.plugins.configureRouting
import com.aiturbo.plugins.configureSerialization
import com.aiturbo.time.BuiltinTimeZoneResolver
import com.aiturbo.time.CachingTimeZoneResolver
import com.aiturbo.time.CompositeTimeZoneResolver
import com.aiturbo.time.DirectZoneResolver
import com.aiturbo.time.LlmTimeZoneResolver
import com.aiturbo.time.TimeService
import com.aiturbo.time.TimeZoneResolver
import com.aiturbo.weather.GetWeatherTool
import com.aiturbo.weather.KoogWeatherAgent
import com.aiturbo.weather.OpenMeteoWeatherClient
import com.aiturbo.weather.WeatherAgent
import com.aiturbo.weather.WeatherClient
import com.aiturbo.weather.WeatherConfig
import com.aiturbo.weather.WeatherUnavailableException
import com.aiturbo.weather.deepseekModel
import com.aiturbo.weather.deepseekPromptExecutor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
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

internal fun loadDotenv(): Dotenv = dotenv {
    directory = System.getProperty("user.dir")
    ignoreIfMissing = true
}

/**
 * Production dependency graph. Tests inject their own modules instead.
 */
fun appModules(deepseek: DeepseekConfig, db: DbConfig, weather: WeatherConfig): Module = module {
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
                CachingTimeZoneResolver(LlmTimeZoneResolver(get(), deepseek)),
            )
        )
    }
    singleOf(::TimeService)

    // Weather tool (Koog) + DeepSeek agent + Postgres
    single<WeatherClient> { OpenMeteoWeatherClient(get(), weather) }
    single<WeatherRecordRepository> { JdbcWeatherRecordRepository(db) }
    single { GetWeatherTool(get(), get(), get(), get()) }
    single { ToolRegistry.builder().tool(get<GetWeatherTool>()).build() }
    single { deepseekModel(deepseek.model) }
    single<PromptExecutor> { deepseekPromptExecutor(deepseek) }
    single<WeatherAgent> {
        if (deepseek.apiKey.isBlank()) {
            WeatherAgent { throw WeatherUnavailableException("DeepSeek API key is not configured") }
        } else {
            KoogWeatherAgent(get(), get(), get())
        }
    }
}

/**
 * Application entry point, loaded by EngineMain from application.conf.
 * [overrideModules] lets tests replace the dependency graph.
 */
fun Application.module(overrideModules: List<Module> = emptyList()) {
    install(Koin) {
        val koinModules = if (overrideModules.isEmpty()) {
            listOf(
                appModules(
                    deepseek = DeepseekConfig.from(environment.config),
                    db = DbConfig.from(environment.config),
                    weather = WeatherConfig.from(environment.config),
                )
            )
        } else {
            overrideModules
        }
        modules(koinModules)
    }
    configureSerialization()
    configureRouting()
}
