# Kodein + Exposed Stack and a Selectable Local (Ollama) Model — System Design

Requirements source: `docs/features/kodein-exposed-ollama/01-requirements.md` (FR-01…FR-15,
NFR-01…NFR-10, ASM-01…ASM-14). This document designs *how*; it adds no requirements.

## Context and goals

The code still runs on Koin 4.1.0 and raw JDBC while `CLAUDE.md`/`CODE_STYLE.md` fix the stack as
**Kotlin, Ktor, Koog, Kodein, Exposed ORM**. This feature (a) removes Koin and `dotenv-kotlin`,
(b) moves both repositories to Exposed, (c) moves both Koog tools into `com.aiturbo.tools`,
(d) adds an optional per-request provider field `model` (`local` | `deepseek`, default `deepseek`)
that routes a single request's LLM traffic either to the existing DeepSeek client or to a locally
running Ollama (`http://localhost:11434`, `qwen3:8b`) through Koog's Ollama client, (e) keeps every
observable contract — status codes, response bodies, the `req=`-tagged log chain, offline tests —
and (f) yields a measured local-vs-DeepSeek latency comparison plus a live tool-calling verdict.

Design stance, in one sentence: **one Kodein container built in `Application.module` (no Kodein–Ktor
plugin), two Exposed repositories over lazily-connecting `Database`s, the tools relocated unchanged,
and one new LLM layer — a request-scoped provider selection consumed by a routing `PromptExecutor`
that wraps the two existing `LoggingPromptExecutor` choke points.**

**Current state → target state**

| Area | Today | After |
|---|---|---|
| DI | Koin (`install(Koin)`, `appModules`/`fuelingModule` as Koin modules, `by inject`) | Kodein (`DI`/`DI.Module`, `installDi`, `by di.instance()`) |
| Weather repository | `db/JdbcWeatherRecordRepository` (DriverManager, `INSERT … RETURNING id`) | `db/ExposedWeatherRecordRepository` (`UsersTable`, `insertReturning`, same retry seam) |
| Stage repository | `db/JdbcStageFuelingRepository` (PreparedStatement, `SELECT … WHERE lower(fueling_id) = ?`) | `db/ExposedStageFuelingRepository` (Table objects, `selectAll().where { … lowerCase() eq … }`) |
| Tools | `weather/GetWeatherTool`, `fueling/FindFuelingTool` | `tools/GetWeatherTool`, `tools/FindFuelingTool` (package move only) |
| Secrets | `dotenv-kotlin` | internal stdlib `.env` reader (`com.aiturbo.config`) |
| LLM | one `LoggingPromptExecutor` over the DeepSeek client, agent dies without a key | two `LoggingPromptExecutor`s (DeepSeek, Ollama) behind one routing executor; per-request selection; `local` works without a key |
| Config | `deepseek.*`, `db.*`, `stageDb.*`, `weather.*` | plus `ollama.baseUrl` / `ollama.model` (`OLLAMA_BASE_URL` / `OLLAMA_MODEL`) |

**Requirement traceability**

| Req | Design elements |
|---|---|
| FR-01 | C1 (Kodein modules, `installDi`), D1–D4, flow F7 |
| FR-02 | C5, C6, C7, D5, D6, D8, D9, flow F9 |
| FR-03 | C6, T9 (guard test), D7 |
| FR-04 | C8, D10 |
| FR-05 | C9, D11 |
| FR-06 | C3, API design (`POST /weather`, `POST /fueling`), D12, flow F4 |
| FR-07 | C2, C4, D13, D14, D19, flows F1/F3 |
| FR-08 | C4, C7 (timeouts), `StatusPages`, D15, flow F5, risks R2/R7 |
| FR-09 | C4 (blank-key guard), D16, flow F2 |
| FR-10 | C4 (per-provider endpoint labels), D17 |
| FR-11 | T1–T10, D18 |
| FR-12 | NFR-10 in `## Non-functional coverage`; planner handoff (benchmark recipe unchanged from the requirements) |
| FR-13 | Risk R1 (live gate) |
| FR-14 | C10 |
| FR-15 | The `/feature-design` → `/feature-implementation` pipeline and the four reviewers — process, not code |
| NFR-01…NFR-10 | `## Non-functional coverage` (per-NFR row) |

## Architecture overview

Layered, single-process Ktor application; no new runtime service, no new dependency beyond Kodein
and Exposed (NFR-02). The DI container is created once in `Application.module` and stored on the
application; routes and `configureRouting` resolve beans lazily, exactly like today's `by inject`.

```
                        POST /weather | POST /fueling            GET /time        GET /weather/history
                              |  (model: local|deepseek)             |                    |
                    plugins/WeatherRouting.kt  ── withContext(trace + LlmTargetContext(target)) ──┐
                              |                                                                     │
                    weather.WeatherAgent / fueling.FuelingAgent  (Koog AIAgent, unchanged shape)  │
                              |                                                                     │
                    llm.ProviderRoutingPromptExecutor  <── reads currentLlmTarget() ──────────────┘
                       |                       |
        llm.LoggingPromptExecutor         llm.LoggingPromptExecutor
        (endpoint=…/chat/completions)     (endpoint=http://localhost:11434/api/chat)
                       |                       |
        DeepSeek OpenAILLMClient           Koog OllamaLLMClient        ← LLMProvider.Ollama
                       |                       |
                    DeepSeek API            local Ollama (user-managed)

  tools.GetWeatherTool → db.ExposedWeatherRecordRepository → Exposed Database(local)  → PG mydb2.users
  tools.FindFuelingTool → db.ExposedStageFuelingRepository → Exposed Database(stage)  → PG stage (SELECT only)

  DI: plugins/Di.kt installDi(DI { appModules(...) ; fuelingModule(...) })  — Application attribute
  Config: deepseek.* / ollama.* / db.* / stageDb.* / weather.* + com.aiturbo.config.EnvFile (.env, secrets only)
```

**File inventory** (paths relative to the repository root).

| Action | Path | What changes |
|---|---|---|
| create | `src/main/kotlin/com/aiturbo/config/EnvFile.kt` | stdlib `.env` reader (C8) |
| create | `src/main/kotlin/com/aiturbo/llm/LlmTarget.kt` | `LlmTarget`, `LlmTargetContext`, `fromRequest` (C3) |
| create | `src/main/kotlin/com/aiturbo/llm/DeepSeekLlm.kt` | `deepseekModel` / `deepseekPromptExecutor`, moved from `weather/DeepSeekKoogLlm.kt`, package `com.aiturbo.llm` |
| create | `src/main/kotlin/com/aiturbo/llm/OllamaLlm.kt` | `ollamaModel` / `ollamaPromptExecutor` (C2) |
| create | `src/main/kotlin/com/aiturbo/llm/ProviderRoutingPromptExecutor.kt` | the routing executor (C4) |
| create | `src/main/kotlin/com/aiturbo/OllamaConfig.kt` | `OllamaConfig` + `from(config)` + `chatEndpoint` (root package, next to `DeepseekConfig`) |
| create | `src/main/kotlin/com/aiturbo/plugins/Di.kt` | `installDi`, `Application.di`, `Route.di` (C1) |
| create | `src/main/kotlin/com/aiturbo/db/PgDataSource.kt` | `internal fun pgDataSource(...)` over `PGSimpleDataSource` (C7) |
| create | `src/main/kotlin/com/aiturbo/db/UsersTable.kt` | Exposed `Table` for `users` (C5) |
| create | `src/main/kotlin/com/aiturbo/db/ExposedWeatherRecordRepository.kt` | Exposed repo + `DATA_FORMAT`/`TIME_FORMAT`/`insertWithRetry` (C5) |
| create | `src/main/kotlin/com/aiturbo/db/StageTables.kt` | Exposed `Table` objects for the four stage tables (C6) |
| create | `src/main/kotlin/com/aiturbo/db/ExposedStageFuelingRepository.kt` | Exposed repo + `epochMillisOrNull` (C6) |
| create | `src/main/kotlin/com/aiturbo/tools/GetWeatherTool.kt` | moved from `weather/` (C9) |
| create | `src/main/kotlin/com/aiturbo/tools/FindFuelingTool.kt` | moved from `fueling/` (C9) |
| create | `src/main/kotlin/com/aiturbo/weather/WeatherUnavailableException.kt` | the class extracted from the deleted `weather/DeepSeekKoogLlm.kt` (same package/name) |
| create | tests: `EnvFileTest.kt`, `OllamaConfigTest.kt`, `LlmTargetTest.kt`, `ProviderRoutingPromptExecutorTest.kt` (T1–T4) | new offline coverage |
| modify | `build.gradle.kts` | remove `koin-*`, `dotenv-kotlin`; add `kodein-di`, `exposed-core`, `exposed-jdbc`; declare the Ollama artifact only if compilation needs it (D5, R2) |
| modify | `src/main/kotlin/com/aiturbo/Application.kt` | Kodein modules, `ollama` parameter, routing-executor binding, `EnvFile` helper, `Application.module` |
| modify | `src/main/kotlin/com/aiturbo/plugins/Routing.kt`, `WeatherRouting.kt`, `FuelingRouting.kt` | Kodein resolution; `model` field + 400; `withContext(trace + LlmTargetContext(target))` |
| modify | `src/main/resources/application.conf` | `ollama { }` section |
| modify | `README.md` | stack, request field, config keys, log examples (C10) |
| delete | `db/JdbcWeatherRecordRepository.kt`, `db/JdbcStageFuelingRepository.kt`, `weather/GetWeatherTool.kt`, `fueling/FindFuelingTool.kt`, `weather/DeepSeekKoogLlm.kt` | superseded/replaced |
| modify | tests T5–T10 (below) | Kodein modules, Exposed type, guard scan, import-only moves |

