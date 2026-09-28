# Koog-Everything and Logging — System Design

Stage: **design** (`/feature-design`, architect). Input: `docs/features/koog-everything-and-logging/01-requirements.md`.
Output of the next stage: `03-plan.md` (planner), which decomposes this document into tasks.

## Context and goals

Every DeepSeek call the service makes must travel through Koog, and a single `POST /weather`
from Postman must be readable in the console as a five-line chain: inbound request → DeepSeek
request → DeepSeek response → `get_weather` tool call → response to the client, all linked by one
correlation id and free of secrets. Concretely this means replacing the last raw Ktor DeepSeek
caller (`LlmTimeZoneResolver`) with a Koog `PromptExecutor` call, wrapping the shared Koog executor
in a logging decorator that is the single choke point for all LLM traffic, tracing the request
through the agent strategy for tool calls, making the `get_weather` description come from a JSON
resource file under `src/main/resources/`, and guaranteeing that every outbound DeepSeek request
carries a non-empty `tools` array. No endpoint, status code, response shape, database schema or
offline-test property changes; the Postman recipe in the requirements stays verbatim.

### Requirement traceability

| Requirement | Design element |
|---|---|
| FR-01 (all DeepSeek traffic via Koog) | §Components C4 `LoggingPromptExecutor`, C5 `LlmTimeZoneResolver` (rewrite), C9 DI wiring; §Decisions D1, D2; §Non-functional coverage: static guard test (`RawDeepSeekCallTest`) |
| FR-02 (never an empty `tools` array; tool-using flows send `get_weather`) | §Components C4 (logs `tools_count`), C5 (`toolChoice = None` + same non-empty descriptor list), C9 (both call sites receive `toolRegistry.tools.map { it.descriptor }`); §Key flows 1 and 2; §Decisions D3, D4 |
| FR-03 (every inbound request logged incl. body) | §Components C1 `RequestTracing`, C2 `TraceLog.inbound`; §Logging design stage `inbound`; §API design `beginTrace` |
| FR-04 (outbound DeepSeek request logged: model, messages, tools) | §Components C4, C6 `ToolJsonRenderer`, C2 `TraceLog.deepseekRequest`; §Logging design stage `deepseek-request` |
| FR-05 (DeepSeek response logged incl. tool calls and failures) | §Components C4 `TraceLog.deepseekResponse` / `.deepseekFailure`; §Key flows 3 |
| FR-06 (tool invocation logged: name, args, result, DB outcome) | §Components C6 (strategy loop), C7 `GetWeatherTool` (spec + DB log), C2 `TraceLog.tool` / `.toolDb`; §Logging design stages `tool`, `db` |
| FR-07 (every HTTP response logged with status and body) | §Components C1 `respondTraced`, C2 `TraceLog.outbound`; §API design `respondTraced`; §Non-functional coverage: StatusPages handlers |
| FR-08 (JSON file describing the tool under `src/main/resources/`) | §Data model: resource file `tools/get-weather-tool.json`; §Components C8 `ToolSpec`/`ToolSpecLoader` |
| FR-09 (tool description exposed to DeepSeek matches the file) | §Components C8 (spec drives `SimpleTool.name`/`description`), §Decisions D5; §Non-functional coverage: consistency test |
| FR-10 (Postman recipe) | §Key flows 5 (recipe unchanged, verbatim from requirements) |
| FR-11 (README updated, stale statements removed) | §Non-functional coverage: configuration and documentation changes |
| NFR-01 (five chain entries, in order, one correlation id, shipped config) | §Architecture overview: five-stage log chain; §Components C1, C2; §Key flows 1, 2 |
| NFR-02 (no secrets in logs) | §Components C2, C4; §Decisions D7; §Non-functional coverage: security |
| NFR-03 (bodies truncated to 4096 chars with marker) | §Components C2 `TraceLog.truncate`; §Logging design: truncation rule |
| NFR-04 (logging never changes API behavior) | §API design (error semantics), §Decisions D6, D9; §Non-functional coverage: robustness |
| NFR-05 (offline tests, 52 tests stay green, new coverage offline) | §Non-functional coverage: test strategy |
| NFR-06 (endpoints/status codes/shapes unchanged) | §API design: HTTP surface (unchanged), §Non-functional coverage: compatibility |
| NFR-07, NFR-08 (feature-design pipeline artifacts) | This document; `01-requirements.md` and `03-plan.md` are produced by their stages; §Decisions D14 |

## Architecture overview

Two LLM entry points, one Koog path, one decorator, five log stages.

```
             (a) inbound                                        (e) outbound
Postman ──────────────────► Ktor routing ─────────────────────────────────────► Postman
                              │  plugins/RequestTracing.kt
                              │  + plugins/Routing.kt StatusPages
                              │
             POST /weather    │                       GET /time?location=…
                              ▼                              ▼
                     WeatherAgent (Koog AIAgent)    TimeZoneResolver chain
                     weather/KoogWeatherAgent.kt     Direct → Builtin → Caching
                              │                              │
                              │ strategy loop                ▼
                              │  requestLLM / executeTools   time/LlmTimeZoneResolver.kt
                              │  (d) stage=tool              (Koog PromptExecutor call)
                              │                              │
                              └──────────────┬───────────────┘
                                             ▼
                            (b),(c)  log/LoggingPromptExecutor.kt   <-- PromptExecutor decorator
                                             ▼                        (single choke point)
                                   MultiLLMPromptExecutor → OpenAILLMClient (Koog)
                                             ▼
                                       DeepSeek API  /chat/completions
```

Five-stage log chain and where each line is emitted:

| Stage | Marker | Emitted by | File |
|---|---|---|---|
| (a) inbound request | `stage=inbound` | route handlers via `ApplicationCall.beginTrace(...)` | `plugins/RequestTracing.kt` |
| (b) outbound DeepSeek request | `stage=deepseek-request` | decorator, before delegating | `log/LoggingPromptExecutor.kt` |
| (c) DeepSeek response | `stage=deepseek-response` | decorator, after the delegate returns / throws | `log/LoggingPromptExecutor.kt` |
| (d) tool call | `stage=tool` | agent strategy loop (`KoogWeatherAgent`), after `executeTools` | `weather/KoogWeatherAgent.kt` |
| (e) outbound response | `stage=outbound` | `ApplicationCall.respondTraced(...)`, including StatusPages handlers | `plugins/RequestTracing.kt` |

Tool-internal detail on the same chain: `stage=db` (insert succeeded or not), `log/TraceLog.kt` called from `weather/GetWeatherTool.kt`.

Correlation id propagation: `beginTrace` creates `CallTrace(id)` (a `CoroutineContext.Element` and a
`call.attributes` entry). Handlers that reach an LLM wrap the call in
`withContext(trace) { … }`; everything downstream (agent strategy, executor decorator, tool) reads
`coroutineContext[CallTrace]` and prints `req=<id>`. This was verified against Koog 1.2.0: the
agent session runs the strategy in the **caller's** coroutine context
(`AIAgentRunSessionImpl.run` creates no new scope or dispatcher), so a custom context element
survives from the route into `requestLLM` and `executeTools`.

