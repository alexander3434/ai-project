# Fueling Order Lookup Tool — System Design

## Context and goals

The service today knows one Koog agent (weather) and one external data source (Open-Meteo + the local
`users` database). This feature adds a second, independent vertical slice: a `find_fueling` Koog tool
that reads one fueling order by its GUID from the **stage** PostgreSQL database (`fueling` database:
`fuelings`, `fuelings_archive`, `fuelings_drop` plus the related tables), converts the epoch-millisecond
timestamps to human-readable date/time, and hands the collected data back to DeepSeek, which returns a
one-line plain-language summary. A new `POST /fueling` endpoint (mirroring the `POST /weather` contract)
serves the question; the default DeepSeek model becomes `deepseek-flash`. Hard constraints: no UI,
read-only stage access, password only in the git-ignored `.env`, all tests offline, existing 123 tests
green, existing trace chain intact.

## Architecture overview

The feature is a new vertical slice that reuses every existing layer (Koog tool, agent strategy,
`LoggingPromptExecutor`, `TraceLog`, JDBC-per-call, Ktor route, Koin module, JSON tool spec) and shares
the LLM plumbing with the weather slice (`PromptExecutor`, `LLModel`, `ToolJsonRenderer`, `TraceLog`).
It adds exactly two new infrastructure types: `StageDbConfig` and `JdbcStageFuelingRepository`.

```
Postman ──POST /fueling──► plugins/FuelingRouting.kt
                                  │  (beginTrace, blank-message 400, withContext(trace))
                                  ▼
                          fueling/FuelingAgent (interface)
                                  │
                          KoogFuelingAgent ── functionalStrategy loop:
                                  │             requestLLM → executeTools → sendToolResults
                                  │             (TraceLog.tool per call)
                                  ▼
                          ai.koog PromptExecutor  ──► log/LoggingPromptExecutor (stage=deepseek-request/response)
                                  │                                    │
                                  │                                    ▼
                                  │                            DeepSeek API (model deepseek-flash)
                                  ▼
                          fueling/FindFuelingTool  (SimpleTool<FindFuelingArgs>, name+description from
                                  │                  resources/tools/find-fueling-tool.json)
                          ┌───────┴─────────────────────────────┐
                          │ FuelingId.canonicalize (no DB call) │  invalid → InvalidFuelingIdException
                          ▼                                     │
                  db/StageFuelingRepository  (interface)        │
                          │                                     │
                  db/JdbcStageFuelingRepository                 │
                          │  DriverManager per call, 10 s timeouts, prepared statements, SELECT only
                          ▼                                     │
                  STAGE PostgreSQL (fueling: fuelings / archive / drop + events/feedback/belka_tokens)
                          │                                     │
                          └── FuelingLookupResult ──► fueling/FuelingReport.render(...) ──► tool result text
                                                     │
                                                     └── TraceLog.fuelingLookup*  (stage=db line)
```

Request path (one line per stage, one `req=` id): `inbound` → `deepseek-request` → `deepseek-response`
(tool call) → `db` (lookup outcome, emitted by the tool) → `tool` (args + result + `is_error`) →
`deepseek-request` → `deepseek-response` (summary) → `outbound`.

Two separate agents, two separate registries: `/weather` (and the `GET /time` LLM fallback) keep the
`get_weather`-only registry, `/fueling` gets its own `find_fueling`-only registry. Sharing one registry
would put the fueling tool into every weather/`/time` request, breaking the existing
`TraceChainIntegrationTest` assertions (`tools_count=1`, `"name":"get_weather"`) and sending pointless
tokens to the model (NFR-06, NFR-07).

## Components

### `db/StageDbConfig` (new — `src/main/kotlin/com/aiturbo/db/StageDbConfig.kt`)

Responsibility: stage connection settings, resolved the same way as `DbConfig` (config value → env →
`.env`), but **without** a hard-coded fallback password.

```kotlin
data class StageDbConfig(
    val host: String = DEFAULT_HOST,            // "postgres.stage.turboapp.ru"
    val port: Int = DEFAULT_PORT,               // 25432
    val database: String = DEFAULT_DATABASE,    // "fueling"          (ASM-10, verify in the smoke run)
    val user: String = DEFAULT_USER,            // "fueling"
    val password: String = "",                  // config → STAGE_DB_PASSWORD env → .env; never logged
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS, // 10 (ASM-07) — connect, socket and query timeout
) {
    val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$database"

    companion object {
        fun from(config: ApplicationConfig): StageDbConfig   // stageDb.host|port|name|user|password|timeoutSeconds
    }
}

/** config → env → .env; no development default (unlike the weather database). */
fun resolveStageDbPassword(configValue: String, envValue: String?, fileValue: String?): String
```

Dependencies: Ktor `ApplicationConfig`, the shared `loadDotenv()` helper. Nothing connects at
construction — the application starts with the stage database absent (FR-12).

### `db/StageFuelingRepository` + DTOs (new — `src/main/kotlin/com/aiturbo/db/StageFuelingRepository.kt`)

Responsibility: the read port for one fueling order; defines the stage schema shape the rest of the code
sees. Blocking, like `WeatherRecordRepository` (callers wrap in `withContext(Dispatchers.IO)`).

```kotlin
enum class FuelingSource(val tableName: String) {
    FUELINGS("fuelings"), ARCHIVE("fuelings_archive"), DROP("fuelings_drop")
}

data class FuelingMatch(val source: FuelingSource, val record: FuelingRecord)
data class RelatedRows(
    val events: List<PartnerFuelingEvent>,     // total = events.size
    val feedback: List<FuelingFeedback>,
    val tokens: List<BelkaToken>,
)
data class FuelingLookupResult(
    val fuelingId: String,                     // canonical lowercase GUID
    val matches: List<FuelingMatch>,           // one entry per source table that has the row (FR-05/ASM-04)
    val related: RelatedRows,                  // empty lists when nothing matched (FR-06)
)

interface StageFuelingRepository {
    /** Reads the fueling by [fuelingId] from all three tables plus all related rows. Throws DatabaseUnavailableException. */
    fun findByFuelingId(fuelingId: String): FuelingLookupResult
}
```

### `db/JdbcStageFuelingRepository` (new — `src/main/kotlin/com/aiturbo/db/JdbcStageFuelingRepository.kt`)

Responsibility: the only place that talks to the stage database. Plain JDBC, connection per call (so the
app starts fine with no stage DB), `PreparedStatement` only, `SELECT` only (FR-10).

```kotlin
class JdbcStageFuelingRepository(private val config: StageDbConfig) : StageFuelingRepository {
    override fun findByFuelingId(fuelingId: String): FuelingLookupResult
    // internals: one connection per call, opened with Properties(user, password,
    // loginTimeout/connectTimeout/socketTimeout = timeoutSeconds, ApplicationName="ai-turbo"),
    // statement.queryTimeout = timeoutSeconds, SQLException → DatabaseUnavailableException
}
```

`ApplicationName=ai-turbo` is a one-line pgjdbc connection property: it makes this read-only client
identifiable in `pg_stat_activity`, which supports the NFR-03 check ("no write traffic from this app").
The connection properties are the only place the password is used; it is never logged, never rendered.