## Components

### C1 — DI: one Kodein container, no plugin

`plugins/Di.kt` (new):

```kotlin
package com.aiturbo.plugins

private val DiAttribute = AttributeKey<DI>("aiturbo.di")

/** Stores the container on the application; nothing is resolved here. */
fun Application.installDi(di: DI) { attributes.put(DiAttribute, di) }

/** The running application's container (routes resolve lazily from it). */
val Application.di: DI get() = attributes[DiAttribute]
val Route.di: DI get() = application.di
```

Module functions in `com.aiturbo.Application.kt` keep their names and become `DI.Module`s
(production graph; `name` is mandatory and must stay unique per container):

```kotlin
internal const val DEEPSEEK_EXECUTOR_TAG = "deepseekPromptExecutor"
internal const val LOCAL_EXECUTOR_TAG = "localPromptExecutor"
internal const val LOCAL_DATABASE_TAG = "localDatabase"
internal const val STAGE_DATABASE_TAG = "stageDatabase"
const val FUELING_TOOL_SPEC = "fuelingToolSpec"          // was a Koin Qualifier, now a tag
const val FUELING_TOOL_REGISTRY = "fuelingToolRegistry"  // was a Koin Qualifier, now a tag

fun appModules(
    deepseek: DeepseekConfig,
    ollama: OllamaConfig,
    db: DbConfig,
    weather: WeatherConfig,
): DI.Module = DI.Module(name = "app") {
    bind<Clock>() with singleton { Clock.systemUTC() }
    bind<HttpClient>() with singleton {
        HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    bind<ToolSpec>() with eagerSingleton { ToolSpecLoader.load() }          // fail fast at startup
    bind<ToolJsonRenderer>() with singleton { ToolJsonRenderer() }

    bind<WeatherClient>() with singleton { OpenMeteoWeatherClient(instance(), weather) }
    bind<Database>(tag = LOCAL_DATABASE_TAG) with singleton {
        Database.connect(pgDataSource(db.jdbcUrl, db.user, db.password, applicationName = AI_TURBO_APPLICATION_NAME))
    }
    bind<WeatherRecordRepository>() with singleton {
        ExposedWeatherRecordRepository(instance(tag = LOCAL_DATABASE_TAG))
    }
    bind<GetWeatherTool>() with singleton { GetWeatherTool(instance(), instance(), instance(), instance(), instance()) }
    bind<ToolRegistry>() with singleton { ToolRegistry.builder().tool(instance<GetWeatherTool>()).build() }
    bind<LLModel>() with singleton { deepseekModel(deepseek.model) }

    bind<PromptExecutor>(tag = DEEPSEEK_EXECUTOR_TAG) with singleton {
        LoggingPromptExecutor(
            delegate = deepseekPromptExecutor(deepseek),
            endpoint = "${deepseek.baseUrl.trimEnd('/')}/chat/completions",
            toolJsonRenderer = instance(),
        )
    }
    bind<PromptExecutor>(tag = LOCAL_EXECUTOR_TAG) with singleton {
        LoggingPromptExecutor(
            delegate = ollamaPromptExecutor(ollama),
            endpoint = ollama.chatEndpoint,
            toolJsonRenderer = instance(),
        )
    }
    // The single entry point every LLM call goes through (one per provider underneath).
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
                        toolDescriptorsProvider = { instance<ToolRegistry>().tools.map { it.descriptor } },
                        apiKeyConfigured = deepseek.apiKey.isNotBlank(),
                    )
                ),
            )
        )
    }
    bind<TimeService>() with singleton { TimeService(instance()) }

    // Unconditional: the local path must work without a DeepSeek key (ASM-08); the
    // deepseek branch throws the documented 503 inside the routing executor (FR-09).
    bind<WeatherAgent>() with singleton { KoogWeatherAgent(instance(), instance(), instance()) }
}

fun fuelingModule(stageDb: StageDbConfig, apiKeyConfigured: Boolean): DI.Module = DI.Module(name = "fueling") {
    bind<ToolSpec>(tag = FUELING_TOOL_SPEC) with eagerSingleton {
        ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH)
    }
    bind<Database>(tag = STAGE_DATABASE_TAG) with singleton {
        Database.connect(
            pgDataSource(
                stageDb.jdbcUrl, stageDb.user, stageDb.password,
                applicationName = AI_TURBO_APPLICATION_NAME, timeoutSeconds = stageDb.timeoutSeconds,
            )
        )
    }
    bind<StageFuelingRepository>() with singleton { ExposedStageFuelingRepository(instance(tag = STAGE_DATABASE_TAG)) }
    bind<FindFuelingTool>() with singleton { FindFuelingTool(instance(), instance(tag = FUELING_TOOL_SPEC)) }
    bind<ToolRegistry>(tag = FUELING_TOOL_REGISTRY) with singleton {
        ToolRegistry.builder().tool(instance<FindFuelingTool>()).build()
    }
    bind<FuelingAgent>() with singleton {
        if (apiKeyConfigured) KoogFuelingAgent(instance(), instance(tag = FUELING_TOOL_REGISTRY), instance())
        else FuelingAgent { throw WeatherUnavailableException("DeepSeek API key is not configured") }
    }
}
```

`Application.module` (entry point, shape preserved so every test that calls it keeps working):

```kotlin
fun Application.module(overrideModules: List<DI.Module> = emptyList()) {
    val di = DI {
        if (overrideModules.isEmpty()) {
            val deepseek = DeepseekConfig.from(environment.config)
            import(appModules(deepseek, OllamaConfig.from(environment.config),
                              DbConfig.from(environment.config), WeatherConfig.from(environment.config)))
            import(fuelingModule(StageDbConfig.from(environment.config), deepseek.apiKey.isNotBlank()))
        } else {
            overrideModules.forEach { import(it) }
        }
    }
    installDi(di)
    configureSerialization()
    configureRouting()
}
```

Rules this shape depends on (verified against the Kodein 7 documentation): bindings are lazy unless
declared `eagerSingleton`; `import` order is irrelevant; a duplicate key raises `OverridingException`
and overriding requires `bind<X>(overrides = true)` on the overriding side plus `import(module,
allowOverride = true)` for the imported one; retrieval in a module is `instance()` /
`instance(tag = "…")`, in routes `val x: T by di.instance()` (lazy, cached).