File inventory (all paths relative to the repository root):

| Action | Path |
|---|---|
| new | `src/main/kotlin/com/aiturbo/log/CallTrace.kt` |
| new | `src/main/kotlin/com/aiturbo/log/TraceLog.kt` |
| new | `src/main/kotlin/com/aiturbo/log/TraceFormats.kt` |
| new | `src/main/kotlin/com/aiturbo/log/LoggingPromptExecutor.kt` |
| new | `src/main/kotlin/com/aiturbo/plugins/RequestTracing.kt` |
| new | `src/main/kotlin/com/aiturbo/tools/ToolSpec.kt` |
| new | `src/main/kotlin/com/aiturbo/tools/ToolJsonRenderer.kt` |
| new | `src/main/resources/tools/get-weather-tool.json` |
| modify | `src/main/kotlin/com/aiturbo/time/LlmTimeZoneResolver.kt` (rewrite: Koog instead of raw HTTP) |
| modify | `src/main/kotlin/com/aiturbo/weather/KoogWeatherAgent.kt` (strategy logging) |
| modify | `src/main/kotlin/com/aiturbo/weather/GetWeatherTool.kt` (spec-driven, DB outcome logging) |
| modify | `src/main/kotlin/com/aiturbo/plugins/Routing.kt` (drop `CallLogging`, traced responses) |
| modify | `src/main/kotlin/com/aiturbo/plugins/WeatherRouting.kt` (traced responses) |
| modify | `src/main/kotlin/com/aiturbo/Application.kt` (Koin wiring) |
| modify | `src/main/resources/logback.xml` (explicit trace logger) |
| modify | `src/main/resources/application.conf` (comment only) |
| modify | `README.md` (FR-11) |
| new/modify | tests listed in §Non-functional coverage |

No new Gradle dependency is required. `ktor-server-call-logging` becomes unused (its installation is
removed); the dependency may stay or be dropped.

## Components

### C1. Request tracing on the Ktor side — `plugins/RequestTracing.kt` (new)

Responsibility: create the correlation id, emit stage (a) and stage (e), and expose the trace to the
StatusPages handlers. No dependency on Koog or on the DI graph, so route tests keep working
unchanged.

```kotlin
private val TraceAttribute = AttributeKey<CallTrace>("aiturbo.trace")

/** Idempotent: returns the existing trace if the call was already traced. */
fun ApplicationCall.beginTrace(body: String? = null): CallTrace

/** Trace of the current call, or null when the handler never called beginTrace. */
fun ApplicationCall.traceOrNull(): CallTrace?

/** Logs stage=outbound (status + JSON body) and then responds. */
suspend inline fun <reified T : Any> ApplicationCall.respondTraced(
    body: T,
    status: HttpStatusCode = HttpStatusCode.OK,
)
```

- `beginTrace` generates `CallTrace(newTraceId())`, stores it in `call.attributes`, logs
  `stage=inbound` with `method`, `path`, `query`, `client` (`call.request.origin.remoteHost` +
  `remotePort`) and the optional body, and is idempotent.
- `respondTraced` logs `stage=outbound` with `status` and the body serialized by a compact
  `Json { encodeDefaults = true; explicitNulls = false }`, then delegates to `call.respond(status, body)`.
  It never swallows or rewrites the body; serialization for logging is wrapped in `runCatching` so a
  logging failure cannot turn a 200 into a 500 (NFR-04).
- Every `call.respond` in `Routing.kt` and `WeatherRouting.kt` is replaced by `respondTraced`,
  including the four StatusPages handlers. In the `ContentTransformationException` /
  `BadRequestException` handlers a missing trace is created first (`call.beginTrace()`), so a
  malformed body still yields one `stage=inbound` line without a body plus one `stage=outbound` 400
  line.
- Handlers that reach an LLM are wrapped: `withContext(trace) { agent.answer(message) }` and
  `withContext(trace) { resolver.resolve(location) }`.

Alternatives considered are recorded in §Decisions D9 (Ktor `CallLogging` and `onCallRespond`
plugin hooks were rejected) and D10 (DoubleReceive was rejected).

### C2. Trace logger — `log/TraceLog.kt` and `log/CallTrace.kt` (new)

```kotlin
class CallTrace(val id: String) : AbstractCoroutineContextElement(CallTrace) {
    companion object Key : CoroutineContext.Key<CallTrace>
    override fun toString(): String = id
}

fun newTraceId(): String = UUID.randomUUID().toString().take(8)

object TraceLog {
    const val LOGGER_NAME = "com.aiturbo.trace"
    const val MAX_BODY_CHARS = 4096
    val logger: Logger = LoggerFactory.getLogger(LOGGER_NAME)

    suspend fun currentId(): String? = coroutineContext[CallTrace]?.id

    fun inbound(id: String, method: String, path: String, query: String?, client: String?, body: String?)
    fun deepseekRequest(id: String?, endpoint: String, model: String, messages: List<Message>,
                        toolsCount: Int, tools: String, toolChoice: String?)
    fun deepseekResponse(id: String?, model: String, response: Message.Assistant)
    fun deepseekFailure(id: String?, model: String, error: Throwable)
    fun tool(id: String?, name: String, args: String, result: String, isError: Boolean)
    fun toolDb(id: String?, tool: String, saved: Boolean, recordId: Int?, reason: String?)
    fun outbound(id: String, status: Int, body: String)

    internal fun truncate(text: String, max: Int = MAX_BODY_CHARS): String
    internal fun describeError(t: Throwable): String
}
```

- One logger name for the whole chain (`com.aiturbo.trace`) so the five entries are visually uniform
  and filterable; class loggers stay for component-internal warnings.
- Missing id renders as `req=-` (never `null`).
- `truncate` appends an explicit marker: `…[truncated, NNNN chars total]`.
- `describeError` walks the cause chain (`SimpleName: message`) and, for
  `ai.koog.http.client.KoogHttpClientException`, appends `status=<statusCode> body=<errorBody>`
  (truncated) — that is where Koog puts the HTTP status and the error payload. If that type is not
  public in the shipped Koog artifact, the fallback is the cause-chain walk only (see Risks R5).

### C3. Traffic formatters — `log/TraceFormats.kt` (new)

Pure, unit-testable functions (no logging side effects), all returning single-line strings and all
truncated by the caller:

```kotlin
internal fun renderMessages(messages: List<Message>): String      // [system: "...", user: "..."]
internal fun renderAssistant(response: Message.Assistant): String // text="..." tool_calls=[{"name":…,"args":…}]
internal fun renderJson(obj: JsonObject): String                  // compact JSON, one line
```

`Message.Assistant.parts` is a `List<MessagePart.ResponsePart>`; text comes from
`MessagePart.Text.text`, tool calls from `MessagePart.Tool.Call` (`id`, `tool`, `args` — `args` is
already a JSON string in Koog 1.2.0).

### C4. Logging executor decorator — `log/LoggingPromptExecutor.kt` (new)

The single choke point for every DeepSeek call in the application (FR-01, FR-04, FR-05).

