package com.aiturbo

import com.aiturbo.db.AI_TURBO_APPLICATION_NAME
import com.aiturbo.db.DbConfig
import com.aiturbo.db.ExposedStageFuelingRepository
import com.aiturbo.db.ExposedWeatherRecordRepository
import com.aiturbo.db.StageDbConfig
import com.aiturbo.db.StageFuelingRepository
import com.aiturbo.db.WeatherRecordRepository
import com.aiturbo.db.pgDataSource
import com.aiturbo.fueling.FuelingAgent
import com.aiturbo.fueling.KoogFuelingAgent
import com.aiturbo.llm.ProviderRoutingPromptExecutor
import com.aiturbo.llm.deepseekModel
import com.aiturbo.llm.deepseekPromptExecutor
import com.aiturbo.llm.ollamaModel
import com.aiturbo.llm.ollamaPromptExecutor
import com.aiturbo.log.LoggingPromptExecutor
import com.aiturbo.plugins.configureRouting
import com.aiturbo.plugins.configureSerialization
import com.aiturbo.plugins.installDi
import com.aiturbo.time.BuiltinTimeZoneResolver
import com.aiturbo.time.CachingTimeZoneResolver
import com.aiturbo.time.CompositeTimeZoneResolver
import com.aiturbo.time.DirectZoneResolver
import com.aiturbo.time.LlmTimeZoneResolver
import com.aiturbo.time.TimeService
import com.aiturbo.time.TimeZoneResolver
import com.aiturbo.tools.FindFuelingTool
import com.aiturbo.tools.GetWeatherTool
import com.aiturbo.tools.ToolJsonRenderer
import com.aiturbo.tools.ToolSpec
import com.aiturbo.tools.ToolSpecLoader
import com.aiturbo.tools.weatherToolDescriptor
import com.aiturbo.weather.KoogWeatherAgent
import com.aiturbo.weather.OpenMeteoWeatherClient
import com.aiturbo.weather.WeatherAgent
import com.aiturbo.weather.WeatherClient
import com.aiturbo.weather.WeatherConfig
import com.aiturbo.weather.WeatherUnavailableException
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import com.aiturbo.config.EnvFile
import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.eagerSingleton
import org.kodein.di.instance
import org.kodein.di.singleton
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
        const val DEFAULT_MODEL = "deepseek-flash"

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

internal fun loadDotenv(): EnvFile = EnvFile.load()

/** Tags keep the per-provider/per-database bindings apart inside one container. */
internal const val LOCAL_DATABASE_TAG = "localDatabase"
internal const val DEEPSEEK_EXECUTOR_TAG = "deepseekPromptExecutor"
internal const val LOCAL_EXECUTOR_TAG = "localPromptExecutor"

/**
 * Production dependency graph. Tests inject their own modules instead.
 */