### C2 — LLM factories: DeepSeek moved, Ollama added

`com.aiturbo.llm.DeepSeekLlm` (moved verbatim from `weather/DeepSeekKoogLlm.kt`, minus the
exception): `fun deepseekModel(id: String): LLModel` and
`fun deepseekPromptExecutor(config: DeepseekConfig): PromptExecutor`.

`com.aiturbo.llm.OllamaLlm` (new):

```kotlin
fun ollamaModel(id: String): LLModel = LLModel(
    provider = LLMProvider.Ollama,
    id = id,
    capabilities = listOf(LLMCapability.Completion, LLMCapability.Temperature, LLMCapability.Tools),
)

fun ollamaPromptExecutor(config: OllamaConfig): PromptExecutor =
    MultiLLMPromptExecutor(LLMProvider.Ollama to OllamaLLMClient(baseUrl = config.baseUrl))
```

`OllamaLLMClient` is the Koog 1.2.0 Ollama client (`ai.koog.prompt.executor.clients.ollama`,
transitively present via `koog-agents`, ASM-05/ASM-06); the exact class/package and constructor —
including whether a `KoogHttpClient`/`ConnectionTimeoutConfig` must be supplied for the ≤10 s
connect budget (NFR-06) — is a **verification step** at implementation against the resolved jar
(present in the Gradle cache today), with the documented fallbacks in risk R2. No connection is
opened at construction, so the application starts with Ollama stopped (NFR-04).

`com.aiturbo.OllamaConfig` (new, root package next to `DeepseekConfig`):

```kotlin
data class OllamaConfig(val baseUrl: String = DEFAULT_BASE_URL, val model: String = DEFAULT_MODEL) {
    /** Endpoint label used in the trace: Ollama's chat endpoint. */
    val chatEndpoint: String get() = "${baseUrl.trimEnd('/')}/api/chat"

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:11434"
        const val DEFAULT_MODEL = "qwen3:8b"
        fun from(config: ApplicationConfig): OllamaConfig = OllamaConfig(
            baseUrl = config.propertyOrNull("ollama.baseUrl")?.getString() ?: DEFAULT_BASE_URL,
            model = config.propertyOrNull("ollama.model")?.getString() ?: DEFAULT_MODEL,
        )
    }
}
```

Environment overrides use the project's existing style for non-secrets: HOCON `${?VAR}`
substitution in `application.conf` (exactly like `deepseek.baseUrl`/`deepseek.model`), not the
`.env` reader (ASM-12, no secret involved).

### C3 — Request-scoped provider selection

`com.aiturbo.llm.LlmTarget.kt` (new):

```kotlin
enum class LlmTarget {
    DEEPSEEK,
    LOCAL,
    ;
    companion object {
        const val FIELD = "model"
        const val ALLOWED_VALUES = "local, deepseek"

        /** null means "not one of the allowed values" — the route answers 400 (ASM-01). */
        fun fromRequest(value: String?): LlmTarget? = when (value?.trim()?.lowercase()) {
            null, "" -> DEEPSEEK          // absent or blank → DeepSeek
            "local" -> LOCAL
            "deepseek" -> DEEPSEEK
            else -> null                  // unknown, non-blank → 400, no LLM/tool call
        }
    }
}

/** Request-scoped provider selection; travels through the agent session like `CallTrace` does. */
class LlmTargetContext(val target: LlmTarget) : AbstractCoroutineContextElement(LlmTargetContext) {
    companion object Key : CoroutineContext.Key<LlmTargetContext>
}

/** The provider selected for the current request; DeepSeek when no element is present (ASM-02). */
suspend fun currentLlmTarget(): LlmTarget =
    coroutineContext[LlmTargetContext]?.target ?: LlmTarget.DEEPSEEK
```

The element is set once per request in the route handler and is readable anywhere inside the call —
including inside Koog's strategy loop at the executor call site, the same mechanism the existing
`CallTrace` element already proves (`TraceLog.currentId()` works there today).

### C4 — Routing executor (the new LLM layer)

`com.aiturbo.llm.ProviderRoutingPromptExecutor` (new) mirrors the overload set of
`LoggingPromptExecutor` (LR-1: `execute(LLModel)`, `execute(ResolvedModel)`, `executeStreaming`,
`moderate`, `close`):

```kotlin
class ProviderRoutingPromptExecutor(
    private val deepseek: PromptExecutor,       // LoggingPromptExecutor over the DeepSeek client
    private val local: PromptExecutor,          // LoggingPromptExecutor over the Ollama client
    private val localModel: LLModel,            // ollamaModel(cfg.model)
    private val deepseekConfigured: Boolean,    // apiKey.isNotBlank()
) : PromptExecutor() {

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
        when (currentLlmTarget()) {
            LlmTarget.LOCAL -> local.execute(prompt, localModel, tools)     // model substitution
            LlmTarget.DEEPSEEK -> deepseek().execute(prompt, model, tools)  // unchanged path
        }

    override suspend fun execute(prompt: Prompt, model: ResolvedModel, tools: List<ToolDescriptor>) = /* same selection */
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        flow { /* reads the element at collection time, then emits from the selected delegate */ }
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = /* same selection */

    override fun close() { runCatching { deepseek.close() }; runCatching { local.close() } }

    /** FR-09: a blank key keeps today's 503 contract, before any log line or HTTP attempt. */
    private fun deepseek(): PromptExecutor {
        if (!deepseekConfigured) throw WeatherUnavailableException("DeepSeek API key is not configured")
        return deepseek
    }
}
```

Why the model substitution: a Koog `AIAgent` fixes `promptExecutor` and `llmModel` at build time, and
`MultiLLMPromptExecutor` dispatches by `model.provider`. Rewriting the model in the routing executor
is therefore what makes *one* agent instance per slice serve both providers (FR-07 "the same
agents") while the logged `model=` shows the id the request really used (`qwen3:8b` locally,
FR-10). Failure semantics of the local delegate are untouched: `LoggingPromptExecutor` logs
`stage=deepseek-response … error=…` (no secrets) and rethrows; the exception reaches the route and
`Routing.kt`'s existing `WeatherUnavailableException → 503` handler (ASM-09, FR-08).

### C5 — Exposed weather repository

`db/UsersTable.kt`: `internal object UsersTable : Table("users")` with `id = integer("id").autoIncrement()`,
`data = varchar("data", 255)`, `time = varchar("time", 100).nullable()`,
`createdAt = datetime("created_at").nullable()`, `override val primaryKey = PrimaryKey(id)`.
The local `users` DDL already exists; no `SchemaUtils`/DDL is ever issued (out of scope).

`db/ExposedWeatherRecordRepository.kt`:

```kotlin
class ExposedWeatherRecordRepository(private val db: Database) : WeatherRecordRepository {

    override fun save(at: LocalDateTime): Int {
        val id = insertWithRetry(at, MAX_INSERT_ATTEMPTS) { candidate ->
            // One transaction (one connection) per attempt: Postgres aborts a transaction
            // after a constraint violation, so the retry must be a fresh transaction.
            transaction(db) {
                UsersTable.insertReturning(listOf(UsersTable.id)) { row ->
                    row[UsersTable.data] = DATA_FORMAT.format(candidate)
                    row[UsersTable.time] = TIME_FORMAT.format(candidate)
                }.single()[UsersTable.id]
            }
        }
        if (id < 0) logger.warn("Weather record was not saved: UNIQUE conflict on 'data' persisted after $MAX_INSERT_ATTEMPTS attempts")
        return id
    }

    override fun recent(limit: Int): List<WeatherRecord> = try {
        transaction(db) {
            UsersTable.selectAll()
                .orderBy(UsersTable.id to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    WeatherRecord(
                        id = row[UsersTable.id],
                        data = row[UsersTable.data],
                        time = row[UsersTable.time],
                        createdAt = row[UsersTable.createdAt]?.format(DATA_FORMAT),
                    )
                }
        }
    } catch (e: ExposedSQLException) { throw DatabaseUnavailableException("Failed to read weather records: ${e.message}", e) }
      catch (e: SQLException) { throw DatabaseUnavailableException("Failed to read weather records: ${e.message}", e) }
}
```