```kotlin
class LoggingPromptExecutor(
    private val delegate: PromptExecutor,
    private val endpoint: String,                  // "<baseUrl>/chat/completions", not a secret
    private val toolJsonRenderer: ToolJsonRenderer,
) : PromptExecutor() {

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame>
    override fun close() { delegate.close() }
}
```

Behaviour of `execute`:

1. `val id = TraceLog.currentId()`.
2. Log `stage=deepseek-request` with `endpoint`, `model.id`, `renderMessages(prompt.messages)`,
   `tools_count=<tools.size>`, `tools=<renderTools(tools)>`, `tool_choice=<prompt.params.toolChoice ?: ->`.
   When `tools` is empty, the same line carries `tools_count=0` and a `WARN`-level duplicate is
   emitted so an accidental empty-tools call is impossible to miss (FR-02). The decorator does not
   throw on an empty list — refusing would change behaviour (§Decisions D3).
3. Call `delegate.execute(prompt, model, tools)` **unchanged** (the decorator never filters,
   reorders or adds tools).
4. On success log `stage=deepseek-response` with `model.id` and
   `renderAssistant(response)` (`text=…`, `tool_calls=[…]`).
5. On `CancellationException`: rethrow without logging. On any other exception: log
   `stage=deepseek-response … error=<describeError(e)>` and rethrow — graceful degradation stays in
   the callers (resolver → 404, agent → 503).

`executeStreaming` logs `stage=deepseek-request` (with a `streaming=true` field) and then delegates
the flow untouched; no frame-level logging is added because nothing in this application streams.
`close()` delegates to the wrapped executor; the decorator is never closed by Koin.

Rejected alternatives (agent event-handler feature, logging inside each call site): §Decisions D1.

### C5. `LlmTimeZoneResolver` rewritten on Koog — `time/LlmTimeZoneResolver.kt` (modify)

Keeps the class name and the `TimeZoneResolver` interface (`suspend fun resolve(location: String): ZoneId?`),
so `CompositeTimeZoneResolver` and `CachingTimeZoneResolver` keep working unchanged.

```kotlin
class LlmTimeZoneResolver(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolDescriptorsProvider: () -> List<ToolDescriptor>,
    private val apiKeyConfigured: Boolean = true,
) : TimeZoneResolver {

    override suspend fun resolve(location: String): ZoneId?
}

internal fun parseZoneContent(content: String?): ZoneId?   // moved out of the class, still unit-tested
```

`resolve` semantics (unchanged from today, only the transport changes):

1. `!apiKeyConfigured` → warn, return `null` **without any call** (preserves
   `GET /time` → 404 when `DEEPSEEK_API_KEY` is blank).
2. Build the prompt:
   `Prompt.build(id = "time-zone-resolution", params = LLMParams(temperature = 0.0, maxTokens = 64, toolChoice = LLMParams.ToolChoice.None)) { system(SYSTEM_PROMPT); user("Location: $location") }`.
   `SYSTEM_PROMPT` is the existing instruction (return only `{"timezone": "<IANA id>"}`, `null` when
   unknown); the wording stays, since it is now the only structured-output mechanism.
3. `promptExecutor.execute(prompt, model, toolDescriptorsProvider())` — the **same non-empty
   descriptor list** the weather agent uses (§Decisions D4).
4. Concatenate `MessagePart.Text` parts, then `parseZoneContent` (strip code fences, decode
   `ZoneGuess`, `ZoneId.of`, any failure → `null`). Tool calls in the answer are not executed; they
   remain visible in the `stage=deepseek-response` line and lead to `null` → 404.
5. `CancellationException` rethrown; any other exception → warn (existing message) and `null`.

The raw-HTTP artifacts (`ChatMessage`, `ChatCompletionRequest`, `ChatCompletionResponse`, `Choice`,
`ResponseFormat`, the `HttpClient`/`Authorization` usage) are deleted. `DeepseekConfig` keeps being
the source of `baseUrl`/`apiKey`/`model` for the wiring in C9.

### C6. Weather agent strategy logging — `weather/KoogWeatherAgent.kt` (modify)

The strategy loop keeps its shape (up to `maxToolRounds` rounds, `executeTools`, `sendToolResults`,
non-convergence warning) and adds exactly one `stage=tool` line per tool invocation, after the tool
has run, so a single invocation produces a single chain entry (FR-06, NFR-01):

```kotlin
val traceId = TraceLog.currentId()
var response = requestLLM(input)
while (rounds < maxToolRounds) {
    val calls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
    if (calls.isEmpty()) break
    val results = executeTools(calls)
    calls.zip(results).forEach { (call, result) ->
        TraceLog.tool(traceId, call.tool, call.args, result.output, result.toMessagePart().isError)
    }
    response = sendToolResults(results)
    rounds++
}
```

`ReceivedToolResult` (`ai.koog.agents.core.environment`) provides `tool`, `toolArgs`, `output`,
`resultKind` and `toMessagePart()`; `isError` is derived through `toMessagePart()` to avoid
depending on `ToolResultKind` internals. Logging stays inside `runCatching`-free straight-line code
but must never throw (`traceId` may be `null` in unit tests — allowed, renders `req=-`).

The agent receives the tool descriptors from the registry, which Koog passes to the executor
(`tools = toolRegistry.tools.map { it.descriptor }` in `FunctionalAIAgent.prepareContext`), so the
`stage=deepseek-request` line for the weather flow always shows a non-empty `tools` array.

### C7. `GetWeatherTool` — spec-driven description and DB outcome — `weather/GetWeatherTool.kt` (modify)

```kotlin
class GetWeatherTool(
    private val resolver: TimeZoneResolver,
    private val weatherClient: WeatherClient,
    private val timeService: TimeService,
    private val repository: WeatherRecordRepository,
    spec: ToolSpec,                                  // NEW: name + description from the JSON resource
) : SimpleTool<GetWeatherArgs>(
    argsType = typeToken<GetWeatherArgs>(),
    name = spec.name,
    description = spec.description,
)
```

- The parameter schema still comes from Koog (`GetWeatherArgs` with its `@LLMDescription`); only the
  tool *name* and *description* come from the resource file (FR-09). A consistency test keeps the
  resource's `parameters` block honest (see NFR-05).
- The returned string (what the model sees, and therefore the final answer) is **not** changed —
  no "запись сохранена" suffix is added, because prompts and answer format are explicitly out of
  scope. The database outcome is logged instead:

```kotlin
val outcome = runCatching { withContext(Dispatchers.IO) { repository.save(timeService.nowIn(zone).toLocalDateTime()) } }
outcome.onSuccess { id ->
    if (id >= 0) TraceLog.toolDb(TraceLog.currentId(), name, saved = true, recordId = id, reason = null)
    else TraceLog.toolDb(TraceLog.currentId(), name, saved = false, recordId = null, reason = "unique conflict on 'data'")
}.onFailure { TraceLog.toolDb(TraceLog.currentId(), name, saved = false, recordId = null, reason = it.message) }
```

  This is the only addition to the tool and it satisfies "log … including whether the database insert
  succeeded" (FR-06) on both the success and the failure path, without touching behaviour
  (NFR-04, NFR-06).