Query order (fixed, so it is predictable in tests and logs):

1. `SELECT <columns> FROM fuelings WHERE fueling_id = ?`
2. `SELECT <columns> FROM fuelings_archive WHERE fueling_id = ?`
3. `SELECT <columns> FROM fuelings_drop WHERE fueling_id = ?`
4. only when at least one match:
   `SELECT event_id, fueling_id, partner_id, event_name, delivery_status, data, created_at, updated_at FROM partner_fueling_events WHERE fueling_id = ? ORDER BY created_at DESC NULLS LAST`
   `SELECT fueling_feedback_id, user_id, fueling_id, reason_id, reason_message, requested_at FROM fueling_feedback WHERE fueling_id = ? ORDER BY requested_at DESC NULLS LAST`
   `SELECT token, fueling_id, created_at, error_type FROM belka_tokens WHERE fueling_id = ? ORDER BY created_at DESC NULLS LAST`

Injection safety: the only user-controlled value (the GUID) is a bind parameter; table names come from
the `FuelingSource` enum (compile-time constants), never from input. `fuelings` is read through its
partitioned parent — verified to work.

### `fueling/FuelingId` (new — `src/main/kotlin/com/aiturbo/fueling/FuelingId.kt`)

Responsibility: GUID contract from FR-03 and the two domain failures (FR-04, FR-12).

```kotlin
object FuelingId {
    val PATTERN: Regex        // ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$
    /** Trimmed, canonical 8-4-4-4-12 → lowercase; null for anything else (no version/variant check, ASM-03). */
    fun canonicalize(raw: String): String?
}

/** The extracted value is not a canonical GUID — no lookup was performed (FR-04). */
class InvalidFuelingIdException(message: String) : RuntimeException(message)

/** The stage lookup could not be performed: unreachable, auth failure or timeout (FR-12). */
class StageDatabaseUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

`java.util.UUID.fromString` is deliberately **not** used for validation: it accepts short groups
(`1-1-1-1-1`), which FR-03/FR-04 reject.

### `fueling/FindFuelingTool` (new — `src/main/kotlin/com/aiturbo/fueling/FindFuelingTool.kt`)

Responsibility: the Koog tool — validate the id, read stage, log the lookup outcome, render the result.

```kotlin
@Serializable
data class FindFuelingArgs(
    @property:LLMDescription("Идентификатор заказа (GUID в формате 8-4-4-4-12), например 5e12bef2-2f78-48f0-aab5-ccb6bfeb8469")
    val orderId: String,
)