`internal val DATA_FORMAT`, `internal val TIME_FORMAT`, `internal fun insertWithRetry(start,
maxAttempts, insert: (LocalDateTime) -> Int): Int` and `MAX_INSERT_ATTEMPTS` keep their names,
package and signatures (the frozen `WeatherRecordRepositoryTest` seam); only the unique-violation
test becomes cause-chain aware, because Exposed raises `ExposedSQLException` around the pgjdbc
`SQLException`:

```kotlin
private fun Throwable.isUniqueViolation(): Boolean =
    generateSequence(this) { it.cause }.any { it is SQLException && it.sqlState == UNIQUE_VIOLATION_SQL_STATE }
```

A plain `java.sql.SQLException(msg, "23505")` still matches on the first hop, so the existing retry
tests pass unchanged.

### C6 — Exposed stage repository (read-only)

`db/StageTables.kt` declares one `Table` per stage table with a shared base class for the three
fuelling tables (each concrete object instantiates its own `Column`s):

```kotlin
internal open class FuelingsColumns(name: String) : Table(name) {
    val fuelingId = text("fueling_id")
    val vendorFuelingOrderId = text("vendor_fueling_order_id").nullable()
    val userId = text("user_id").nullable()
    val status = text("status").nullable()
    val amount = decimal("amount", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val actualAmount = decimal("actual_amount", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val discountFuelPrice = decimal("discount_fuel_price", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val vendorFuelPrice = decimal("vendor_fuel_price", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val fuelType = text("fuel_type").nullable()
    val gasStationId = text("gas_station_id").nullable()
    val gasPumpId = text("gas_pump_id").nullable()
    val refuelingGunId = text("refueling_gun_id").nullable()
    val fuelReservationKey = text("fuel_reservation_key").nullable()
    val fuelingType = text("fueling_type").nullable()
    val fuelingPaymentType = text("fueling_payment_type").nullable()
    val failedReason = text("failed_reason").nullable()
    val fueledOrders = text("fueled_orders").nullable()      // jsonb, carried as raw JSON text
    val extra = text("extra").nullable()                     // jsonb, carried as raw JSON text
    val createdAt = decimal("created_at", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()   // epoch ms
    val updatedAt = decimal("updated_at", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val finishedAt = decimal("finished_at", DECIMAL_PRECISION, DECIMAL_SCALE).nullable()
    val vendorTransactionDate = text("vendor_transaction_date").nullable()  // TEXT, never an epoch
}
internal object FuelingsTable : FuelingsColumns("fuelings")
internal object FuelingsArchiveTable : FuelingsColumns("fuelings_archive")
internal object FuelingsDropTable : FuelingsColumns("fuelings_drop")
internal object PartnerFuelingEventsTable : Table("partner_fueling_events") { /* event_id, fueling_id, partner_id, event_name, delivery_status, data, created_at, updated_at */ }
internal object FuelingFeedbackTable : Table("fueling_feedback") { /* fueling_feedback_id, user_id, fueling_id, reason_id, reason_message, requested_at */ }
internal object BelkaTokensTable : Table("belka_tokens") { /* token, fueling_id, created_at, error_type */ }
```

Type choices mirror the previous, live-verified read path exactly: numeric columns are read
defensively as `BigDecimal` and passed through `epochMillisOrNull` (the reported "Bad value for
type long" regression stays fixed); text/`jsonb` columns are read as `String`. `DECIMAL_PRECISION`
/`DECIMAL_SCALE` are nominal (no DDL is issued) and deliberately wide (38/18) so no value is
truncated or rounded by the declared scale.

`db/ExposedStageFuelingRepository.kt`:

```kotlin
class ExposedStageFuelingRepository(private val db: Database) : StageFuelingRepository {

    override fun findByFuelingId(fuelingId: String): FuelingLookupResult = try {
        transaction(db) {                                   // one connection for the whole lookup
            val matches = FuelingSource.entries.mapNotNull { source -> readFueling(source, fuelingId) }
            val related = if (matches.isEmpty()) RelatedRows() else readRelated(fuelingId)
            FuelingLookupResult(fuelingId = fuelingId, matches = matches, related = related)
        }
    } catch (e: ExposedSQLException) { throw DatabaseUnavailableException("Failed to read fueling $fuelingId from the stage database: ${e.message}", e) }
      catch (e: SQLException) { throw DatabaseUnavailableException("Failed to read fueling $fuelingId from the stage database: ${e.message}", e) }

    private fun readFueling(source: FuelingSource, fuelingId: String): FuelingMatch? {
        val table = TABLES.getValue(source)                 // enum-derived table objects; never user input
        return table.selectAll()
            .where { table.fuelingId.lowerCase() eq fuelingId }      // case-insensitive, value bound
            .firstOrNull()
            ?.let { FuelingMatch(source, it.toFuelingRecord(table)) }
    }

    private fun readRelated(fuelingId: String): RelatedRows = RelatedRows(
        events = PartnerFuelingEventsTable.selectAll()
            .where { PartnerFuelingEventsTable.fuelingId eq fuelingId }
            .orderBy(PartnerFuelingEventsTable.createdAt to SortOrder.DESC_NULLS_LAST)
            .map { it.toPartnerFuelingEvent() },
        feedback = /* fueling_feedback, requested_at DESC NULLS LAST */,
        tokens   = /* belka_tokens, created_at DESC NULLS LAST */,
    )

    private companion object {
        val TABLES = mapOf(
            FuelingSource.FUELINGS to FuelingsTable,
            FuelingSource.ARCHIVE to FuelingsArchiveTable,
            FuelingSource.DROP to FuelingsDropTable,
        )
    }
}
```

Preserved contract: the same three main lookups (no added `ORDER BY`, exactly like today), the same
three related queries with `ORDER BY … DESC NULLS LAST`, one connection per lookup, the same 22/8/6/4
column coverage and the same DTO field mapping (`epochMillisOrNull` for epoch columns,
`vendorTransactionDate` read as text). No `insert`/`update`/`delete`/`replace`/`SchemaUtils` and no
raw SQL string appears in the file (FR-03, guarded by T9).

### C7 — Connection management (no new dependency)

`db/PgDataSource.kt` (new):

```kotlin
internal const val AI_TURBO_APPLICATION_NAME = "ai-turbo"

/**
 * One physical connection per `getConnection()` (no pool, no connection at construction).
 * Uses the PostgreSQL driver's own DataSource — the driver is already a dependency and
 * `DriverManager` is forbidden in src/main (FR-02).
 */
internal fun pgDataSource(
    jdbcUrl: String,
    user: String,
    password: String,
    applicationName: String,
    timeoutSeconds: Int? = null,
): DataSource = PGSimpleDataSource().apply {
    setURL(jdbcUrl); setUser(user); setPassword(password); setApplicationName(applicationName)
    if (timeoutSeconds != null) { setConnectTimeout(timeoutSeconds); setSocketTimeout(timeoutSeconds); setLoginTimeout(timeoutSeconds) }
}
```

`Database.connect(dataSource)` is lazy — no connection, no driver registration and no schema access
at startup (NFR-04) — and `transaction(db) { }` acquires one connection per call and releases it,
preserving today's connection-per-call model (FR-02). The stage `Database` additionally carries
`ApplicationName=ai-turbo` (NFR-03 traceability in `pg_stat_activity`) and the configured 10 s
timeouts; the local `Database` carries `ApplicationName` only, keeping its previous connection
behaviour unchanged. Trade-off accepted and recorded in D6: Exposed has no per-statement query
timeout, so the JDBC `queryTimeout` disappears and the driver's socket timeout is the remaining
bound (covered by the stage 10 s configuration and by the smoke test).

### C8 — Internal `.env` reader

`com/aiturbo/config/EnvFile.kt` (new):

```kotlin
package com.aiturbo.config

/**
 * Minimal stdlib `.env` reader (ASM-04). Never logs, never exposes values (`toString` is
 * intentionally not overridden and the class is not a data class).
 */
class EnvFile private constructor(private val values: Map<String, String>) {
    operator fun get(key: String): String? = values[key]

    companion object {
        const val FILE_NAME = ".env"

        /** `KEY=VALUE` lines; whole-line `#` comments, blank lines, optional surrounding quotes. */
        fun parse(text: String): EnvFile
        /** The `.env` in [directory]; missing or unreadable → empty reader (ignore-if-missing). */
        fun load(directory: String = System.getProperty("user.dir"), fileName: String = FILE_NAME): EnvFile
    }
}
```

Parser rules (fixed here so the tests are exact): split on the first `=`; skip blank lines and lines
whose first non-blank character is `#`; trim key and value; strip one pair of matching surrounding
single/double quotes; no interpolation, no inline-comment stripping; an empty value is an empty
string (so the existing `ifBlank` chains behave as before); a repeated key keeps the last value; a
line without `=` is ignored. `Application.kt` keeps its one-line helper
`internal fun loadDotenv(): EnvFile = EnvFile.load()` so `DeepseekConfig`/`DbConfig`/
`StageDbConfig` keep their resolution chains and the pure `resolveApiKey`/`resolveDbPassword`/
`resolveStageDbPassword` functions (and their tests) unchanged.