### C8. Tool specification resource + loader — `tools/ToolSpec.kt` (new)

```kotlin
@Serializable
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

object ToolSpecLoader {
    const val RESOURCE_PATH = "tools/get-weather-tool.json"
    fun load(resourcePath: String = RESOURCE_PATH, classLoader: ClassLoader = ToolSpec::class.java.classLoader): ToolSpec
}
```

`load` reads the classpath resource, decodes it, and throws `IllegalStateException` with the resource
path and the cause when the file is missing or invalid (fail fast at startup, §Decisions D5). It also
validates the non-empty `name`/`description`. The decoded spec is bound in Koin with
`createdAtStart = true` and passed to `GetWeatherTool`; the loader logs one INFO line on its own class
logger (`Tool spec loaded: name=get_weather …`). Tests provide a fake by calling
`ToolSpecLoader.load(testResourcePath)` or by constructing a `ToolSpec` directly — no DI needed.

### C9. Wire-format tool renderer — `tools/ToolJsonRenderer.kt` (new)

Turns the same descriptors Koog is about to send into the OpenAI wire shape, so the log shows
exactly what leaves the process (FR-02, FR-04):

```kotlin
class ToolJsonRenderer(
    private val schemaGenerator: OpenAICompatibleToolDescriptorSchemaGenerator =
        OpenAICompatibleToolDescriptorSchemaGenerator(),
) {
    fun toWireJson(tool: ToolDescriptor): JsonObject
    fun renderAll(tools: List<ToolDescriptor>): String   // "[]" when empty, else the array as one line
}
```

`toWireJson` mirrors `AbstractOpenAILLMClient.toOpenAIChatTool()`:
`{"type":"function","function":{"name": descriptor.name,"description": descriptor.description,
"parameters": schemaGenerator.generate(descriptor)}}` — the parameters block is generated by Koog's
own public `OpenAICompatibleToolDescriptorSchemaGenerator` (`ai.koog.prompt.executor.clients.openai.base`),
so the rendered schema cannot drift from the request Koog builds. The class is stateless and
unit-tested directly (`ToolJsonRendererTest`), and injected into the decorator so tests can
substitute a fake.

### C10. Dependency-injection wiring — `Application.kt` (modify)

```kotlin
fun appModules(deepseek: DeepseekConfig, db: DbConfig, weather: WeatherConfig): Module = module {
    single { Clock.systemUTC() }
    single { HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } } }

    // Tool description resource — loaded once at startup, fail fast if missing/invalid
    single(createdAtStart = true) { ToolSpecLoader.load() }
    single { ToolJsonRenderer() }

    single<WeatherClient> { OpenMeteoWeatherClient(get(), weather) }
    single<WeatherRecordRepository> { JdbcWeatherRecordRepository(db) }
    single { GetWeatherTool(get(), get(), get(), get(), get()) }        // + spec
    single { ToolRegistry.builder().tool(get<GetWeatherTool>()).build() }
    single { deepseekModel(deepseek.model) }

    // Every DeepSeek call goes through this decorator
    single<PromptExecutor> {
        LoggingPromptExecutor(
            delegate = deepseekPromptExecutor(deepseek),
            endpoint = "${deepseek.baseUrl.trimEnd('/')}/chat/completions",
            toolJsonRenderer = get(),
        )
    }

    single<TimeZoneResolver> {
        CompositeTimeZoneResolver(
            listOf(
                DirectZoneResolver(),
                BuiltinTimeZoneResolver(),
                CachingTimeZoneResolver(
                    LlmTimeZoneResolver(
                        promptExecutor = get(),
                        model = get(),
                        toolDescriptorsProvider = { get<ToolRegistry>().tools.map { it.descriptor } },
                        apiKeyConfigured = deepseek.apiKey.isNotBlank(),
                    )
                ),
            )
        )
    }
    singleOf(::TimeService)
    single<WeatherAgent> {
        if (deepseek.apiKey.isBlank()) {
            WeatherAgent { throw WeatherUnavailableException("DeepSeek API key is not configured") }
        } else {
            KoogWeatherAgent(get(), get(), get())
        }
    }
}
```

Two points matter here:

- **Cycle break.** `GetWeatherTool` needs `TimeZoneResolver`, and the time-zone resolver now needs
  the tool descriptors. Injecting `ToolRegistry` eagerly would create a Koin cycle; the lazy
  `toolDescriptorsProvider: () -> List<ToolDescriptor>` resolves the registry on first use
  (Koin's `single` definition lambda runs in a `Scope`, so `get()` inside the nested lambda resolves
  at call time). The resolver caches the list with `by lazy` — §Decisions D11.
- **Non-empty tools on both paths.** The weather agent gets them from its registry; the resolver
  gets the same list from the provider. Both therefore satisfy "never `tools: []`" (§Decisions D3, D4).

### C11. Cross-cutting: logging design (loggers, formats, samples)

**Logger names.** One chain logger shared by all five stages:

| Logger | Used for |
|---|---|
| `com.aiturbo.trace` (`TraceLog`) | `stage=inbound`, `deepseek-request`, `deepseek-response`, `tool`, `db`, `outbound` |
| class loggers (unchanged) | component warnings: `LlmTimeZoneResolver` (key missing, call failed), `KoogWeatherAgent` (tool loop did not converge), `OpenWeatherClient`, `JdbcWeatherRecordRepository`, `Routing.kt` StatusPages warnings, `ToolSpecLoader` startup line |

**Line format.** Existing logback pattern, unchanged:
`%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`, and every chain line is
`req=<id> stage=<marker> <key=value …>`. `%thread` shows the executing thread; the correlation id
travels in the message instead of MDC because MDC is thread-local and does not follow coroutine
dispatches (the agent and the executor hop dispatchers).

**Truncation.** Every body-like field (inbound body, rendered messages, rendered tools, response
text, tool args, tool result, outbound body) goes through `TraceLog.truncate(…)`: at most 4096
characters plus an explicit marker `…[truncated, NNNN chars total]` (NFR-03).

**Stage samples** (format is literal; values illustrative):