class FindFuelingTool(
    private val repository: StageFuelingRepository,
    spec: ToolSpec,                                    // name + description from the JSON resource
) : SimpleTool<FindFuelingArgs>(argsType = typeToken<FindFuelingArgs>(), name = spec.name, description = spec.description) {
    override suspend fun execute(args: FindFuelingArgs): String
}
```

`execute` behaviour (exact):

1. `FuelingId.canonicalize(args.orderId)` → `null` ⇒ throw `InvalidFuelingIdException("Идентификатор
   '<raw>' не является корректным GUID (формат 8-4-4-4-12). Запрос в базу не выполнялся.")`. No repository
   call, no `stage=db` line — the strategy's `stage=tool` line carries `is_error=true` (FR-04).
2. `withContext(Dispatchers.IO) { repository.findByFuelingId(canonical) }`.
3. `DatabaseUnavailableException` ⇒ `TraceLog.fuelingLookupUnavailable(currentId, name, cause.message)` and
   throw `StageDatabaseUnavailableException("Данные stage временно недоступны: <cause>. Повторите запрос
   позже.", cause)` ⇒ `stage=tool … is_error=true`; the model answers "temporarily unavailable" (FR-12).
4. Otherwise log the outcome and return `FuelingReport.render(result)`: `fuelingLookupFound(...)` or
   `fuelingLookupNotFound(...)` (both non-error results, `is_error=false`).

Tool name: **`find_fueling`** (resource `src/main/resources/tools/find-fueling-tool.json`, name and
description are the file's; the parameter schema Koog sends is generated from `FindFuelingArgs`, exactly
as `get_weather` does today).

### `fueling/FuelingReport` (new — `src/main/kotlin/com/aiturbo/fueling/FuelingReport.kt`)

Responsibility: the pure tool-output renderer (no I/O, no logging, deterministic — unit-testable with a
fixed zone). See §Data model for the exact text.

```kotlin
object FuelingReport {
    const val UTC_SUFFIX = " UTC"
    val UTC: ZoneOffset = ZoneOffset.UTC
    const val MAX_RENDERED_RELATED_ROWS = 50       // per related table; totals always reported (ASM-09)
    const val MAX_JSONB_CHARS = 2000               // per jsonb field, marker "…[truncated, N chars total]"

    fun render(result: FuelingLookupResult): String
    /** "2026-09-21 14:32:11 UTC" for a plausible epoch-ms value, the raw number otherwise, "-" for null. */
    internal fun formatEpochMillis(value: Long?): String
}
```

### `fueling/FuelingAgent` + `KoogFuelingAgent` (new — `src/main/kotlin/com/aiturbo/fueling/FuelingAgent.kt`)

Responsibility: the agent that answers fueling questions; same shape as `WeatherAgent`/`KoogWeatherAgent`,
own system prompt (FR-09), own registry, `maxToolRounds = 3`, `temperature = 0.0`, `maxIterations = 10`,
agent id `fueling-agent`.

```kotlin
fun interface FuelingAgent { suspend fun answer(message: String): String }

class KoogFuelingAgent(
    executor: PromptExecutor,
    toolRegistry: ToolRegistry,     // contains find_fueling only
    model: LLModel,                 // the shared deepseekModel(deepseek.model) = deepseek-flash
    maxToolRounds: Int = 3,
) : FuelingAgent
```

System prompt (verbatim, mirrors the weather one in tone):

```
Ты — ассистент по заказам на пролив (заправку). Отвечай на языке пользователя.
Если в вопросе есть идентификатор заказа (GUID вида 8-4-4-4-12) — ОБЯЗАТЕЛЬНО вызывай инструмент
find_fueling и строй ответ только на его результате; никогда не выдумывай данные и не отвечай по памяти.
Если идентификатора в вопросе нет — попроси прислать GUID заказа и не вызывай инструмент.
Если инструмент вернул ошибку — объясни её простыми словами (некорректный идентификатор или
временная недоступность данных stage).
Ответ — одна понятная фраза: найден ли заказ и в какой таблице, статус, суммы, тип топлива,
колонка/пистолет/заправка, время в читаемом виде, заметные связанные события, токены и обращения.
Не упоминай названия инструментов и не выводи JSON.
```

### `plugins/FuelingRouting` (new — `src/main/kotlin/com/aiturbo/plugins/FuelingRouting.kt`)

Responsibility: `POST /fueling`, byte-for-byte the same handler shape as `POST /weather`.

```kotlin
@Serializable data class FuelingRequest(val message: String = "")
@Serializable data class FuelingResponse(val message: String, val answer: String)

fun Route.fuelingRoutes()   // route("/fueling") { post { … } }, agent obtained by inject<FuelingAgent>()
```

### `log/TraceLog` additions (modify — `src/main/kotlin/com/aiturbo/log/TraceLog.kt`)

Three new functions, each emitting exactly one `stage=db` line (FR-14, NFR-01); the existing
`toolDb(saved=…)` shape stays untouched for weather.

```kotlin
fun fuelingLookupFound(id: String?, tool: String, tables: List<String>, events: Int, tokens: Int, feedback: Int, capped: Boolean)
fun fuelingLookupNotFound(id: String?, tool: String, tables: List<String>)
fun fuelingLookupUnavailable(id: String?, tool: String, reason: String?)
```

### Koin wiring (modify — `src/main/kotlin/com/aiturbo/Application.kt`)

The existing `appModules(deepseek, db, weather)` keeps its signature and its bindings untouched (the
frozen `AppModulesTest` asserts them). The feature adds a sibling module, composed in
`Application.module`:

```kotlin
val FUELING_TOOL_SPEC = named("fuelingToolSpec")
val FUELING_TOOL_REGISTRY = named("fuelingToolRegistry")

fun fuelingModule(stageDb: StageDbConfig, apiKeyConfigured: Boolean): Module = module {
    single(createdAtStart = true, qualifier = FUELING_TOOL_SPEC) {          // fail fast at startup
        ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH)
    }
    single<StageFuelingRepository> { JdbcStageFuelingRepository(stageDb) }
    single { FindFuelingTool(get(), get(FUELING_TOOL_SPEC)) }
    single(qualifier = FUELING_TOOL_REGISTRY) { ToolRegistry.builder().tool(get<FindFuelingTool>()).build() }
    single<FuelingAgent> {
        if (apiKeyConfigured) KoogFuelingAgent(get(), get(FUELING_TOOL_REGISTRY), get())
        else FuelingAgent { throw WeatherUnavailableException("DeepSeek API key is not configured") }
    }
}
```

`Application.module` (default branch only) installs
`listOf(appModules(deepseek = …, db = …, weather = …), fuelingModule(stageDb = StageDbConfig.from(environment.config), apiKeyConfigured = deepseek.apiKey.isNotBlank()))`.
The `overrideModules` path is unchanged, so every existing route test keeps working. `get<PromptExecutor>()`
and `get<LLModel>()` come from `appModules` (the single choke point and the shared model id).

### `tools/ToolSpec` + resource (modify/new)

`ToolSpecLoader` gains `const val FUELING_RESOURCE_PATH = "tools/find-fueling-tool.json"`; the new
resource follows the shipped convention (name, description, parameter documentation):

```json
{
  "name": "find_fueling",
  "description": "Находит заказ на пролив (заправку) по идентификатору (GUID) в данных stage: ищет запись в таблицах fuelings, fuelings_archive, fuelings_drop и связанные события, обращения и токены. Возвращает поля заказа с читаемыми датами.",
  "parameters": {
    "type": "object",
    "properties": {
      "orderId": { "type": "string", "description": "Идентификатор заказа (GUID в формате 8-4-4-4-12)" }
    },
    "required": ["orderId"]
  }
}
```

### Logging design — the trace chain

One correlation id, the same `req=` id as today (`CallTrace` in the coroutine context; the tool runs
inside the agent's context, which runs inside `withContext(trace)` in the route). New/changed lines:

```
req=<id> stage=inbound method=POST path=/fueling query=- client=… body={"message":"Найди данные по проливу для заказа 5e12bef2-…"}
req=<id> stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-flash tool_choice=- tools_count=1 tools=[{"type":"function","function":{"name":"find_fueling",…}}] messages=[system: "Ты — ассистент по заказам…", user: "Найди данные…"]
req=<id> stage=deepseek-response model=deepseek-flash text="" tool_calls=[{"name":"find_fueling","args":"{\"orderId\":\"5e12bef2-…\"}"}]
req=<id> stage=db tool=find_fueling lookup=found records=1 tables=fuelings_archive events=2 tokens=1 feedback=0
req=<id> stage=tool tool=find_fueling args={"orderId":"5e12bef2-…"} is_error=false result="НАЙДЕНО: 1 (fuelings_archive) …"
req=<id> stage=deepseek-request endpoint=… model=deepseek-flash tool_choice=- tools_count=1 tools=[…] messages=[…, tool: "…"]
req=<id> stage=deepseek-response model=deepseek-flash text="Заказ 5e12bef2-… найден в архиве…" tool_calls=[]
req=<id> stage=outbound status=200 body={"message":"…","answer":"…"}
```

`stage=db` variants: `lookup=not_found records=0`, `lookup=unavailable reason="…"`, plus an optional
` capped=true` when the rendered related rows were cut by `MAX_RENDERED_RELATED_ROWS`. No `stage=db` line
exists for an invalid id (FR-04). The existing truncation (`MAX_BODY_CHARS = 4096`) and the "no secret is
ever logged" rule apply unchanged: `TraceLog` receives no config object, no password, no API key — the
repository never logs and the tool only passes the failure message (FR-14, NFR-02).

Order note: the requirements' prose lists `tool` before `db`; the shipped order is `db` → `tool` because
the tool emits the lookup line while executing and the strategy emits the `tool` line after the call
returns — identical to the verified weather chain (`TraceChainIntegrationTest`). Both lines exist, in one
request, with one id.

### Configuration changes (`src/main/resources/application.conf`)

```hocon
deepseek { model = "deepseek-flash"   model = ${?DEEPSEEK_MODEL} }   # was "deepseek-chat" (FR-13)

stageDb {
    # Stage PostgreSQL (read-only; the password never lives in this file).
    # It is resolved: 1. STAGE_DB_PASSWORD env, 2. the git-ignored .env file. No default.
    host = "postgres.stage.turboapp.ru"   host = ${?STAGE_DB_HOST}
    port = "25432"                        port = ${?STAGE_DB_PORT}
    name = "fueling"                      name = ${?STAGE_DB_NAME}
    user = "fueling"                      user = ${?STAGE_DB_USER}
    password = ""                         password = ${?STAGE_DB_PASSWORD}
    timeoutSeconds = "10"                 timeoutSeconds = ${?STAGE_DB_TIMEOUT_SECONDS}
}
```

The local weather database keys (`db.*`) are untouched, and the stage settings are never resolved from
them (FR-11). Manual step for the operator: add `STAGE_DB_PASSWORD=…` to the git-ignored `.env`.

### Files to create / modify

Create:

| File | Content |
|---|---|
| `src/main/kotlin/com/aiturbo/db/StageDbConfig.kt` | `StageDbConfig`, `resolveStageDbPassword` |
| `src/main/kotlin/com/aiturbo/db/StageFuelingRepository.kt` | `StageFuelingRepository`, `FuelingSource`, `FuelingLookupResult`, `FuelingMatch`, `FuelingRecord`, `RelatedRows`, `PartnerFuelingEvent`, `FuelingFeedback`, `BelkaToken` |
| `src/main/kotlin/com/aiturbo/db/JdbcStageFuelingRepository.kt` | the JDBC implementation |
| `src/main/kotlin/com/aiturbo/fueling/FuelingId.kt` | `FuelingId`, `InvalidFuelingIdException`, `StageDatabaseUnavailableException` |
| `src/main/kotlin/com/aiturbo/fueling/FindFuelingTool.kt` | `FindFuelingArgs`, `FindFuelingTool` |
| `src/main/kotlin/com/aiturbo/fueling/FuelingReport.kt` | the tool-output renderer |
| `src/main/kotlin/com/aiturbo/fueling/FuelingAgent.kt` | `FuelingAgent`, `KoogFuelingAgent`, `SYSTEM_PROMPT` |
| `src/main/kotlin/com/aiturbo/plugins/FuelingRouting.kt` | `FuelingRequest`, `FuelingResponse`, `fuelingRoutes()` |
| `src/main/resources/tools/find-fueling-tool.json` | tool name/description/parameter doc |
| `src/test/kotlin/com/aiturbo/FuelingTestFixtures.kt` | `FakeStageFuelingRepository` + sample rows shared by the new tests |
| `src/test/kotlin/com/aiturbo/StageDbConfigTest.kt`, `FuelingIdTest.kt`, `FuelingReportTest.kt`, `FindFuelingToolTest.kt`, `FuelingRoutesTest.kt`, `KoogFuelingAgentTest.kt`, `FuelingModulesTest.kt`, `FuelingChainIntegrationTest.kt`, `StageReadOnlyGuardTest.kt` | the offline suite (see the FR/NFR tables below) |

Modify:

| File | Change |
|---|---|
| `src/main/kotlin/com/aiturbo/Application.kt` | `DEFAULT_MODEL = "deepseek-flash"`; `FUELING_TOOL_SPEC`/`FUELING_TOOL_REGISTRY` qualifiers; `fuelingModule(...)`; compose both modules in `Application.module` |
| `src/main/kotlin/com/aiturbo/plugins/Routing.kt` | register `fuelingRoutes()` inside `routing { }` |
| `src/main/kotlin/com/aiturbo/log/TraceLog.kt` | the three `fuelingLookup*` functions |
| `src/main/kotlin/com/aiturbo/tools/ToolSpec.kt` | `ToolSpecLoader.FUELING_RESOURCE_PATH` |
| `src/main/resources/application.conf` | `deepseek.model` default; `stageDb { … }` block |
| `README.md` | API table, fueling section + Postman example, trace examples (`deepseek-flash`), config table (`STAGE_DB_*`), test list, feature-docs link |
| `.env` (git-ignored, not in the repo) | add `STAGE_DB_PASSWORD=…` — operator step, documented in README |

No new Gradle dependency (NFR-06): the PostgreSQL driver, Koog, Ktor and kotlinx.serialization are
already on the classpath.

## API design

### `POST /fueling` (external)

| Aspect | Contract |
|---|---|
| Method / path | `POST http://localhost:8080/fueling` |
| Headers | `Content-Type: application/json` |
| Request body | `{"message": "<natural-language question containing the order GUID>"}` — `FuelingRequest(message: String = "")` |
| 200 body | `{"message": "<trimmed user message>", "answer": "<plain-language summary from DeepSeek>"}` — `FuelingResponse` |
| 400 | Blank/absent `message` → `{"error":"Field 'message' is required"}` (same wording as `/weather`); invalid JSON body → `{"error":"Invalid request body"}` (existing `ContentTransformationException` handler) |
| 503 | No `DEEPSEEK_API_KEY` → `{"error":"DeepSeek API key is not configured"}` (via `WeatherUnavailableException` → existing StatusPages handler) |
| 200 (degraded) | Stage DB unreachable/timeout → the tool result is an error, the model answers that the stage data is temporarily unavailable; **no 503** (FR-12, ASM-07) |
| 200 (business) | Unknown but valid GUID → "not found in stage data"; malformed GUID / no GUID → the model asks for a valid order id (FR-04, ASM-05, ASM-06) |
| 500 | Only for an unexpected failure (e.g. the DeepSeek call itself fails) — same as `/weather` |
| Auth | none (local service) |

Request-level validation happens before the agent runs: `message.trim()` empty → 400 with no agent call
and no DeepSeek line. All other outcomes are HTTP 200 with a plain-language `answer` (no 404, ASM-06).

### Koog tool (internal contract)

| Aspect | Contract |
|---|---|
| Name / description | from `tools/find-fueling-tool.json` (file is the source of truth; a missing/invalid resource fails fast at startup) |
| Parameters | exactly one: `orderId` (string, canonical GUID 8-4-4-4-12, case-insensitive, trimmed) |
| Returns | compact structured text (see §Data model) describing the matches, the fields and the related rows |
| Invalid id | throws `InvalidFuelingIdException`; the strategy logs `stage=tool … is_error=true`; no database lookup, no `stage=db` line (FR-04) |
| Stage DB unavailable | throws `StageDatabaseUnavailableException`; `stage=db lookup=unavailable` + `stage=tool … is_error=true` (FR-12) |
| Not found | returns the not-found text as a normal result (`is_error=false`) (ASM-06) |
| Concurrency | the tool is stateless; one lookup per call |
| Multiplicity | one GUID per call; several GUIDs in one question become several tool calls inside the existing 3-round loop (ASM-05) |

### Internal interfaces

```kotlin
interface StageFuelingRepository { fun findByFuelingId(fuelingId: String): FuelingLookupResult }
fun interface FuelingAgent { suspend fun answer(message: String): String }
object FuelingId { fun canonicalize(raw: String): String? }
object FuelingReport { fun render(result: FuelingLookupResult): String }
fun TraceLog.fuelingLookupFound(id: String?, tool: String, tables: List<String>, events: Int, tokens: Int, feedback: Int, capped: Boolean)
fun TraceLog.fuelingLookupNotFound(id: String?, tool: String, tables: List<String>)
fun TraceLog.fuelingLookupUnavailable(id: String?, tool: String, reason: String?)
fun appModules(deepseek: DeepseekConfig, db: DbConfig, weather: WeatherConfig): Module   // unchanged
fun fuelingModule(stageDb: StageDbConfig, apiKeyConfigured: Boolean): Module
```

## Data model

### Stage schema (read-only, `fueling` database)

| Table | Role in this feature | Key columns read |
|---|---|---|
| `fuelings` (partitioned, ~47K) | source; read through the parent (all 24 partitions) | `fueling_id` (GUID), `vendor_fueling_order_id`, `user_id`, `status`, `amount`, `actual_amount`, `discount_fuel_price`, `vendor_fuel_price`, `fuel_type`, `gas_station_id`, `gas_pump_id`, `refueling_gun_id`, `fuel_reservation_key`, `fueling_type`, `fueling_payment_type`, `failed_reason`, `fueled_orders` (jsonb), `extra` (jsonb), `created_at`, `updated_at`, `finished_at`, `vendor_transaction_date` |
| `fuelings_archive` (~124K) | second source of the same shape | same columns |
| `fuelings_drop` (~87K) | third source of the same shape | same columns |
| `partner_fueling_events` (~67K) | related by `fueling_id` | `event_id`, `fueling_id`, `partner_id`, `event_name`, `delivery_status`, `data` (jsonb), `created_at`, `updated_at` |
| `fueling_feedback` (0 rows) | related by `fueling_id` | `fueling_feedback_id`, `user_id`, `fueling_id`, `reason_id`, `reason_message`, `requested_at` |
| `belka_tokens` (57 rows) | related by `fueling_id` | `token`, `fueling_id`, `created_at`, `error_type` |

A GUID may live in any of the three main tables; every match is returned and labelled (ASM-04). Related
rows are keyed by `fueling_id` and are fetched once per lookup, shared by all matches. No writes, no
migrations, no DDL — the feature has no persisted state of its own (FR-10, NFR-03).

### Tool DTOs (`FuelingRecord` and related)

`FuelingRecord` (all `String?` unless noted): `fuelingId: String`, `vendorFuelingOrderId`, `userId`,
`status`, `amount: BigDecimal?`, `actualAmount: BigDecimal?`, `discountFuelPrice: BigDecimal?`,
`vendorFuelPrice: BigDecimal?`, `fuelType`, `gasStationId`, `gasPumpId`, `refuelingGunId`,
`fuelReservationKey`, `fuelingType`, `fuelingPaymentType`, `failedReason`, `fueledOrders` (jsonb text),
`extra` (jsonb text), `createdAt: Long?`, `updatedAt: Long?`, `finishedAt: Long?`,
`vendorTransactionDate: Long?`.

`PartnerFuelingEvent`: `eventId`, `fuelingId`, `partnerId`, `eventName`, `deliveryStatus`, `data`
(jsonb text), `createdAt: Long?`, `updatedAt: Long?`.
`FuelingFeedback`: `fuelingFeedbackId`, `userId`, `fuelingId`, `reasonId`, `reasonMessage`,
`requestedAt: Long?`.
`BelkaToken`: `token`, `fuelingId`, `createdAt: Long?`, `errorType`.

Reading rules: text/numeric id columns via `getString`; amounts and prices via `getBigDecimal` (rendered
`toPlainString()`); jsonb columns via `getString` (keeps the JSON text; truncated at render time);
epoch columns via `getLong` + `wasNull()`. A `SQLException` anywhere (including a schema surprise such as
a non-numeric timestamp column) becomes `DatabaseUnavailableException` → the graceful unavailable path.

### Timestamp rendering (ASM-08 → decision)

* Reference zone: **UTC**, rendered as `yyyy-MM-dd HH:mm:ss UTC` (e.g. `2026-09-21 14:32:11 UTC`).
  A single constant (`FuelingReport.UTC`) makes it the only place to change if Moscow time is ever
  requested.
* Plausibility window: `2000-01-01T00:00:00Z` … `2100-01-01T00:00:00Z` (946 684 800 000 … 4 102 444 800 000 ms).
  A numeric value inside the window is converted; outside it the raw number is printed (`<value> (raw)`)
  so no 13-digit value can leak into an answer and no crash can occur; `null` prints `-` (ASM-08, NFR-08).
* Fields converted: fueling `created_at`, `updated_at`, `finished_at`, `vendor_transaction_date`;
  partner-event `created_at`, `updated_at`; feedback `requested_at`; belka `created_at`. Raw JSON
  (`fueled_orders`, `extra`, event `data`) is passed through, capped at `MAX_JSONB_CHARS = 2000` per
  field with the marker `…[truncated, N chars total]`.

### Tool output text (what DeepSeek receives)

Decision: **compact structured text**, not JSON (see §Decisions D-04).

Found (one block per match, in the query order `fuelings`, `fuelings_archive`, `fuelings_drop`):

```
НАЙДЕНО: 2 (fuelings, fuelings_archive)
record[1] table=fuelings
  fueling_id=5e12bef2-2f78-48f0-aab5-ccb6bfeb8469
  vendor_fueling_order_id=…
  user_id=…
  status=SUCCESS
  amount=45.00
  actual_amount=45.00
  discount_fuel_price=1.50
  vendor_fuel_price=44.90
  fuel_type=AI-95
  gas_station_id=21
  gas_pump_id=12
  refueling_gun_id=3
  fuel_reservation_key=…
  fueling_type=…
  fueling_payment_type=…
  failed_reason=-
  fueled_orders={"orders":[…]}
  extra={…}
  created_at=2026-09-21 14:32:11 UTC
  updated_at=2026-09-21 14:32:41 UTC
  finished_at=2026-09-21 14:32:41 UTC
  vendor_transaction_date=2026-09-21 14:32:05 UTC
events: total=2 shown=2
  event[1] event_id=… partner_id=… event_name=FUELING_DELIVERED delivery_status=DELIVERED created_at=2026-09-21 14:33:00 UTC updated_at=… data={…}
  event[2] …
feedback: total=0 shown=0
belka_tokens: total=1 shown=1
  token[1] token=… error_type=- created_at=2026-09-21 14:32:12 UTC
```

Not found:

```
НЕ НАЙДЕНО: заказ 00000000-0000-4000-8000-000000000000 отсутствует в fuelings, fuelings_archive, fuelings_drop (stage, база fueling).
```

Related rows are ordered newest-first (`ORDER BY <time> DESC NULLS LAST`), the first
`MAX_RENDERED_RELATED_ROWS = 50` per table are rendered, and `total=`/`shown=` always carry the real
counts (ASM-09). Every documented column is always present (a missing value prints `-`), so no field can
be silently dropped (FR-07). No masking (ASM-13).

## Key flows

### F-1 Success — the order is in `fuelings_archive`

1. Postman sends `POST /fueling` with the example question; the route calls `beginTrace(body)` →
   `stage=inbound` with the same `req=` id.
2. `message.trim()` is non-empty; `withContext(trace) { agent.answer(message) }` runs the Koog agent.
3. Round 1: `LoggingPromptExecutor` logs `stage=deepseek-request` (`model=deepseek-flash`,
   `tools_count=1`, the `find_fueling` definition from the JSON resource) and the model answers with a
   tool call `{"orderId":"5e12bef2-…"}` → `stage=deepseek-response`.
4. The strategy executes the tool: `FuelingId.canonicalize` trims and lowercases the GUID; the repository
   opens one connection (timeouts + prepared statements) and runs the three main `SELECT`s, finds one row
   in `fuelings_archive`, then the three related `SELECT`s; the tool logs
   `stage=db tool=find_fueling lookup=found records=1 tables=fuelings_archive events=2 tokens=1
   feedback=0` and returns the rendered text.
5. The strategy logs `stage=tool … is_error=false result="НАЙДЕНО: 1 (fuelings_archive) …"` and sends the
   result back; round 2 produces the Russian one-line summary (`stage=deepseek-request/response` again,
   the second request carrying the tool message).
6. The route responds 200 `{"message":…,"answer":"…"}` and logs `stage=outbound status=200`.

Edge cases in the same flow: the same GUID in two tables → two `record[n]` blocks and
`tables=fuelings,fuelings_archive`; a record with no related rows → `total=0 shown=0` blocks; a huge
`extra`/`fueled_orders` → capped with the truncation marker; the `stage=tool` log line itself is capped
at 4096 chars as today.

### F-2 Not found

Steps 1–4 as above; all three tables return no row, related queries are skipped, the tool logs
`stage=db tool=find_fueling lookup=not_found records=0` and returns the not-found text as a **normal**
result. The model answers "no data for this id in the stage database" (HTTP 200, no 404 — ASM-06).

### F-3 Malformed / absent id

`{"message":"Найди заказ 12345"}` → the model calls the tool with `orderId="12345"` (it cannot know the
format) → `FuelingId.canonicalize` returns null → `InvalidFuelingIdException`, **no** repository call and
**no** `stage=db` line → `stage=tool … is_error=true result="…не является корректным GUID…"` → the model
answers in plain language that the id is not a valid GUID. If the question contains no id at all
(`{"message":"Найди данные по проливу"}`) the system prompt keeps the model from calling the tool and it
asks for the GUID. In both cases HTTP 200; a blank `message` is rejected earlier with 400 and never
reaches the agent.

### F-4 Stage database down (unreachable host, auth failure, timeout)

The very first `DriverManager.getConnection` blocks at most `stageDb.timeoutSeconds` (default 10 s) and
throws `SQLException` → `DatabaseUnavailableException`. The tool logs
`stage=db tool=find_fueling lookup=unavailable reason="…"` and throws
`StageDatabaseUnavailableException` → `stage=tool … is_error=true` → the model answers "данные stage
временно недоступны, повторите позже". The route still returns 200 within the 30 s budget (NFR-04); the
server stays up and `GET /time`, `POST /weather` are unaffected. If the exception ever escaped the tool,
the existing StatusPages mapping of `DatabaseUnavailableException` still answers 503 instead of 500
(safety net).

### F-5 No DeepSeek API key

`apiKeyConfigured = false` → the `FuelingAgent` binding is the lambda that throws
`WeatherUnavailableException("DeepSeek API key is not configured")` → the existing StatusPages handler
answers 503 with that message; no `stage=deepseek-*`, no `stage=tool`, no `stage=db` lines. The app
starts normally (FR-11/FR-12), and the weather behaviour is identical.

## Decisions and alternatives

| # | Decision | Alternatives considered | Rationale |
|---|---|---|---|
| D-01 | **Dedicated `FuelingAgent` + `find_fueling`-only registry; `POST /fueling` (ASM-01)** | (a) add the tool to the existing weather registry and keep one agent; (b) one agent with two routes and a prompt switch | (a) breaks frozen tests (`TraceChainIntegrationTest` asserts `tools_count=1` and the `get_weather` definition for `/weather` and `/time`) and sends the fueling tool into every weather/`/time` request (NFR-06/NFR-07); (b) mixes two domains in one prompt (FR-09 wants unambiguous routing). A sibling agent mirrors the existing pattern 1:1 and keeps the weather slice untouched (FR-16) |
| D-02 | **Separate Koin module `fuelingModule(stageDb, apiKeyConfigured)`, composed in `Application.module`; named qualifiers for the fueling spec/registry** | (a) add a 4th parameter to `appModules`; (b) unnamed second `ToolSpec`/`ToolRegistry` bindings | (a) either breaks the frozen `AppModulesTest` call site or forces that test to be edited (NFR-05 allows edits only for the model default), and an eager second spec load would break its "loaded once" assertion; (b) duplicate unnamed bindings of the same type make `get<ToolSpec>()`/`get<ToolRegistry>()` ambiguous and would feed the wrong descriptors to `/time`. Named qualifiers are standard Koin and leave every existing binding untouched |
| D-03 | **Invalid id → throw `InvalidFuelingIdException`; DB down → throw `StageDatabaseUnavailableException`; not found → normal string result** | (a) return a plain string for all three (no error flag); (b) use Koog's `ToolException.ValidationFailure`; (c) extend `Tool<TArgs, ToolResult>` | FR-04 explicitly requires `is_error=true` for an invalid id; the repo's own test (`KoogWeatherAgentTest`) verifies that a throwing `SimpleTool` becomes an error tool result and that the loop still answers. (a) contradicts FR-04; (b) depends on a Koog API whose 1.2.0 availability and error propagation are unverified and cannot carry a cause for the DB case; (c) deviates from the shipped `SimpleTool` pattern for no gain |
| D-04 | **Tool result = compact structured text with English field labels and Russian prose lines**, not JSON | (a) JSON via kotlinx.serialization; (b) prose only | (a) is machine-friendly but token-heavy, double-escapes jsonb inside JSON and invites the model to echo JSON (FR-08 forbids it); (b) loses the field/table structure the model must map. Text with `key=value` lines keeps the DB vocabulary, is cheap, readable and testable |
| D-05 | **Timestamps in UTC, `yyyy-MM-dd HH:mm:ss UTC` (ASM-08)** | (a) `Europe/Moscow`; (b) machine-local zone; (c) configurable zone | UTC is the analyst's assumption, is machine-independent and unambiguous with the explicit suffix; (a) hard-codes a business zone, (b) makes results non-reproducible across machines, (c) is scope not asked for. One constant to change if the user asks |
| D-06 | **Plausibility window instead of unconditional conversion** | convert every numeric value to a date | ASM-08 requires that "any numeric field that turns out not to be epoch millis must still be rendered human-readably"; a range check turns a schema surprise into readable output instead of a wrong date |
| D-07 | **No SQL `LIMIT` on related queries; cap only what is rendered (50 rows/table), always report real totals** | (a) SQL `LIMIT 50` + `count(*) OVER ()`; (b) no cap at all | (b) risks a 50 000-row tool message (context blow-up); (a) saves transfer but adds window-function SQL that no offline test can exercise. Related rows per single fueling are few; the render cap satisfies ASM-09 ("the total count must be stated") with the simplest auditable SQL. Revisit if real data shows large event sets |
| D-08 | **Read-only by construction: `SELECT`-only repository + a static guard test** | `connection.isReadOnly = true` as defence in depth | `setReadOnly` is a client-side flag in pgjdbc (no guarantee the server enforces it) and cannot be verified by the offline suite; FR-10's acceptance criterion is exactly a text search over production sources, which the guard test performs (NFR-03) |
| D-09 | **`WHERE fueling_id = ?` with the canonical lowercase GUID (index-friendly)** | `WHERE lower(fueling_id) = ?` (case-insensitive on both sides) | The analyst verified exactly this predicate against the stage; all observed `fueling_id` values are lowercase canonical. The alternative defeats a possible index on `fueling_id` and removes the signal that a wrongly-cased stored value exists. Recorded as an assumption with a smoke check and a one-line fallback (see R-3) |
| D-10 | **Timeouts: `stageDb.timeoutSeconds` (default 10, ASM-07) applied as connect/socket/login timeout and `statement.queryTimeout`** | (a) `DriverManager.setLoginTimeout` (JVM-global); (b) no timeout | (a) would also affect the local weather database connection; (b) risks a hanging request (NFR-04). Per-connection properties are local to the stage connection |
| D-11 | **Reuse `DatabaseUnavailableException` and `WeatherUnavailableException`; no new StatusPages handler** | new `StageUnavailableException` + `FuelingUnavailableException` + two handlers | The types already mean "database unavailable → 503" and "LLM unavailable → 503"; duplicating them adds handlers with no behavioural difference. `WeatherUnavailableException`'s name is weather-flavoured but its contract is app-wide ("the LLM agent is unavailable"); renaming it would touch frozen tests |
| D-12 | **`FuelingReport` holds all rendering; the JDBC layer returns raw DTOs (epoch values as `Long?`)** | format timestamps inside the repository (reusing `db/DATA_FORMAT`) | Keeps conversion pure, clock-free and unit-testable offline (`FuelingReportTest`), and keeps JDBC code free of presentation concerns; the existing `DATA_FORMAT` is package-internal to the weather repository |
| D-13 | **Duplicate the ~25-line strategy loop in `KoogFuelingAgent`** | extract a shared strategy helper used by both agents | Extraction touches the frozen `KoogWeatherAgent`, whose test asserts the non-convergence warning comes from the `com.aiturbo.weather.KoogWeatherAgent` logger; the duplication is small, local, and mirrors the repo's per-slice structure (FR-16, NFR-05). If the planner prefers extraction, the logger name must be preserved |
| D-14 | **Model default `deepseek-flash` in `application.conf` and `DeepseekConfig.DEFAULT_MODEL`; every LLM path inherits it (weather agent, `/time` resolver, fueling agent)** | per-agent model overrides | FR-13/NFR-07 demand the cheapest tier everywhere as the default with a single env override (`DEEPSEEK_MODEL`); no test asserts the old default, so nothing else changes. A per-agent model would add knobs nobody asked for |
| D-15 | **`stage=db` line emitted by the tool, not the repository** | repository-side logging | The tool owns the tool name and the outcome vocabulary; the repository has no trace context (like the weather repository, which never logs). Keeps `TraceLog` calls in one place per flow and the correlation id intact |

## Non-functional coverage

### FR traceability

| FR | Design element |
|---|---|
| FR-01 | §API `POST /fueling`; `plugins/FuelingRouting.kt` (`FuelingRequest`/`FuelingResponse`, 400 on blank `message`, JSON always) → `FuelingRoutesTest` |
| FR-02 | `FindFuelingTool` + `tools/find-fueling-tool.json` + `ToolSpecLoader.FUELING_RESOURCE_PATH` + `FUELING_TOOL_REGISTRY`; chain test asserts `tools_count=1`, `"name":"find_fueling"`, the resource's description, and one `stage=tool` line with the GUID |
| FR-03 | `FindFuelingArgs.orderId` + `FuelingId.canonicalize` + the resource's `parameters` block → `FuelingIdTest` (case, whitespace, rejects) |
| FR-04 | D-03 invalid path: no repository call, no `stage=db`, `is_error=true`, plain-language answer; system prompt handles "no id" → `FindFuelingToolTest`, `FuelingChainIntegrationTest` |
| FR-05 | `JdbcStageFuelingRepository` queries all three tables through the parent; `FuelingMatch.source` labels each match → `FindFuelingToolTest`, `FuelingReportTest` |
| FR-06 | the three related queries by `fueling_id`; empty lists are normal → `FakeStageFuelingRepository` fixtures, `FindFuelingToolTest` |
| FR-07 | `FuelingRecord` + related DTOs carrying every documented column; `FuelingReport` renders all of them with converted timestamps → `FuelingReportTest` |
| FR-08 | tool returns text → the strategy's second round-trip; `FuelingChainIntegrationTest` asserts the second `deepseek-request` carries the tool result and the second `deepseek-response` is the summary |
| FR-09 | `SYSTEM_PROMPT` in `fueling/FuelingAgent.kt`; agent test asserts the system message is present in the first prompt |
| FR-10 | `SELECT`-only repository + `StageReadOnlyGuardTest` (D-08) |
| FR-11 | `StageDbConfig` + `resolveStageDbPassword` (no default), empty `stageDb.password` in tracked config, `.env` git-ignored → `StageDbConfigTest` |
| FR-12 | per-call connection, 10 s timeouts, `DatabaseUnavailableException` → `StageDatabaseUnavailableException` → plain-language answer; nothing connects at startup → `FindFuelingToolTest`, `FuelingModulesTest`, F-4 |
| FR-13 | `DeepseekConfig.DEFAULT_MODEL` + `application.conf` (D-14) → `DeepseekConfigTest` addition |
| FR-14 | the three `TraceLog.fuelingLookup*` functions + the unchanged `stage=tool` line → `FuelingChainIntegrationTest` (order, one id, no secrets) |
| FR-15 | README section "Fueling order lookup" with the input/output contract and the Postman example (mirrors `01-requirements.md`) |
| FR-16 | no change to weather bindings, routes, DTOs or prompts; only the model default (D-14) → existing suite green |
| FR-17 | this document + `03-plan.md`; the implementation record follows the plan |

### NFR coverage

| NFR | How it is met |
|---|---|
| NFR-01 Observability | The 8-line chain of §Logging design: `inbound` → 2 × `deepseek-*` → `db` (lookup, tables, counts) → `tool` (args/result/is_error) → 2 × `deepseek-*` → `outbound`, one `req=` id; asserted by `FuelingChainIntegrationTest` |
| NFR-02 Security | Password only via `STAGE_DB_PASSWORD` (env/`.env`); `StageDbConfig` is never logged, the repository never logs, `TraceLog` receives only the failure message; the chain test seeds a password fixture and asserts 0 occurrences in all lines; `.env` stays in `.gitignore` |
| NFR-03 Data safety | `SELECT`-only repository (D-08), prepared statements, table names from an enum; `StageReadOnlyGuardTest` scans the stage sources for write keywords; the smoke checklist checks row counts before/after a manual lookup |
| NFR-04 Performance / resilience | One connection per lookup, at most 6 short queries, 10 s connect/socket/login + query timeout (D-10), no retries; success budget 5 s and the unreachable-DB budget 30 s are checked in the manual smoke run (timings between the `tool`/`db` lines and the request end) |
| NFR-05 Testability / offline | Every new test uses `FakeStageFuelingRepository`, a scripted `PromptExecutor` and `LogCapture`; no test resolves `StageDbConfig.from`, opens a socket or reads `.env`; the 123 existing tests are untouched (only the model default changes in production config, and no test asserted it) |
| NFR-06 Compatibility | No new dependency; stack unchanged; `fuelingModule` is additive; existing routes/DTOs/status codes untouched (`GET /` 200, `GET /time` 200/400/404, `POST /weather` 200/400/503, `GET /weather/history` 200/400) |
| NFR-07 Cost | `deepseek-flash` is the single default for the shared `LLModel` binding (all three LLM paths); no fallback model anywhere; `DEEPSEEK_MODEL` remains the only override |
| NFR-08 Robustness | Types cover malformed ids, not-found, unavailable/timeouts; render caps bound the oversized-jsonb/large-event cases; a blank `message` stays a 400; StatusPages keeps 503/500 as the last resort; nothing connects at startup, so `./gradlew run` works with the stage DB absent |
| NFR-09 Process / documentation | `01-requirements.md` → `02-design.md` (this file) → `03-plan.md`; README updated (API table, fueling section + Postman example, trace examples, config keys, test list, feature-docs link) so nothing contradicts the new behaviour |

### New tests (all offline)

| Test class | Coverage |
|---|---|
| `StageDbConfigTest` | defaults (host/port/name/user/timeout), `stageDb.*` values, `STAGE_DB_*` env precedence, `.env` password, blank password when nothing is configured, `jdbcUrl` |
| `FuelingIdTest` | canonical lower/upper accepted and lowercased, whitespace trimmed, rejects compact 32-hex, braces, `urn:uuid:`, empty, wrong group lengths, non-hex |
| `FuelingReportTest` | found/not-found rendering, all columns present, epoch → `… UTC`, out-of-range value → `<n> (raw)`, null → `-`, jsonb cap marker, related-row cap with real totals, multiple matches naming their tables |
| `FindFuelingToolTest` | with `FakeStageFuelingRepository`: success result + one `stage=db lookup=found …` line; archive/drop source naming; not found (no related queries, `lookup=not_found`); invalid id → `InvalidFuelingIdException`, repository not called, **no** `stage=db` line; DB down → `StageDatabaseUnavailableException`, `lookup=unavailable` line; descriptor name/description come from the shipped resource |
| `FuelingRoutesTest` | 200 + `message`/`answer` + inbound/outbound with one id + agent runs in `withContext(trace)`; blank `message` → 400 with no agent call; malformed body → 400 without an inbound body; blank key → 503 with no `stage=deepseek` lines |
| `KoogFuelingAgentTest` | scripted executor: tool call → text, one `stage=tool` line (`tool=find_fueling`, GUID argument, `is_error=false`); correlation id from the context; failing tool → `is_error=true`; non-convergence warning; the registry descriptors are always non-empty; the system prompt is present |
| `FuelingModulesTest` | `appModules(...) + fuelingModule(...)` composes exactly like production: graph resolves offline, `FuelingAgent` is a `KoogFuelingAgent`, the fueling spec is loaded once from the new resource, blank key → the unavailable lambda, `ToolRegistry` (weather, unnamed) still contains only `get_weather` |
| `FuelingChainIntegrationTest` | the full 8-line chain through the production-shaped graph with fakes at the edges (scripted executor + `LoggingPromptExecutor` + fake repository + fake LLM-free agent path): stage order, one `req=` id, `tools_count=1` with the `find_fueling` definition, `stage=db` counts, invalid-id flow without a `db` line, truncation bounded, no secrets (API-key and stage-password fixtures) |
| `StageReadOnlyGuardTest` | static scan of `src/main/kotlin` for the stage sources: no `INSERT`/`UPDATE`/`DELETE`/`DROP`/`ALTER`/`CREATE`/`TRUNCATE`, SQL constants use `?` placeholders and no string interpolation (FR-10, NFR-03) |

## Open questions and risks for the planner

| # | Question / risk | Default assumption taken here | Verification / fallback |
|---|---|---|---|
| R-1 | **Does `deepseek-flash` support tool calling through the Koog OpenAI client?** (ASM-12, the load-bearing assumption of the whole flow) | Yes | First smoke step after wiring: one `POST /fueling` must show a `tool_calls=[{"name":"find_fueling",…}]` line. If not, this is a blocker: report it (the only named alternative is keeping a more expensive model); the fallback needs no code change (`DEEPSEEK_MODEL=deepseek-chat`) |
| R-2 | **What exactly does Koog 1.2.0 put into the tool result when `execute` throws?** FR-04's answer quality depends on the model seeing a readable message | The exception message reaches the model (the repo's test proves `is_error=true` and that the loop continues) | Inspect the second `deepseek-request` of the manual run; if the text is opaque, keep the invalid-id throw (FR-04 requires the flag) but return the *unavailable* case as a normal string instead of throwing (one-line change in `FindFuelingTool`) |
| R-3 | **Are all stage `fueling_id` values lowercase canonical GUIDs?** An uppercase stored value would make the exact-match lookup report "not found" | Yes (verified examples are lowercase); `WHERE fueling_id = ?` with the lowercase canonical form (D-09) | Smoke SQL check `SELECT count(*) FROM fuelings WHERE fueling_id <> lower(fueling_id)` (also for archive/drop); fallback `WHERE lower(fueling_id) = ?` |
| R-4 | **Stage database name `fueling`** (ASM-10, unverified) | `fueling` (same as the user) | Confirm in the smoke run; `STAGE_DB_NAME` is the one-key override if it differs |
| R-5 | **Log order `db` → `tool`** while the requirements' prose lists `tool` → `db` | Keep the shipped order (identical to the verified weather chain); both lines are present with one id | If the requirements owner insists on the literal order, the `stage=tool` line would have to be emitted by the tool itself (it does not know `is_error`/`result` as the strategy renders them) — raise before implementing |
| R-6 | **Timeout value 10 s** (ASM-07 "proposed, confirm") | 10 s via `stageDb.timeoutSeconds` | The key makes it tunable without a code change; the smoke run measures the unreachable-DB response (< 30 s) |
| R-7 | **`.env` must gain `STAGE_DB_PASSWORD`** — a manual step the implementation cannot do (git-ignored secret) | Documented in the README; without it the tool degrades gracefully (FR-11/FR-12 as designed) | First manual run: a successful lookup proves it |
| R-8 | `vendor_transaction_date` / other numeric columns may not really be epoch millis | Range-check fallback prints the raw number with `(raw)` (D-06) | Manual lookup against a real row: every timestamp must be readable and plausible |
| R-9 | A question with more than 3 GUIDs exceeds `maxToolRounds` | 3 rounds (mirrors the weather agent); ASM-05 only requires "one call per GUID" | Non-convergence already logs a warning and the agent answers with what it has; raise if the operator needs more |
| R-10 | Related-row volumes for a single GUID are unknown (67K events overall; per-GUID density unverified) | All related rows are fetched, the first 50 per table are rendered, totals always stated (D-07) | Manual lookup of a busy order; if needed, add a SQL `LIMIT` + `count(*) OVER ()` later |
| R-11 | `Application.module`'s composed graph (both modules) is not exercised by any existing test | `FuelingModulesTest` composes `appModules(...) + fuelingModule(...)` exactly like production and asserts the graph resolves offline and the fueling spec loads once | If the planner wants route-level proof, add a composed-graph test with a blank key asserting 503 for `POST /fueling` (offline) |
| R-12 | FR-15 asks for the Postman example in the documentation; the README already carries a "Testing with Postman" section | Add a fueling subsection there (method, URL, headers, body, expected response, other cases) rather than a separate document | Reviewed against `01-requirements.md`'s table for verbatim consistency |