### C9 — Tools move

`GetWeatherTool` and `FindFuelingTool` move to `com.aiturbo.tools` with **no** signature, name,
description or behaviour change: same `SimpleTool` subclasses, same `@LLMDescription` arguments,
same JSON resources (`src/main/resources/tools/get-weather-tool.json`,
`find-fueling-tool.json`) loaded by the existing fail-fast `ToolSpecLoader` — so the
`deepseek-request … tools=[…]` definitions stay byte-identical (FR-05). Only the package line and
the importing files (production + tests) change.

### C10 — Config and README

`application.conf` gains (mirroring the `deepseek` section style):

```hocon
ollama {
    baseUrl = "http://localhost:11434"
    baseUrl = ${?OLLAMA_BASE_URL}
    model = "qwen3:8b"
    model = ${?OLLAMA_MODEL}
}
```

README updates (FR-14): stack line (Kodein + Exposed, no Koin/plain JDBC), the tools' package,
the request field with the local/deepseek/absent/padded/unknown matrix, the `ollama.*` keys and
`OLLAMA_*` overrides, one example chain per provider, and the secrets rules reminder (unchanged).
No latency claims in the README (the benchmark lives in this feature's docs, FR-12).

### Tests (T1–T10)

New, all offline:

| # | File | Scenarios |
|---|---|---|
| T1 | `EnvFileTest` | `KEY=VALUE`, blanks/comments, quotes, empty value, duplicate key, missing `=` ignored, missing file → empty, `parse` purity (no interpolation) |
| T2 | `OllamaConfigTest` | defaults without the section; `ollama.baseUrl`/`ollama.model` from a `MapApplicationConfig`; `chatEndpoint` label |
| T3 | `LlmTargetTest` | absent → DEEPSEEK; `""`/`" "` → DEEPSEEK; `" LOCAL "`/`"local"` → LOCAL; `"deepseek"`/`"DEEPSEEK"` → DEEPSEEK; `"gpt-4"` → `null`; `ALLOWED_VALUES` wording |
| T4 | `ProviderRoutingPromptExecutorTest` | fake delegates recording calls: (a) no element → DeepSeek delegate, model passed through; (b) `LlmTargetContext(LOCAL)` → local delegate with the Ollama model (`provider == LLMProvider.Ollama`, id `qwen3:8b`); (c) blank key + LOCAL → local delegate, no throw; (d) blank key + DEEPSEEK/absent → `WeatherUnavailableException("…API key…")`, DeepSeek delegate untouched; (e) `close()` closes both; (f) `executeStreaming` routes by target |
| T5 | `WeatherRoutesTest` additions | unknown model → 400 naming `local`/`deepseek`, agent never called, no `stage=deepseek` line; `" LOCAL "` → target LOCAL observed by the fake agent via `currentLlmTarget()`; absent/blank → DEEPSEEK; the frozen inbound-body assertion stays the canary for the `model` rendering |
| T6 | `FuelingRoutesTest` additions | same three cases on `/fueling` (unknown → 400, padded local accepted, absent → DeepSeek) |
| T7 | `WeatherRecordRepositoryTest` addition | a wrapped unique violation (`SQLException("wrapped", cause = SQLException("unique", "23505"))`) is still retried; the existing direct-case tests stay |

Adapted (never deleted, FR-11/ASM-11):

| # | File | Change |
|---|---|---|
| T8 | `AppModulesTest`, `FuelingModulesTest`, `FuelingChainIntegrationTest`, `TraceChainIntegrationTest` | Koin `koinApplication { modules(...) }`/`module { }` become `DI { import(DI.Module("…") { … }) }`; `appModules(...)` gains the `ollama` argument; `get<X>()`/`get(X)` become `instance()`/`instance(tag = …)`; `createEagerInstances()` disappears (eager singletons fire when the container is built); `PromptExecutor is LoggingPromptExecutor` becomes `is ProviderRoutingPromptExecutor`; `StageFuelingRepository is JdbcStageFuelingRepository` becomes `is ExposedStageFuelingRepository`; the `FUELING_TOOL_SPEC`/`FUELING_TOOL_REGISTRY` constants keep their names but become string tags |
| T9 | `StageReadOnlyGuardTest` | scan `db/ExposedStageFuelingRepository.kt` (+ `StageDbConfig.kt`, `StageFuelingRepository.kt`) for the Exposed write DSL and DDL (`insert`, `insertReturning`, `batchInsert`, `upsert`, `replace`, `update`, `deleteWhere`, `deleteAll`, `SchemaUtils`, `createStatement`, `prepareStatement`, `execute(`, raw `SELECT/INSERT/UPDATE/DELETE` strings); assert the three main lookups still use the case-insensitive `lowerCase()` predicate and that the local repository file is not among the scanned stage files |
| T10 | `GetWeatherToolTest`, `FindFuelingToolTest`, `KoogWeatherAgentTest`, `KoogFuelingAgentTest`, `LoggingPromptExecutorTest`, `LlmTimeZoneResolverTest`, `ApplicationTest`, `RequestTracingTest`, `DbConfigTest`, `StageDbConfigTest`, `DeepseekConfigTest` | import-only moves (`com.aiturbo.tools.*`, `com.aiturbo.llm.deepseekModel`); Kodein test modules for `ApplicationTest` (replacement module, no production import); the config tests keep testing the pure `resolve*` functions |

## API design

**HTTP contracts (unchanged unless stated).**

| Endpoint | Request | Response | Statuses |
|---|---|---|---|
| `POST /weather` | `{"message": string, "model"?: "local" \| "deepseek"}` | `{"message","answer"}` | 200; 400 blank `message`; 400 unknown `model` (naming `local`, `deepseek`); 503 agent/DB unavailable |
| `POST /fueling` | same | `{"message","answer"}` | same |
| `GET /time?location=` | — | `TimeResponse` | 200; 400 missing; 404 unresolvable — DeepSeek-only, no `model` field (ASM-02) |
| `GET /weather/history?limit=` | — | `{"records":[…]}` | 200; 400 bad limit; 503 DB unavailable |
| `GET /` | — | `{"service","usage"}` | 200 |

`model` semantics (ASM-01 exactly): trimmed and case-insensitive; absent or blank → `deepseek`;
`local`/`deepseek` (any case, surrounding whitespace) → that provider; anything else non-blank →
`400 {"error":"Field 'model' must be one of: local, deepseek"}` with no LLM and no tool call. The
response never echoes the provider (ASM-14); the log does (FR-10).

**Request DTOs** (both routes): `message: String = ""` unchanged, plus

```kotlin
@EncodeDefault(EncodeDefault.Mode.NEVER)
val model: String? = null
```

`@EncodeDefault(NEVER)` (kotlinx-serialization, `@OptIn(ExperimentalSerializationApi::class)`) keeps
the `stage=inbound` body of a request without the field byte-identical to today
(`body={"message":"…"}`), which the frozen route tests assert; when the client sends the field it is
logged (`body={"message":"…","model":"local"}`). The implementation verifies this in T5; the
fallback is recorded in risk R6.

**Route handler shape** (`WeatherRouting.kt`; `FuelingRouting.kt` is identical in form):

```kotlin
post {
    val request = call.receive<WeatherRequest>()
    val trace = call.beginTrace(traceJson.encodeToJsonElement<WeatherRequest>(request).toString())
    val message = request.message.trim()
    if (message.isEmpty()) { call.respondTraced(ErrorResponse("Field 'message' is required"), HttpStatusCode.BadRequest); return@post }

    val target = LlmTarget.fromRequest(request.model)
    if (target == null) {
        call.respondTraced(ErrorResponse("Field 'model' must be one of: ${LlmTarget.ALLOWED_VALUES}"), HttpStatusCode.BadRequest)
        return@post
    }

    val answer = withContext(trace + LlmTargetContext(target)) { agent.answer(message) }
    call.respondTraced(WeatherResponse(message = message, answer = answer))
}
```

Bean resolution in routes becomes lazy Kodein retrieval, preserving "routes can be registered
without the production graph" (the frozen route tests bind only a partial graph):

```kotlin
fun Route.weatherRoutes() {
    val agent: WeatherAgent by di.instance()
    val repository: WeatherRecordRepository by di.instance()
    …
}
```

**Internal signatures (new or changed).** `Application.module(overrideModules: List<DI.Module>)`,
`appModules(deepseek, ollama, db, weather): DI.Module`, `fuelingModule(stageDb, apiKeyConfigured):
DI.Module`, `Application.installDi(di)`, `Application.di`/`Route.di`, `pgDataSource(...)`,
`ExposedWeatherRecordRepository(db: Database)`, `ExposedStageFuelingRepository(db: Database)`,
`insertWithRetry(start, maxAttempts, insert)`, `epochMillisOrNull(BigDecimal?)`, `OllamaConfig.from`,
`ollamaModel(id)`, `ollamaPromptExecutor(config)`, `ProviderRoutingPromptExecutor(...)`,
`LlmTarget.fromRequest(value)`, `currentLlmTarget()`, `EnvFile.parse/load`. Everything else keeps its
current signature — notably `WeatherAgent.answer(message)`, `KoogWeatherAgent(executor, toolRegistry,
model)`, `KoogFuelingAgent(...)`, `WeatherRecordRepository`, `StageFuelingRepository`,
`ToolSpecLoader.load`, `LoggingPromptExecutor(delegate, endpoint, toolJsonRenderer)` and the whole
`com.aiturbo.log` API.

**Log contract.** Stage names, correlation id, chain order, 4096-char truncation and the no-secrets
rule are unchanged (ASM-03). Identity of the target is carried by the existing fields of every
`stage=deepseek-request`/`stage=deepseek-response` line: `endpoint=https://api.deepseek.com/chat/completions`
+ the DeepSeek model id, or `endpoint=http://localhost:11434/api/chat` + `model=qwen3:8b`. No new
field is added (D17), matching the illustration in the requirements.

## Data model

No migrations, no DDL, no `SchemaUtils`; both schemas already exist and are only mapped.

**Local `mydb2.users`** (unchanged DDL) mapped by `UsersTable`:

| Column | DDL (existing) | Exposed declaration | DTO field |
|---|---|---|---|
| `id` | `integer PRIMARY KEY DEFAULT nextval('users_id_seq')` | `integer("id").autoIncrement()` (omitted on INSERT) | `WeatherRecord.id: Int` |
| `data` | `varchar(255) NOT NULL UNIQUE` | `varchar("data", 255)` | `data: String` |
| `time` | `varchar(100)` | `varchar("time", 100).nullable()` | `time: String?` |
| `created_at` | `timestamp DEFAULT now()` | `datetime("created_at").nullable()` | `createdAt: String?` via `format(DATA_FORMAT)` |

Insert path: `INSERT INTO users (data, time) VALUES (?, ?) RETURNING id` — Exposed omits unset
columns, so `id` (`nextval`) and `created_at` (`now()`) keep their database defaults, exactly as
before. Retry: up to 3 attempts, +1 s per attempt, on SQL-state `23505`; attempts run in separate
transactions; exhausted → `-1` plus the existing warning; any other failure →
`DatabaseUnavailableException`.

**Stage database** (read-only, `fueling`) mapped by `StageTables`:

| Table | Columns (all read) | DTO |
|---|---|---|
| `fuelings`, `fuelings_archive`, `fuelings_drop` | 22 columns: ids/status/fuel fields as `text`; `amount`, `actual_amount`, `discount_fuel_price`, `vendor_fuel_price` as `decimal(38,18)`; `fueled_orders`, `extra` (jsonb) as `text`; `created_at`, `updated_at`, `finished_at` (epoch ms) as `decimal(38,18)`; `vendor_transaction_date` as `text` | `FuelingRecord` |
| `partner_fueling_events` | `event_id`, `fueling_id`, `partner_id`, `event_name`, `delivery_status`, `data` (jsonb→text), `created_at`, `updated_at` | `PartnerFuelingEvent` |
| `fueling_feedback` | `fueling_feedback_id`, `user_id`, `fueling_id`, `reason_id`, `reason_message`, `requested_at` | `FuelingFeedback` |
| `belka_tokens` | `token`, `fueling_id`, `created_at`, `error_type` | `BelkaToken` |

All DTOs, `FuelingSource`, `FuelingMatch`, `RelatedRows` and `FuelingLookupResult` are unchanged
(same file, same public shape). Table names come from `FuelingSource` only (FR-03).

## Key flows

**F1 — `POST /weather` with `"model":"local"` (Ollama running).** Route receives and decodes the
body (unknown keys ignored), `beginTrace` writes `stage=inbound` with the body, validates `message`
then `model`, and runs the agent inside `withContext(trace + LlmTargetContext(LOCAL))`. The Koog
agent's first `requestLLM` reaches the routing executor, which reads `currentLlmTarget() == LOCAL`
and calls the Ollama-wrapped `LoggingPromptExecutor` with `ollamaModel("qwen3:8b")` →
`stage=deepseek-request endpoint=http://localhost:11434/api/chat model=qwen3:8b tool_choice=-
tools_count=1 tools=[get_weather…]`, then `stage=deepseek-response model=qwen3:8b
tool_calls=[…]`. The strategy executes the tool: `GetWeatherTool` (from `com.aiturbo.tools`) calls
the same clients, inserts via the Exposed repository (`stage=db … saved=true id=…`) and emits
`stage=tool … is_error=false`. The second round trip goes through the same local delegate; the
route answers `{"message","answer"}` and `stage=outbound` closes the chain. Adding `"model":
"deepseek"` or removing the field changes only the two executor calls (DeepSeek endpoint + model id).

**F2 — default / `"model":"deepseek"`, key configured or blank.** No context element (or the
DeepSeek target) → the routing executor's DeepSeek branch → today's `LoggingPromptExecutor` →
`MultiLLMPromptExecutor(LLMProvider.DeepSeek …)`; identical lines and behaviour to the current
release. With a blank key the branch throws `WeatherUnavailableException("DeepSeek API key is not
configured")` *before* the logging decorator: no `stage=deepseek` line, no HTTP attempt, StatusPages
answers 503 with that message (FR-09). The same holds for `/fueling` and for `/time` (no element).

**F3 — `POST /fueling` with `"model":"local"`.** Same plumbing; the tool (`FindFuelingTool` in
`com.aiturbo.tools`) performs the Exposed stage lookup (one read-only transaction, three
case-insensitive main lookups, related rows with `DESC NULLS LAST`), logs
`fuelingLookupFound/NotFound` and `stage=tool`, and the summary answer returns unchanged.

**F4 — unknown `model` (`"gpt-4"`).** `LlmTarget.fromRequest` returns `null`; the handler responds
400 after the inbound line and before any agent call: no `deepseek-request`, no tool, no DB access
(FR-06).

**F5 — Ollama stopped, `"model":"local"`.** The local delegate's client fails to connect; the
routing executor does not catch it (no fallback, FR-08), `LoggingPromptExecutor` logs
`stage=deepseek-response … error=…` with the endpoint/model and no secret, and the exception
propagates to StatusPages → 503 with a plain-language message within the connect budget (NFR-06).
The same connection failure cannot affect other requests: no shared connection, no lock, no state
(one Koog HTTP client per provider, per-request coroutines). A following DeepSeek request succeeds.

**F6 — `/time` unchanged.** No `model` field, no context element → `LlmTimeZoneResolver` keeps
using the routing executor's DeepSeek branch and the DeepSeek model; live lookup, cache, 404
behaviour and log lines are unchanged (ASM-02).

**F7 — startup.** `Application.module` builds the container: `EnvFile.load()` reads `.env` if
present (missing file ignored), the configs resolve, eager singletons load both tool specs (fail
fast), `Database.connect(...)` creates no connection and the LLM clients open nothing. The server
starts with both databases down, no `.env`, Ollama stopped and no key (NFR-04, ASM-08); the first
request that touches a database or an LLM opens a connection then.

**F8 — `GET /weather/history?limit=20`.** `withContext(Dispatchers.IO) { repository.recent(limit) }`
→ one Exposed transaction (one connection) → `ORDER BY id DESC LIMIT ?` → the same JSON row shape;
a database failure becomes `DatabaseUnavailableException` → 503 (FR-02).

**F9 — two weather writes within one second.** Attempt 1 hits the `data` UNIQUE constraint
(`ExposedSQLException` wrapping SQL-state `23505`); the cause-chain check recognises it, the
transaction is discarded, attempt 2 opens a fresh transaction/connection with `+1 s`; the new id is
returned. After 3 attempts `-1` is returned and the existing warning is logged (FR-02, NFR-09).

## Decisions and alternatives

| # | Decision | Alternatives considered and why rejected |
|---|---|---|
| D1 | Kodein core only: one `DI` container built in `Application.module`, stored on the application, beans resolved lazily in handlers | Kodein–Ktor integration artifact (ASM-05 default): no stable plugin for Ktor 3.3.3, unnecessary — a plain container needs no plugin; annotations (`kodein-di-framework-*`): would break the plain-module rule and add artifacts |
| D2 | `org.kodein.di:kodein-di:7.32.0` | 7.33.0: compiled against Kotlin 2.4 metadata (`kotlin-stdlib` 2.4.0) which the 2.3.10 compiler rejects; 7.29.0: older than needed, kept as fallback; Android/erased variants: not needed on JVM |
| D3 | One `DI.Module` per slice (`"app"`, `"fueling"`), tags for the per-provider executor/database bindings, `FUELING_TOOL_SPEC`/`FUELING_TOOL_REGISTRY` kept as name-compatible String tags | Single flat module: hides the slice boundary and the `get_weather`-only vs `find_fueling`-only registry contract (frozen assertions); `overrides = true` bindings for tests: unnecessary — tests build replacement/composed modules, no production import |
| D4 | Lazy `by di.instance()` retrieval inside route builders | Resolving eagerly in `configureRouting(di)`: breaks the frozen route tests that register routes with a partial graph (no `WeatherAgent`/repository bound) |
| D5 | Exposed `exposed-core` + `exposed-jdbc` 1.5.0, no extras | `exposed-json`: the DTOs carry jsonb as text; `exposed-dao`: DSL suffices; older 1.x: not needed; H2 in tests: see D18 |
| D6 | `PGSimpleDataSource` (shipped in the existing PostgreSQL driver) as Exposed's `DataSource`; one physical connection per `transaction`, `ApplicationName=ai-turbo`, stage timeouts | HikariCP: a new runtime dependency (NFR-02) and pooling contradicts "connection per call"; homemade `DataSource` over `DriverManager`: `DriverManager` is forbidden in `src/main` (FR-02 grep guard); one long-lived connection: breaks "starts with the database absent" and adds failure modes |
| D7 | Stage read-only enforced by construction (SELECT-only Exposed DSL, enum-derived table names, one guarded file) + the adapted guard test | JDBC-level read-only connection flag: no Exposed API surface for it in the simple `transaction` form and the previous code did not set it either; relying on the DB user's grants alone: loses the static guard ASM-11 requires |
| D8 | Exposed column types chosen to reproduce the live-verified reads: `text` for jsonb/String columns, `decimal(38,18)` for numeric/epoch columns, `datetime` for `users.created_at`, `autoIncrement` for `users.id` | `long`/`timestamp` for epoch columns: reintroduces the "Bad value for type long" class of failure the current code fixed; declared precision/scale are nominal because no DDL is issued |
| D9 | Keep `insertWithRetry(start, maxAttempts, insert)` and `epochMillisOrNull` as package-level functions with their exact signatures; recognise a unique violation by walking the cause chain | Re-implementing retries inside Exposed (`ignoreErrors`/upsert): changes behaviour and loses the frozen retry tests; catching only `ExposedSQLException`: breaks the direct `SQLException` tests; `ON CONFLICT DO NOTHING`: silently drops the id |
| D10 | `EnvFile` (stdlib, `com.aiturbo.config`, non-data class, no logging) + the unchanged `loadDotenv()` helper and `resolve*` functions | Keeping `dotenv-kotlin`: forbidden by FR-04; putting the reader into `com.aiturbo.db`: wrong home, it serves the DeepSeek secret too; a data class: `toString()` could leak values into logs |
| D11 | Move both tools to `com.aiturbo.tools` unchanged (same class names/constructors, same JSON resources) | Splitting behaviour while moving: out of scope (FR-05); renaming classes: churn without benefit |
| D12 | `model` as nullable `String? = null` with `@EncodeDefault(NEVER)`; validation after the blank-message check; 400 message `Field 'model' must be one of: local, deepseek` | `String = ""` default: the inbound log would gain `,"model":""` for old requests and break the frozen body assertion; echoing the received value in the 400: not required, and it feeds user input back |
| D13 | Per-request selection via a coroutine-context element (`LlmTargetContext`) read by one routing executor; routes wrap the agent call in `withContext(trace + LlmTargetContext(target))` | Two agents per slice selected by the route: duplicates the Koog agents (contradicts FR-07 "the same agents") and puts provider knowledge in the routes; `agent.answer(message, executor)` parameter: changes the frozen fun-interface contract; per-call model selection inside the strategy: no Koog API for it |
| D14 | The routing executor substitutes the selected provider's model (`ollamaModel`) for local calls | Two `KoogWeatherAgent`/`KoogFuelingAgent` instances: same duplication as D13; passing the model through the agent: `AIAgent` fixes `llmModel` at build time |
| D15 | Local failure keeps its exception and maps to the existing 503 handler (`WeatherUnavailableException` → `ErrorResponse(cause.message)`), no fallback | A new `LlmUnavailableException` + handler: extra type and mapping for the same observable 503; silent fallback to DeepSeek: explicitly out of scope (ASM-09) and would falsify the benchmark |
| D16 | The blank-key guard lives in the routing executor, before the logging decorator | Keeping the guard as an "unavailable agent lambda" per slice: makes the local path unusable without a key (ASM-08) and forces a target-aware agent wrapper; guarding inside the logging decorator: would emit a `stage=deepseek-request` line for a request that never leaves the process (breaks frozen tests) |
| D17 | No new log field; `endpoint=`/`model=` carry the target identity | Adding `provider=`/`target=`: ASM-03 leaves it to the design, the requirements' example chain does not show it, and it would change a documented line format for no extra information |
| D18 | Offline tests keep port interfaces + fakes; no H2/embedded DB; Exposed behaviour is covered through the retry seam, the guard scan, the config/selector tests and the live smoke | H2: a new test dependency (NFR-02) and a different SQL dialect — it would test H2, not the Postgres schema; Testcontainers: requires Docker/network (NFR-01) |
| D19 | `ollamaModel(id)` capabilities: `Completion`, `Temperature`, `Tools` (no `ToolChoice`, no `OpenAIEndpoint.Completions`) | Copying the DeepSeek capability list: `ToolChoice`/OpenAI endpoint markers are DeepSeek/OpenAI-specific and may be rejected by the Ollama client; declaring fewer capabilities risks a client-side capability error — verified at implementation and in the live smoke (R1/R2) |
| D20 | README + `application.conf` updated; no latency claims in the README | Leaving the README on Koin/JDBC: contradicts the shipped state (FR-14); putting benchmark numbers in the README: FR-12 stores them in the feature docs |

## Non-functional coverage

| NFR | How the design covers it |
|---|---|
| NFR-01 offline testability | No new test dependency; all new tests use fakes/`MapApplicationConfig`/temp files/`LogCapture`; Exposed and Ollama are never contacted (repositories behind interfaces, executors behind fakes); `.env` is injected into the `resolve*` functions, never read by tests except `EnvFileTest`'s own temp file |
| NFR-02 dependency hygiene | `build.gradle.kts` loses `koin-ktor`/`koin-core`/`koin-test`/`dotenv-kotlin` and gains `kodein-di` + `exposed-core`/`exposed-jdbc`; the Ollama artifact is declared only if compilation requires it; the PostgreSQL driver and logback stay. Nuance recorded in R3: `exposed-core` brings `kotlinx-datetime` and coroutines 1.11.0 transitively — unavoidable with Exposed and noted as a transitive, not direct, addition |
| NFR-03 security | Secrets are read only through `EnvFile` (never logged, non-data class); no config object is passed to `TraceLog` (the executor receives only endpoint/model/flag); `ApplicationName=ai-turbo` keeps sessions identifiable; the 400 message echoes nothing sensitive; the README/config carry placeholders only |
| NFR-04 startup independence | `Database.connect(dataSource)` is lazy, `pgDataSource` opens nothing, the Ollama/DeepSeek clients connect on first use, eager singletons touch only the bundled JSON resources, and a missing/unreadable `.env` yields an empty reader |
| NFR-05 observability | One correlation id per request via the unchanged `CallTrace`; the chain order is unchanged; both executor wrappers emit the documented lines with `endpoint=`/`model=`; `TraceLog.truncate` (4096) and the no-secrets rule are untouched |
| NFR-06 latency/robustness (local) | Connection-per-call, no shared state and per-provider clients keep the server responsive; the connect budget is the Ollama client's timeout configuration (verified, R2) plus the immediate refusal of a closed local port; the 10 s budget is exercised by the smoke case (R7) |
| NFR-07 compatibility | Kotlin 2.3.10 / Ktor 3.3.3 / Koog 1.2.0 / Gradle 8.14.1 / JVM 17 unchanged; versions chosen for metadata compatibility (D2/D5); all HTTP contracts and status codes preserved (API design) |
| NFR-08 build/test discipline | No build log or server log is copied into context: verdict lines only, `grep`/tail for the smoke; the design's verification steps are stated as single commands with a described verdict (planner handoff) |
| NFR-09 data safety | Stage repository is SELECT-only (`selectAll().where { … }`), tables come from the enum-derived map, no `SchemaUtils`/DDL, one guarded file scanned by T9, `ApplicationName` for `pg_stat_activity`; the manual lookup's row counts are unchanged by construction |
| NFR-10 benchmark reproducibility | The requirements' checklist (warm-up, ≥5 runs per provider per endpoint, median + min/max, date/machine/versions) is unchanged; the design only adds that the local runs must have Ollama running and the DeepSeek runs a configured key |

## Open questions and risks for the planner

| # | Risk / open question | Handling |
|---|---|---|
| R1 | **qwen3:8b tool calling (FR-13, ASM-10)** — the whole local path depends on the model emitting tool calls through the Koog client for `get_weather` and `find_fueling`. Blocking, user-dependent (Ollama is user-managed and currently stopped) | Schedule the live gate as the smoke milestone; record one run per endpoint in the feature docs. If tool calling fails: blocker with evidence, options (another local tool-capable tag) are a user decision, not a silent scope change |
| R2 | **Koog Ollama client API** — class/package and constructor are not fully verified: the requirements name `ai.koog.prompt.executor.clients.ollama.OllamaLLMClient`, the Koog 1.2.0 docs show `ai.koog.prompt.executor.ollama.client.OllamaClient` (`KoogHttpClient` primary constructor, JVM `baseUrl` convenience factory). Also: whether the artifact is on the *compile* classpath transitively or must be declared | Verify against the resolved jar (already in the Gradle cache) before writing `OllamaLlm.kt`; use the verified constructor, pass explicit timeouts if the API offers them (NFR-06); declare `implementation("ai.koog:prompt-executor-ollama-client:1.2.0")` only if compilation requires it (ASM-05/06). Fallback: the documented factory form with `baseUrl` |
| R3 | **Version-resolution nuances** — `exposed-core 1.5.0` requests `kotlin-stdlib 2.3.20` (higher than the project's 2.3.10; metadata is readable, a warning is expected) and pulls `kotlinx-datetime`/coroutines 1.11.0 transitively; `kodein-di 7.32.0` keeps stdlib at 2.2.21 | First build after the dependency change is the check (`./gradlew test` verdict). If the build fails on metadata/version alignment: pin the stdlib via a resolution strategy, or step down (`exposed 1.4.x`, `kodein-di 7.29.0`) — both are metadata-compatible |
| R4 | **Exposed insert and transaction semantics** — that unset columns (`id`, `created_at`) are omitted so DB defaults apply, that `insertReturning` yields the id, and that the default `transaction { }` does not internally retry a failed statement (which would multiply the UNIQUE retry attempts) | Covered by the live weather smoke (`users` row with `created_at` set, one row per request) and by the F9 two-requests-in-one-second case; if v1 retries by default, disable it explicitly at the `transaction` call site |
| R5 | **Stage column-type fidelity** — the live stage column types are not verifiable from here; `decimal(38,18)`/`text` choices must not alter a single value of the previously verified report | FR-02's acceptance already requires one live lookup for the verified GUID returning the same values as the previous feature's report; the implementation should diff the two reports during the smoke |
| R6 | **`@EncodeDefault(NEVER)` assumption** — if the annotation does not suppress the defaulted `null` in the trace body, the frozen inbound assertion in the route tests fails | T5 asserts the rendered body explicitly (no `model` key when absent); if the behaviour differs, either keep the annotation semantics via a dedicated trace rendering or adapt the frozen assertion — a decision the implementer makes with the failing test as evidence |
| R7 | **≤10 s local failure budget on macOS** — a closed local port refuses immediately (ECONNREFUSED), a wrong reachable host relies on the client's connect timeout | Verify with the Postman "Ollama stopped" case and, if needed, an unreachable-host variant; the budget is a client-timeout configuration item, not application code |
| R8 | **Container lifecycle** — no explicit close of the executors/clients on shutdown (unchanged from today's Koin behaviour) | No FR/NFR requires it; if the implementation finds an idiomatic Kodein close hook while writing `installDi`, a `monitor.subscribe(ApplicationStopped)` close is a small, optional addition — not a design requirement |
| R9 | **Frozen-test churn is wide but shallow** — 4 Koin-building test files change shape (T8), 1 guard test is rewritten (T9), ~10 files get import-only edits (T10) | Sequence the work: dependencies first, then DI+`EnvFile` (mechanical churn with a green suite), then Exposed (T9/T7), then the tools move (T10), then the model field and the Ollama path (T5/T6/T4) — each step ends with a `./gradlew test` verdict, so a regression is always attributable to one step |
| R10 | **Secret-handling regression risk while replacing `dotenv-kotlin`** — the new reader must not print or expose values, and must not change the precedence that the existing config tests pin | The `resolve*` functions keep their signatures (tests unchanged); `EnvFile` is non-data, has no logging and is covered by `EnvFileTest`; NFR-03's manual secrets search over the logs and tracked files is part of the acceptance walkthrough |