```
2026-09-16 10:15:22.481 [eventLoopGroupProxy-4-2] INFO  com.aiturbo.trace - req=8f2c1ad4 stage=inbound method=POST path=/weather query=- client=127.0.0.1:54321 body={"message":"Какая сейчас погода в Москве?"}
2026-09-16 10:15:22.905 [eventLoopGroupProxy-4-2] INFO  com.aiturbo.trace - req=8f2c1ad4 stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-chat tool_choice=- tools_count=1 tools=[{"type":"function","function":{"name":"get_weather","description":"Возвращает текущую погоду и местное время для указанного города или региона","parameters":{"type":"object","properties":{"location":{"type":"string","description":"Город или регион, например 'Москва' или 'Berlin'"}},"required":["location"]}}}] messages=[system: "Ты — ассистент по погоде…", user: "Какая сейчас погода в Москве?"]
2026-09-16 10:15:24.117 [eventLoopGroupProxy-4-2] INFO  com.aiturbo.trace - req=8f2c1ad4 stage=deepseek-response model=deepseek-chat text="" tool_calls=[{"name":"get_weather","args":"{\"location\":\"Москва\"}"}]
2026-09-16 10:15:25.331 [eventLoopGroupProxy-4-2] INFO  com.aiturbo.trace - req=8f2c1ad4 stage=tool tool=get_weather args={"location":"Москва"} is_error=false result="Москва, Россия: +15.4°C, облачно, влажность 66%, часовой пояс Europe/Moscow, местное время 2026-09-16 13:15:25 (Wednesday)"
2026-09-16 10:15:25.334 [eventLoopGroupProxy-4-2] INFO  com.aiturbo.trace - req=8f2c1ad4 stage=db tool=get_weather saved=true id=7
2026-09-16 10:15:26.002 [eventLoopGroupProxy-4-2] INFO  com.aiturbo.trace - req=8f2c1ad4 stage=outbound status=200 body={"message":"Какая сейчас погода в Москве?","answer":"Сейчас в Москве +15.4°C…"}
```

Failure variants: `stage=deepseek-response … error=LLMClientException: 401 status=401 body={"error":…}`
(FR-05), `stage=db tool=get_weather saved=false reason=Connection refused` while `stage=tool` and
`stage=outbound 200` still appear (DB-down behaviour preserved, FR-06).

No secret ever reaches these lines: the decorator receives only the endpoint label, model id, prompt,
descriptors and response — never `DeepseekConfig`, headers or the API key (§Decisions D7).

## API design

### HTTP surface — unchanged

| Method | Path | Success | Errors | Response shape |
|---|---|---|---|---|
| `GET` | `/` | 200 | — | `ServiceInfoResponse{service, usage}` |
| `GET` | `/time?location=` | 200 | 400 (missing), 404 (unresolvable) | `TimeResponse` |
| `POST` | `/weather` | 200 | 400 (blank/malformed body), 503 (no key / agent failure) | `WeatherResponse{message, answer}` |
| `GET` | `/weather/history?limit=` | 200 | 400 (limit not 1..100) | `WeatherHistoryRecords{records[]}` |
| — | any | — | 500 (unhandled) | `ErrorResponse{error}` |

No endpoint, status code or JSON shape changes (NFR-06). No new headers are added; the correlation id
stays inside the logs.

### Internal interfaces (new or changed)

| Signature | File | Notes |
|---|---|---|
| `suspend fun TimeZoneResolver.resolve(location: String): ZoneId?` | `time/TimeZoneResolver.kt` | unchanged |
| `LlmTimeZoneResolver(promptExecutor, model, toolDescriptorsProvider, apiKeyConfigured = true)` | `time/LlmTimeZoneResolver.kt` | replaces `(HttpClient, DeepseekConfig)` |
| `internal fun parseZoneContent(content: String?): ZoneId?` | `time/LlmTimeZoneResolver.kt` | extracted, unchanged logic |
| `LoggingPromptExecutor(delegate, endpoint, toolJsonRenderer) : PromptExecutor()` | `log/LoggingPromptExecutor.kt` | overrides `execute`, `executeStreaming`, `close` |
| `TraceLog.inbound/deepseekRequest/deepseekResponse/deepseekFailure/tool/toolDb/outbound/currentId/truncate/describeError` | `log/TraceLog.kt` | §Components C2 |
| `ApplicationCall.beginTrace(body: String? = null): CallTrace`, `ApplicationCall.traceOrNull()`, `suspend inline fun <reified T : Any> ApplicationCall.respondTraced(body: T, status = OK)` | `plugins/RequestTracing.kt` | idempotent |
| `ToolSpecLoader.load(resourcePath = RESOURCE_PATH, classLoader = …): ToolSpec` | `tools/ToolSpec.kt` | throws `IllegalStateException` |
| `ToolJsonRenderer.toWireJson(tool): JsonObject`, `ToolJsonRenderer.renderAll(tools): String` | `tools/ToolJsonRenderer.kt` | stateless |
| `GetWeatherTool(resolver, weatherClient, timeService, repository, spec)` | `weather/GetWeatherTool.kt` | adds `spec` |
| `WeatherAgent.answer(message: String): String` | `weather/WeatherAgent.kt` | unchanged |

Validation and error semantics are unchanged: `POST /weather` blank message → 400 before any LLM
call; `GET /time` blank location → 400 before any resolver call; blank API key → 404 for `/time` and
503 for `/weather` with no outbound traffic.

## Data model

No database change: the `users` table (`id`, `data`, `time`, `created_at`) and
`WeatherRecordRepository` stay exactly as they are; no migration.

### Resource file — `src/main/resources/tools/get-weather-tool.json`

```json
{
  "name": "get_weather",
  "description": "Возвращает текущую погоду и местное время для указанного города или региона",
  "parameters": {
    "type": "object",
    "properties": {
      "location": {
        "type": "string",
        "description": "Город или регион, например 'Москва' или 'Berlin'"
      }
    },
    "required": ["location"]
  }
}
```

| Field | Type | Role |
|---|---|---|
| `name` | string, non-blank | passed to `SimpleTool.name` → the function name on the wire and the name the model sees |
| `description` | string, non-blank | passed to `SimpleTool.description` → the description on the wire (FR-09) |
| `parameters` | JSON Schema object | documentation of the generated schema; kept honest by a consistency test |

The file is the **runtime source of truth** for the tool's name and description (§Decisions D5):
it is loaded by `ToolSpecLoader` at startup and fed into `GetWeatherTool`, so editing only the file
(plus restart) changes what DeepSeek receives. The `parameters` block cannot be authoritative without
bypassing Koog's schema generation from the args class, so it is treated as mirrored documentation and
guarded by a test that compares it with the descriptor Koog generates; `@LLMDescription` on
`GetWeatherArgs.location` stays the schema's source.

### In-memory structures

| Structure | Shape | Lifetime |
|---|---|---|
| `CallTrace` | `CoroutineContext.Element` + `call.attributes` entry holding `id: String` (8 hex chars) | one HTTP request |
| `ToolSpec` | `name: String`, `description: String`, `parameters: JsonObject` | singleton, decoded once |
| Log record | one line: `req=<id> stage=<marker> <k=v…>`, bodies truncated to 4096 chars | console only, no persistence |

## Key flows

### Flow 1 — `POST /weather` from Postman (all five stages)

1. Postman sends `{"message":"Какая сейчас погода в Москве?"}`. Ktor routes to
   `weatherRoutes()`; the handler runs `call.beginTrace()` **after** `call.receive<WeatherRequest>()`
   and logs (a) with the re-serialized body, storing `CallTrace(id)` in the call attributes.
2. Blank message → `respondTraced(ErrorResponse, 400)`: (a) and (e) only, no LLM call.
3. `withContext(trace) { agent.answer(message) }` → `KoogWeatherAgent` strategy →
   `requestLLM(input)` → `LoggingPromptExecutor.execute` logs (b) with
   `endpoint`, `model=deepseek-chat`, `messages=[system…, user…]`, `tools_count=1` and the full
   `get_weather` JSON (description taken from the resource file) → `MultiLLMPromptExecutor` →
   DeepSeek.