fun appModules(
    deepseek: DeepseekConfig,
    ollama: OllamaConfig,
    db: DbConfig,
    weather: WeatherConfig,
): DI.Module = DI.Module(name = "app") {
    bind<Clock>() with singleton { Clock.systemUTC() }
    bind<HttpClient>() with singleton {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    // Tool description resource — loaded once at startup, fail fast if missing or invalid
    bind<ToolSpec>() with eagerSingleton { ToolSpecLoader.load() }
    bind<ToolJsonRenderer>() with singleton { ToolJsonRenderer() }

    bind<WeatherClient>() with singleton { OpenMeteoWeatherClient(instance(), weather) }
    // Lazy: no connection is opened at startup.
    bind<Database>(tag = LOCAL_DATABASE_TAG) with singleton {
        Database.connect(
            pgDataSource(db.jdbcUrl, db.user, db.password, applicationName = AI_TURBO_APPLICATION_NAME),
        )
    }
    bind<WeatherRecordRepository>() with singleton {
        ExposedWeatherRecordRepository(instance(tag = LOCAL_DATABASE_TAG))
    }
    bind<GetWeatherTool>() with singleton { GetWeatherTool(instance(), instance(), instance(), instance(), instance()) }
    bind<ToolRegistry>() with singleton { ToolRegistry.builder().tool(instance<GetWeatherTool>()).build() }
    bind<LLModel>() with singleton { deepseekModel(deepseek.model) }

    // Every DeepSeek call goes through this decorator (the single choke point).
    bind<PromptExecutor>(tag = DEEPSEEK_EXECUTOR_TAG) with singleton {
        LoggingPromptExecutor(
            delegate = deepseekPromptExecutor(deepseek),
            endpoint = "${deepseek.baseUrl.trimEnd('/')}/chat/completions",
            toolJsonRenderer = instance(),
        )
    }
    // The local path mirrors it: same decorator, Ollama endpoint and model (FR-10).
    bind<PromptExecutor>(tag = LOCAL_EXECUTOR_TAG) with singleton {
        LoggingPromptExecutor(
            delegate = ollamaPromptExecutor(ollama),
            endpoint = ollama.chatEndpoint,
            toolJsonRenderer = instance(),
        )
    }
    // The single entry point every LLM call goes through (one delegate per provider underneath).
    bind<PromptExecutor>() with singleton {
        ProviderRoutingPromptExecutor(
            deepseek = instance(tag = DEEPSEEK_EXECUTOR_TAG),
            local = instance(tag = LOCAL_EXECUTOR_TAG),
            localModel = ollamaModel(ollama.model),
            deepseekConfigured = deepseek.apiKey.isNotBlank(),
        )
    }

    bind<TimeZoneResolver>() with singleton {
        CompositeTimeZoneResolver(
            listOf(
                DirectZoneResolver(),
                BuiltinTimeZoneResolver(),
                CachingTimeZoneResolver(
                    LlmTimeZoneResolver(
                        promptExecutor = instance(),
                        model = instance(),
                        // The registry needs the tool, which needs this resolver: resolving it
                        // here (even lazily) is a Kodein dependency loop, so the descriptor is
                        // rebuilt from the same spec the tool uses.
                        toolDescriptorsProvider = { listOf(weatherToolDescriptor(instance())) },
                        apiKeyConfigured = deepseek.apiKey.isNotBlank(),
                    )
                ),
            )
        )
    }
    bind<TimeService>() with singleton { TimeService(instance()) }

    // Unconditional: the local path must work without a DeepSeek key (ASM-08); the
    // blank-key guard for the DeepSeek path lives in the routing executor (FR-09).
    bind<WeatherAgent>() with singleton { KoogWeatherAgent(instance(), instance(), instance()) }
}

/**
 * Tags keep the fueling tool spec and registry apart from the weather ones —
 * the untagged [ai.koog.agents.core.tools.ToolRegistry] binding must stay
 * `get_weather`-only (the frozen AppModulesTest/TraceChainIntegrationTest
 * assertions, D-02).
 */
const val FUELING_TOOL_SPEC = "fuelingToolSpec"
const val FUELING_TOOL_REGISTRY = "fuelingToolRegistry"
internal const val STAGE_DATABASE_TAG = "stageDatabase"

/**
 * The fueling vertical slice, additive on top of [appModules]: its own tool
 * (name/description from the JSON resource), its own `find_fueling`-only
 * registry and its own agent. Nothing connects to the stage database at
 * construction, so the application starts with the stage database absent
 * (FR-11, FR-12, FR-16).
 */
fun fuelingModule(stageDb: StageDbConfig, apiKeyConfigured: Boolean): DI.Module = DI.Module(name = "fueling") {
    // Fail fast at startup when the resource is missing or invalid.
    bind<ToolSpec>(tag = FUELING_TOOL_SPEC) with eagerSingleton {
        ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH)
    }
    // Lazy: no connection is opened at startup, and the stage timeouts bound every call.
    bind<Database>(tag = STAGE_DATABASE_TAG) with singleton {
        Database.connect(
            pgDataSource(
                stageDb.jdbcUrl,
                stageDb.user,
                stageDb.password,
                applicationName = AI_TURBO_APPLICATION_NAME,
                timeoutSeconds = stageDb.timeoutSeconds,
            ),
        )
    }
    bind<StageFuelingRepository>() with singleton {
        ExposedStageFuelingRepository(instance(tag = STAGE_DATABASE_TAG))
    }
    bind<FindFuelingTool>() with singleton { FindFuelingTool(instance(), instance(tag = FUELING_TOOL_SPEC)) }
    bind<ToolRegistry>(tag = FUELING_TOOL_REGISTRY) with singleton {
        ToolRegistry.builder().tool(instance<FindFuelingTool>()).build()
    }
    bind<FuelingAgent>() with singleton {
        if (apiKeyConfigured) {
            KoogFuelingAgent(instance(), instance(tag = FUELING_TOOL_REGISTRY), instance())
        } else {
            FuelingAgent { throw WeatherUnavailableException("DeepSeek API key is not configured") }
        }
    }
}

/**
 * Application entry point, loaded by EngineMain from application.conf.
 * [overrideModules] lets tests replace the dependency graph.
 */
fun Application.module(overrideModules: List<DI.Module> = emptyList()) {
    val di = DI {
        if (overrideModules.isEmpty()) {
            val deepseek = DeepseekConfig.from(environment.config)
            import(
                appModules(
                    deepseek = deepseek,
                    ollama = OllamaConfig.from(environment.config),
                    db = DbConfig.from(environment.config),
                    weather = WeatherConfig.from(environment.config),
                )
            )
            import(
                fuelingModule(
                    stageDb = StageDbConfig.from(environment.config),
                    apiKeyConfigured = deepseek.apiKey.isNotBlank(),
                )
            )
        } else {
            overrideModules.forEach { import(it) }
        }
    }
    installDi(di)
    configureSerialization()
    configureRouting()
}