4. DeepSeek answers with a tool call. The decorator logs (c) `tool_calls=[{"name":"get_weather",…}]`.
5. The strategy calls `executeTools`; `GetWeatherTool` resolves the zone, fetches Open-Meteo,
   inserts the `users` row (logging `stage=db`), returns the summary. The strategy logs one
   `stage=tool` line with args, result and `is_error=false`.
6. `sendToolResults` → second LLM round → the decorator logs (b) and (c) again (the chain then has
   two DeepSeek round-trips, which is the normal agent loop), and the strategy returns the final text.
7. `respondTraced(WeatherResponse(message, answer), 200)` logs (e) with status and body; the row is
   visible in `GET /weather/history`.

Edge cases: message blank → 400 (stages a/e only). Body malformed → `ContentTransformationException`
→ StatusPages creates the missing trace, logs (a) without body and (e) 400. API key blank →
`WeatherAgent` throws `WeatherUnavailableException` before any Koog call → 503 with (a) and (e).
DeepSeek error/timeout → decorator logs (c) with `error=…` and rethrows; StatusPages returns 503;
(a) and (e) still present. Database down → `stage=db saved=false`, answer still 200 (existing
behaviour). Tool loop does not converge after `maxToolRounds` → existing warn line, (e) still 200.

### Flow 2 — `GET /time?location=Kisumu` after the migration

1. `beginTrace()` (a) → `withContext(trace) { resolver.resolve("Kisumu") }`.
2. `DirectZoneResolver` (no slash) and `BuiltinTimeZoneResolver` (not in the map) return null;
   `CachingTimeZoneResolver` delegates once.
3. `LlmTimeZoneResolver`: key configured → build the prompt (system instruction, user
   `Location: Kisumu`, `temperature=0.0`, `maxTokens=64`, `toolChoice=ToolChoice.None`) →
   `promptExecutor.execute(prompt, model, toolDescriptorsProvider())`.
4. The decorator logs (b) with `tools_count=1 tools=[…get_weather…] tool_choice=none`, so the wire
   request carries a non-empty `tools` array and is visible in the log (FR-02, FR-04).
5. DeepSeek answers with text; the decorator logs (c); the resolver concatenates text parts and
   parses `{"timezone":"Africa/Nairobi"}` → `ZoneId`; the answer is cached by
   `CachingTimeZoneResolver` (including the null case, unchanged).
6. `respondTraced(TimeResponse, 200)` logs (e).

Edge cases: unknown location → model returns `{"timezone":null}` → 404 (stages a/b/c/e, no tool call);
DeepSeek HTTP error or timeout → decorator logs (c) with the error, resolver returns null → 404;
blank key → no outbound call at all (warn on the class logger) → 404; model unexpectedly answers with
a tool call despite `toolChoice=none` → the call is logged in (c), never executed, text is empty →
null → 404. `/time` requests for built-in cities or IANA ids never reach the LLM (unchanged).

### Flow 3 — DeepSeek failure on the weather path

Decorator catches, logs `stage=deepseek-response … error=LLMClientException: … status=401 body=…`,
rethrows; `KoogWeatherAgent` propagates; StatusPages maps `WeatherUnavailableException`/
`Throwable` to 503 and logs (e) with status and `ErrorResponse` body. Exactly one (b) and one (c)
line per round-trip, and the chain stays greppable by `req=`.

### Flow 4 — startup

Koin creates `ToolSpecLoader.load()` eagerly (`createdAtStart = true`): the resource is read from the
classpath, validated, and logged (`Tool spec loaded: name=get_weather description="…"`). A missing or
invalid file aborts startup with an `IllegalStateException` naming the resource path — a packaging
error surfaces immediately instead of at the first request.

### Flow 5 — Postman manual verification (unchanged)

The recipe in `01-requirements.md` ("Postman examples") is used verbatim: `POST http://localhost:8080/weather`
with `Content-Type: application/json` and body `{"message": "Какая сейчас погода в Москве?"}`; expect
`200` with `{message, answer}`, one new `users` row (checked with the `docker exec … psql` command or
`GET http://localhost:8080/weather/history?limit=5`), and the five-stage chain in the console as in
Flow 1. Request 3 (`GET /time?location=Moscow`) stays a built-in-map resolution with (a)/(e) lines
only. The README (FR-11) mirrors this recipe, describes the Koog-only DeepSeek path and the log chain,
and drops the stale "DeepSeek LLM over HTTP" wording and the old "over HTTP" comment in
`application.conf`.

## Decisions and alternatives

| # | Decision | Alternatives considered | Rationale |
|---|---|---|---|
| D1 | One `PromptExecutor` decorator (`LoggingPromptExecutor`) wrapped around the shared executor; both call sites get the decorated bean | (a) Koog `agents-features-event-handler` / trace feature; (b) logging inside each call site; (c) a second Koog agent for time-zone resolution | (a) only covers the agent path and pulls in a feature module; (b) duplicates logic and would diverge; (c) adds an agent, a strategy and a tool loop for a single JSON answer. One decorator is the minimum code that guarantees "every Koog LLM call is logged" and stays testable with a fake delegate |
| D2 | Time-zone resolution uses `PromptExecutor.execute` with a fixed two-message prompt | Keep raw Ktor HTTP (rejected by FR-01); second agent (see D1c) | Simplest mechanism that preserves the current semantics (one call, JSON-ish answer) and reuses the same executor factory, model and logging |
| D3 | Empty `tools` list is never passed: both call sites pass the shared non-empty descriptor list; the decorator warns (does not throw) if it ever sees an empty list | (a) throw on empty tools; (b) `require` at startup | Koog omits the `tools` field entirely when the list is empty (`tools.takeIf { it.isNotEmpty() }`), so the wire never shows `"tools": []`; throwing in the decorator could turn a working call into a 500/503 and violate NFR-04. The log line plus `tools_count` makes the invariant observable, and DI guarantees the inputs |
| D4 | `/time` requests send the same non-empty `tools` array, with `toolChoice = None` | ASM-04 default (no `tools` field on `/time`) | The user's hard instruction wins over the analyst's default: no backend→DeepSeek request may lack tools. `ToolChoice.None` keeps the model from calling `get_weather` (which would insert database rows), preserving behaviour. Conflict recorded in R1 |
| D5 | `tools/get-weather-tool.json` is the runtime source of truth for name + description (OQ-02 / ASM-02); load fail-fast at startup | (a) documentation-only file (FR-09 dropped); (b) load at runtime with fallback to hardcoded defaults | FR-09 requires the description sent to be the file's; a fallback default would silently diverge from the file. The `parameters` block stays generated from the args class (Koog owns schema generation) and is guarded by a consistency test |
| D6 | Tool-call logging happens in the agent strategy (one `stage=tool` line after `executeTools`); the DB outcome is a separate `stage=db` line from the tool | (a) two lines per invocation (call, then result); (b) log from inside the tool only; (c) put the insert outcome into the returned string | (a) doubles stage-(d) entries against NFR-01; (b) loses the arguments the model asked for; (c) changes the prompt/answer content, which is out of scope. One strategy line + one tool-internal line satisfies FR-06 with the fewest entries |
| D7 | Secrets: the decorator receives only an endpoint label and never the config object; no headers are logged; truncation applies everywhere | Log the full outgoing HTTP request incl. headers | The API key travels in the `Authorization` header, and `DeepseekConfig`/`DbConfig` are data classes whose `toString()` would expose secrets — they are never logged (NFR-02) |
| D8 | Drop wire-level `response_format: json_object`; rely on the system instruction plus the existing lenient parser | `LLMParams(schema = Schema.JSON.Basic(...))` (Koog's only structured-output parameter) | Koog 1.2.0 exposes JSON **schema** response format, which maps to `response_format: {"type":"json_schema", strict=true}`. DeepSeek rejects `json_schema` with HTTP 400 ("This response_format type is unavailable now"); using it would turn `/time` into a permanent 404. Observable behaviour (a zone or null) is preserved because `parseZoneContent` already tolerates fences and malformed answers. Risk R4 |
| D9 | Emit (a) and (e) from explicit helpers called by the handlers (and by StatusPages), not from Ktor plugin hooks | (a) keep `CallLogging` for the request line and add a response plugin (`onCallRespond`); (b) `intercept(ApplicationCallPipeline.Setup)` + `withContext`; (c) `ResponseBodyReadyForSend` hook | `CallLogging` writes its line after the handler completes, so it would appear *after* the DeepSeek and tool lines and break NFR-01's order, and it would add a sixth entry. `onCallRespond` sees only the pre-serialized object and its behaviour for StatusPages-produced responses is not guaranteed. Explicit helpers give a deterministic order, exactly one entry per stage, and are trivially asserted in route tests |
| D10 | Log the request body where it is parsed (route), re-serialized from the DTO | `DoubleReceive` plugin + `call.receiveText()` in a plugin; `onCallReceive { transformBody { … } }` channel read-and-replace | Both alternatives read the body channel ourselves (or add a dependency and an experimental API) before the route receives it, which risks breaking `POST /weather` (NFR-04). Re-serializing the already-parsed body yields exactly the JSON the acceptance criterion expects, with no new dependency |
| D11 | Break the descriptor/registry cycle with a lazy `toolDescriptorsProvider: () -> List<ToolDescriptor>` injected into the resolver (cached with `by lazy`) | (a) eager `ToolRegistry` injection (Koin cycle: resolver → registry → tool → resolver); (b) hand-build a `ToolDescriptor` via internal `getToolDescriptor`; (c) pass `ToolRegistry` into the tool and the resolver and resolve the tool lazily | (a) fails at runtime with a circular dependency; (b) uses `@InternalAgentToolsApi` and duplicates Koog's schema generation; (c) is the same idea with more coupling. A provider lambda is plain Kotlin, keeps the cycle broken, and is easy to fake in tests |
| D12 | One chain logger name (`com.aiturbo.trace`) with `req=`/`stage=` markers; no MDC, existing pattern | Per-class loggers + MDC `reqId` in the logback pattern | MDC is thread-local and does not survive coroutine dispatcher hops (the agent, the executor and the tool all hop dispatchers), so MDC would produce lines without the id; the message marker works everywhere and needs no pattern change (NFR-01) |
| D13 | Correlation id as a `CoroutineContext.Element` (`CallTrace`) set by `withContext(trace)` around LLM-reaching work | `call.attributes` only (invisible to the agent); request-scoped Koin scope; thread-local | Verified: `AIAgentRunSessionImpl.run` executes the strategy in the caller's context, so the element reaches the strategy, the decorator and the tool. `call.attributes` cannot reach Koog internals |
| D14 | Keep every endpoint, status code, DTO and the database schema untouched; no new Gradle dependency | Refactors of routes/DTOs, `ktor-server-double-receive` | NFR-05/NFR-06; the feature is about the LLM path and observability only. `03-plan.md` and this document are the pipeline artifacts for NFR-07/FR-08 |

## Non-functional coverage

**NFR-01 (five ordered entries, one id, shipped config).** Order is structural: (a) is logged before
the handler runs any work, (b)/(c) come from the decorator around the LLM call, (d) from the strategy
before it returns, (e) from `respondTraced` last. One entry per stage by construction (D6, D9). The
shipped `logback.xml` (root INFO, STDOUT) prints everything; the only change is an explicit
`<logger name="com.aiturbo.trace" level="INFO"/>` entry for discoverability, which does not change
what is printed.

**NFR-02 (no secrets).** The decorator's inputs are the endpoint string, the model id, the prompt,
the descriptors and the response (D7). No headers, no `Authorization`, no `DeepseekConfig`, no
`DbConfig` and no JDBC URL are logged; the API key only ever exists inside Koog's OpenAI client.
Truncation does not affect this. A test asserts that a run's captured trace output contains neither
the configured key nor the database password.

**NFR-03 (truncation).** `TraceLog.truncate` caps every body-like field at 4096 characters and adds
the marker `…[truncated, NNNN chars total]`; unit-tested at the boundary (4095/4096/4097).

**NFR-04 (logging cannot change behaviour).** Logging never consumes the body (D10), never mutates
the prompt/tools/response (the decorator passes `tools` through untouched), and every logging call in
the request path is wrapped so that a serialization or appender failure cannot raise a new status
code: `beginTrace`/`respondTraced` catch `Throwable` around logging and fall back to plain
`call.respond`. Status codes stay 400/404/500/503 exactly as today, asserted by the existing route
tests plus new 400/404/503 log assertions.

**NFR-05 (offline tests, 52 green).** All new behaviour is tested without a database or a live LLM:

| Test | Coverage |
|---|---|
| `TraceLogTest` (new) | truncation boundary + marker; `describeError` on a `KoogHttpClientException` clone/cause chain; stage line shape captured through a logback `ListAppender` on `com.aiturbo.trace` |
| `TraceFormatsTest` (new) | message rendering, assistant rendering (text-only, tool-call-only, mixed), tool JSON rendering |
| `ToolJsonRendererTest` (new) | wire shape `type/function/name/description/parameters`, `required=["location"]`, description equals the spec's |
| `ToolSpecTest` (new) | valid file parses; name/description/parameters as in the file; missing/malformed resource (test resource) → `IllegalStateException`; consistency: `GetWeatherTool(..., spec).descriptor.description == spec.description` and `location` is a required parameter |
| `LoggingPromptExecutorTest` (new) | fake `PromptExecutor` delegate; (b) contains `tools_count=1` and the `get_weather` JSON; (c) contains the tool call; the delegate receives the identical `tools` list; empty list → `tools_count=0` warning and no throw; failure → (c) with `error=` and the exception still propagates |
| `LlmTimeZoneResolverTest` (rewrite) | fake executor returning `Message.Assistant(parts = listOf(MessagePart.Text("""{"timezone":"Europe/Paris"}""")))`; fences; `{"timezone":null}`; malformed; executor throws → null; key not configured → executor never called; captured call args: non-empty tools containing `get_weather`, `toolChoice == None` |
| `KoogWeatherAgentTest` (new) | real `AIAgent` + fake executor (tool call, then text) + fake `SimpleTool`: tool executed once, answer returned, exactly one `stage=tool` line with args/result, executor received the registry's non-empty descriptors |
| `WeatherRoutesTest` (modify) | existing 200/400/503 cases plus: (a) line contains the posted body, (e) line contains status 200 and the answer, 400/503 are logged; no status-code regression |
| `ApplicationTest` (modify) | 200/400/404 for `/time` and `/` plus (a)/(e) lines for each, including the 404 outcome |
| `GetWeatherToolTest` (modify) | existing cases with the new `spec` constructor argument; success logs `stage=db saved=true`, `throwOnSave` logs `saved=false` and still answers |
| `RawDeepSeekCallTest` (new) | static guard for FR-01: no file under `src/main/kotlin` contains a manual `"Bearer "` header, and `chat/completions` appears only in `weather/DeepSeekKoogLlm.kt` (Koog factory) and `Application.kt` (log label) |

Existing tests keep their fakes, `MockEngine` usage and frozen clock; only the two constructors that
changed (`LlmTimeZoneResolver`, `GetWeatherTool`) and the route tests' new assertions need edits.

**NFR-06 (backwards compatibility).** See the HTTP table in §API design: identical endpoints, status
codes and DTOs; `users` schema untouched; the answer string for the weather tool is unchanged,
because the DB outcome is logged rather than appended. Route tests pin every documented outcome.

**NFR-07 / NFR-08 (process).** Requirements, design (this document) and plan live in
`docs/features/koog-everything-and-logging/` in that order, each produced by its own stage.

### Configuration changes

| File | Change |
|---|---|
| `src/main/resources/logback.xml` | add `<logger name="com.aiturbo.trace" level="INFO"/>` above `<root>`; pattern and STDOUT appender unchanged |
| `src/main/resources/application.conf` | comment fix only (the DeepSeek block now describes the Koog-only path); no new keys |
| `src/main/kotlin/com/aiturbo/plugins/Routing.kt` | remove `install(CallLogging)` and its imports (replaced by the trace helpers) |
| `build.gradle.kts` | none (no new dependency; `ktor-server-call-logging` becomes unused and may be dropped later) |
| `README.md` | document the Koog-only DeepSeek path, the five-stage log chain, the tool JSON resource and the Postman recipe; remove "DeepSeek LLM over HTTP" wording |

### Compatibility and regression notes

- `TimeZoneResolver` interface, resolver chain order and caching are untouched; `/time` for IANA ids
  and built-in cities never reaches the LLM, exactly as before.
- The only behaviour-visible change on `/time` is the loss of the wire-level JSON mode (D8), which is
  compensated by the unchanged system instruction and the existing lenient parser (R4).
- The `POST /weather` answer text, response shape and 503/400 semantics are unchanged; the tool's
  return value is deliberately not modified (D6).
- `KoogWeatherAgent.answer` signature is unchanged, so `WeatherRoutesTest`'s fake agent and the
  Postman flow keep working.
- Koin's override path (`Application.module(overrideModules)`) is untouched, so tests that build
  their own modules keep working; the new beans (`ToolSpec`, `ToolJsonRenderer`, `LoggingPromptExecutor`)
  live only in `appModules`.
- Removing `CallLogging` removes the old `INFO ... 200 OK: POST /weather` line; the trace lines
  replace it with strictly more information (method, path, query, client, body, status, body).

## Open questions and risks for the planner

| # | Item | Default assumption / mitigation |
|---|---|---|
| R1 | **`tools` on `/time` (FR-02 vs ASM-04/FR-05 acceptance text).** The requirements' FR-02 acceptance criterion says "A time-resolution request contains no `tools` field at all (see ASM-04)" and FR-05 says "for a time-resolution call, the request messages are logged (no tools)". The user's hard instruction says the opposite: every backend→DeepSeek request must carry the non-empty registry, never `[]` | **Design follows the user instruction:** `/time` sends the non-empty array with `toolChoice = None` (D4). The acceptance sentence about `/time` should be read as superseded; if the user prefers no `tools` field on `/time`, the change is a one-line `emptyList()` in the resolver — flag for confirmation during planning |
| R2 | DeepSeek must accept `tool_choice: "none"` together with a non-empty `tools` array, otherwise `/time` fails with 400 → 404 for every LLM-resolved location | OpenAI-compatible providers accept it; if a provider rejects it, drop `toolChoice` from `LLMParams` (behaviour then relies on the prompt alone; a tool-call answer is logged and treated as unresolvable). Verify in the manual smoke test of Flow 2 |
| R3 | `KoogHttpClientException` visibility/type for extracting `status=`/`body=` in `describeError` | Public in `ai.koog.http.client` in 1.2.0; if it is not accessible, fall back to the cause-chain `SimpleName: message` walk (the status still appears when the message carries it). Keep `describeError` unit-tested with a locally built cause chain |
| R4 | Dropping `response_format: json_object` (D8) could make the model answer with prose or fences more often, producing 404s for locations that used to resolve | Mitigated by the unchanged "return ONLY a JSON object" instruction plus fence stripping and tolerant parsing; validate with 2–3 unusual locations (`Kisumu`, `Tula`, `Atlantis`) during the manual run. If the rate degrades, the follow-up is a provider-side structured-output option (DeepSeek `strict` function calling or a beta endpoint), which would be a new requirement |
| R5 | NFR-01's "exactly one entry per chain stage" versus the extra `stage=db` line emitted by the tool | Interpreted as: the five chain stages have exactly one entry each; `stage=db` is a tool-internal detail required by FR-06. If the strictest reading is wanted, the alternative is to fold the DB outcome into the `stage=tool` line, which would require changing the tool's returned string (out of scope) or a logging side channel (over-engineering) — flag for confirmation |
| R6 | Body logging for requests whose body cannot be parsed (malformed JSON) shows no body | `beginTrace()` is called from the StatusPages handler in that case, so the request still gets one (a) line without body plus the (e) 400 line; the raw bytes are deliberately not re-read (D10) |
| R7 | Requests that never reach the routing table (unmatched path, wrong method) produce Ktor's default 404 without trace lines | Out of the documented API surface (ASM-07 targets the app's endpoints). Optional hardening in the plan: a tiny `onCall` fallback that logs an untraced request, if the user wants full coverage |
| R8 | Test capture mechanism for log assertions (logback `ListAppender`) couples tests to logback-classic | Acceptable: logback-classic is already a compile dependency and is what the app ships; the helper lives in one test utility and the trace logger name is a constant in `TraceLog` |
| R9 | `LoggingPromptExecutor.executeStreaming` logs the request but not the frames | Nothing in the application streams; documented limitation, revisit only if a streaming path is added |
| R10 | Startup now fails when `tools/get-weather-tool.json` is missing or invalid | Intentional fail-fast (D5); the resource ships in the jar, and CI/tests cover the loader. If the user prefers a degraded start, fall back to the previous hardcoded description — confirm during planning |
