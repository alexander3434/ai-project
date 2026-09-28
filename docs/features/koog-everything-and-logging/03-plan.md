# Koog-Everything and Logging — Implementation Plan

Stage: **plan** (`/feature-design`, planner). Inputs: `01-requirements.md`, `02-design.md`.
Output of the next stage: implementation, executed task by task in the order below.

Scope rules for everyone executing this plan:

- Implement exactly what `02-design.md` specifies; do not re-design, add endpoints, or change response shapes.
- Keep all **52 existing tests green**; adapt them only where a constructor or route changes, never dropping a covered case.
- All new tests must be **offline**: fakes, `MockEngine`, frozen clock — no real PostgreSQL, no live DeepSeek.
- Size legend: S ≤ 2 h, M ≤ 4 h, L ≤ 1 developer-day. No task exceeds one developer-day.
- File-path corrections against the design inventory (verified in the repo): the design names
  `weather/KoogWeatherAgent.kt`, but the class `KoogWeatherAgent` lives in
  `src/main/kotlin/com/aiturbo/weather/WeatherAgent.kt` — use the real path. `src/test/resources/`
  does not exist yet; the first task that needs it creates it.

## Task list

| ID | Title | Depends on | Size | Description (design ref) | Files to touch |
|---|---|---|---|---|---|
| T-01 | Correlation id + trace logger foundation | — | M | `CallTrace` (`CoroutineContext.Element`, 8-char id, `newTraceId()`) and the `TraceLog` object on logger `com.aiturbo.trace`: `currentId()`, stage methods `inbound/deepseekRequest/deepseekResponse/deepseekFailure/tool/toolDb/outbound`, `truncate` (4096 chars + `…[truncated, NNNN chars total]`), `describeError` (cause chain, Koog HTTP status/body when accessible). Also the shared logback `ListAppender` test helper reused by every log-asserting test. (C2; NFR-01/NFR-02/NFR-03) | new `src/main/kotlin/com/aiturbo/log/CallTrace.kt`, `src/main/kotlin/com/aiturbo/log/TraceLog.kt`; new `src/test/kotlin/com/aiturbo/TraceLogTest.kt`; new test helper (e.g. `src/test/kotlin/com/aiturbo/LogCapture.kt`) |
| T-02 | Traffic formatters | — | S | Pure single-line renderers `renderMessages`, `renderAssistant`, `renderJson` for the log line, no logging side effects. (C3; FR-04/NFR-03) | new `src/main/kotlin/com/aiturbo/log/TraceFormats.kt`; new `src/test/kotlin/com/aiturbo/TraceFormatsTest.kt` |
| T-03 | Wire-format tool renderer | — | S | `ToolJsonRenderer.toWireJson` / `renderAll` producing the OpenAI wire shape with parameters from Koog's own `OpenAICompatibleToolDescriptorSchemaGenerator`, so the logged schema cannot drift from the request Koog builds. (C9; FR-02/FR-04) | new `src/main/kotlin/com/aiturbo/tools/ToolJsonRenderer.kt`; new `src/test/kotlin/com/aiturbo/ToolJsonRendererTest.kt` |
| T-04 | Logging executor decorator | T-01, T-02, T-03 | M | `LoggingPromptExecutor(delegate, endpoint, toolJsonRenderer) : PromptExecutor()` — the single choke point. Logs `stage=deepseek-request` (endpoint, model, messages, `tools_count`, tools, `tool_choice`) before delegating untouched; `stage=deepseek-response` (text, tool calls) or `…error=` on failure, rethrowing; `CancellationException` rethrown silently; WARN duplicate when tools are empty, never throws; `executeStreaming` logs the request only; `close()` delegates. (C4; FR-01/FR-02/FR-04/FR-05) | new `src/main/kotlin/com/aiturbo/log/LoggingPromptExecutor.kt`; new `src/test/kotlin/com/aiturbo/LoggingPromptExecutorTest.kt` |
| T-05 | `LlmTimeZoneResolver` rewritten on Koog | T-04 | M | Remove the last raw DeepSeek caller: constructor becomes `(promptExecutor, model, toolDescriptorsProvider, apiKeyConfigured = true)`; prompt with `temperature=0.0`, `maxTokens=64`, `toolChoice = None`, unchanged system instruction; call `promptExecutor.execute(prompt, model, toolDescriptorsProvider())`; text parts concatenated then `parseZoneContent` (extracted `internal`); exceptions → null, `CancellationException` rethrown; blank key → no call. Raw DTOs and Ktor client usage deleted. (C5; FR-01) | modify `src/main/kotlin/com/aiturbo/time/LlmTimeZoneResolver.kt`; rewrite `src/test/kotlin/com/aiturbo/LlmTimeZoneResolverTest.kt` |
| T-06 | Tool spec resource + loader | — | S | `tools/get-weather-tool.json` under `src/main/resources/` (exact content from design §Data model) plus `ToolSpec` and `ToolSpecLoader.load()`: reads the classpath resource, validates non-blank name/description, throws `IllegalStateException` naming the path on missing/invalid, logs one INFO line. (C8; FR-08) | new `src/main/resources/tools/get-weather-tool.json`; new `src/main/kotlin/com/aiturbo/tools/ToolSpec.kt`; new `src/test/kotlin/com/aiturbo/ToolSpecTest.kt`; new fixture `src/test/resources/tools/invalid-tool.json` (creates `src/test/resources/`) |
| T-07 | `GetWeatherTool` spec-driven + DB outcome log | T-01, T-06 | M | Constructor gains `spec: ToolSpec`; `SimpleTool.name`/`description` come from the file (FR-09); the parameter schema still comes from `GetWeatherArgs`. Log exactly one `stage=db` line per insert attempt (`saved=true id=<n>` / `saved=false reason=…`); returned string unchanged. (C7; FR-06/FR-09) | modify `src/main/kotlin/com/aiturbo/weather/GetWeatherTool.kt`; modify `src/test/kotlin/com/aiturbo/GetWeatherToolTest.kt` |
| T-08 | Koin wiring | T-04, T-05, T-07 | M | Apply the design's `appModules` graph: `ToolSpec` bean (`createdAtStart = true`), `ToolJsonRenderer`, `LoggingPromptExecutor` as the only `PromptExecutor`, `GetWeatherTool(..., spec)`, resolver with the lazy `toolDescriptorsProvider = { get<ToolRegistry>().tools.map { it.descriptor } }` and `apiKeyConfigured = deepseek.apiKey.isNotBlank()`; blank key still yields the 503 `WeatherAgent`. (C10; FR-01/FR-02/FR-09) | modify `src/main/kotlin/com/aiturbo/Application.kt`; add wiring assertions in `src/test/kotlin/com/aiturbo/ApplicationTest.kt` or a new `AppModulesTest.kt` |
| T-09 | Weather agent `stage=tool` logging | T-01 | M | Add exactly one `stage=tool` line per tool invocation in the strategy, after `executeTools`, with `tool=`, `args=`, `result=`, `is_error=`; `req=` id from the coroutine context (`req=-` when absent). Strategy shape (max rounds, non-convergence warn, final text join) unchanged. (C6; FR-06) | modify `src/main/kotlin/com/aiturbo/weather/WeatherAgent.kt` (`KoogWeatherAgent`); new `src/test/kotlin/com/aiturbo/KoogWeatherAgentTest.kt` |
| T-10 | `RequestTracing` on the Ktor side | T-01 | M | `ApplicationCall.beginTrace(body)`, `traceOrNull()`, `respondTraced(body, status)` (idempotent inbound line with method/path/query/client/body; outbound line with status + body; logging wrapped so it can never change the response). Trace stored in `call.attributes`; id in the coroutine context. (C1; FR-03/FR-07/NFR-04) | new `src/main/kotlin/com/aiturbo/plugins/RequestTracing.kt`; new `src/test/kotlin/com/aiturbo/RequestTracingTest.kt` |
| T-11 | Route rewiring | T-08, T-09, T-10 | M | Remove `install(CallLogging)`; replace every `call.respond` (routes and the four StatusPages handlers) with `respondTraced`; StatusPages creates a missing trace first; `beginTrace` after `receive<WeatherRequest>()` with the re-serialized body; wrap `agent.answer` and `resolver.resolve` in `withContext(trace)`. Status codes unchanged. (C1; FR-03/FR-07/NFR-04/NFR-06) | modify `src/main/kotlin/com/aiturbo/plugins/Routing.kt`, `src/main/kotlin/com/aiturbo/plugins/WeatherRouting.kt`; modify `src/test/kotlin/com/aiturbo/WeatherRoutesTest.kt`, `src/test/kotlin/com/aiturbo/ApplicationTest.kt` |
| T-12 | Offline five-stage chain integration test | T-11 (and all above) | L | New end-to-end offline test: boot the app with test modules (fake `PromptExecutor` wrapped by `LoggingPromptExecutor`, fake DB/weather clients, real `ToolRegistry`/`GetWeatherTool`/resolver chain) and assert the whole chain contract: order, one id, non-empty tools on both paths, secrets absent, truncation, statuses unchanged. (NFR-01/NFR-02/NFR-03/NFR-04/NFR-06) | new `src/test/kotlin/com/aiturbo/TraceChainIntegrationTest.kt` |
| T-13 | Static guard test for the Koog-only rule | T-05 | S | `RawDeepSeekCallTest`: scan `src/main/kotlin/**` — no `"Bearer "` anywhere; `chat/completions` only in `weather/DeepSeekKoogLlm.kt` and `Application.kt`; failure output names the offending file. (FR-01) | new `src/test/kotlin/com/aiturbo/RawDeepSeekCallTest.kt` |
| T-14 | Full-suite regression pass | T-12, T-13 (all) | S | Run the whole suite and account for it: `./gradlew test` green offline; the 52 baseline tests all still present (adapted ones retain their original cases); all new test classes listed; no test touches DB/network; `./gradlew build` succeeds. (NFR-05/NFR-06) | no production change expected; test fixes only if a failure is found |
| T-15 | Shipped configuration | — | S | Add `<logger name="com.aiturbo.trace" level="INFO"/>` to `logback.xml` (pattern and STDOUT untouched); fix the stale DeepSeek comment in `application.conf` to describe the Koog-only path (no new keys). (NFR-01, FR-11) | modify `src/main/resources/logback.xml`, `src/main/resources/application.conf` |
| T-16 | README | T-15, T-11 | M | Document the Koog-only DeepSeek path, the five-stage log chain (with a sample and the `stage=db` explanation), the tool JSON resource as the description source, and the Postman recipe verbatim from `01-requirements.md` (both requests + SQL/history check); remove "DeepSeek LLM over HTTP" wording; link the three pipeline documents. (FR-10/FR-11/NFR-07/NFR-08) | modify `README.md` |

## Work order and milestones

Execution batches (tasks inside a batch touch disjoint files and can run in parallel; the developer
follows the batches top to bottom):

| Batch | Tasks (parallel) | Notes |
|---|---|---|
| 1 | T-01, T-02, T-03 | All new files; no production wiring touched. T-02/T-03 are independent of T-01. |
| 2 | T-04 | Needs the logger, formatters and renderer from batch 1. |
| 3 | T-05, T-06 | Resolver rewrite (the only raw DeepSeek caller disappears) and the tool JSON resource + loader. T-06 has no dependencies and may be pulled earlier. |
| 4 | T-07 | Needs `ToolSpec` (T-06) and `TraceLog.toolDb` (T-01). |
| 5 | T-08, T-09, T-10 | Wiring, agent logging and request tracing are independent of each other (T-08 needs T-04/T-05/T-07). |
| 6 | T-11 | Routes are the last production file set; needs the helpers and the wired graph. |
| 7 | T-12, T-13 | The two new guard/integration suites; T-13 can run as soon as T-05 is done. |
| 8 | T-14, T-15 | Regression pass and shipped configuration; independent. |
| 9 | T-16 | README last, against the frozen behavior. |

Milestones (checkpoints, each with a verifiable exit):

- **M-01 — Foundations ready** (after batch 2). `CallTrace`/`TraceLog`/`TraceFormats`/`ToolJsonRenderer`/`LoggingPromptExecutor` exist with their unit tests green; no production behavior changed yet; the 52 existing tests still pass.
- **M-02 — Koog-only LLM path in code** (after batch 3). `LlmTimeZoneResolver` no longer performs raw HTTP; the decorator is the single executor path; the tool JSON resource loads; `RawDeepSeekCallTest` (T-13) passes.
- **M-03 — Feature wired end-to-end** (after batch 6). `appModules` resolves without cycles; routes emit `stage=inbound`/`stage=outbound`; the agent emits `stage=tool`; the tool emits `stage=db`; `./gradlew build` green.
- **M-04 — Verified offline** (after batch 8). T-12, T-13, T-14 green: chain order, single correlation id, non-empty tools on both paths, no secrets, statuses unchanged; the 52 baseline tests accounted for; the whole suite runs without DB/LLM.
- **M-05 — Documentation and manual smoke** (after batch 9). T-15/T-16 done. Then the user/developer runs the manual checklist on a live server (key + DB present): Postman Request 1 → 200, one new `users` row, `GET /weather/history?limit=5` returns it; console shows the five-stage chain in order with `tools_count=1` and the `get_weather` definition and no secrets; `GET /time?location=<not in the built-in map>` (try `Kisumu`, `Tula`, `Atlantis`) resolves via Koog (`tool_choice=none`) or returns 404; grep of the log for the key/password → 0 hits.

## Acceptance criteria

### T-01 — Correlation id + trace logger foundation

- `newTraceId()` returns an 8-character id; `CallTrace` works as a coroutine context element: `TraceLog.currentId()` returns the id inside `withContext(CallTrace(id))` and `null` outside.
- `truncate`: 4095- and 4096-character inputs are unchanged; 4097 characters produce exactly 4096 characters plus the marker `…[truncated, 4097 chars total]`; the marker format is asserted literally.
- `describeError` renders the cause chain as `SimpleName: message` (tested with a locally built nested chain); when the shipped Koog exception type is used, `status=`/`body=` (truncated) are appended — if that type is not accessible, the cause-chain-only fallback ships and the report says so (risk R-06).
- Each stage method emits exactly one line shaped `req=<id> stage=<marker> <key=value …>` on logger `com.aiturbo.trace`; a `null` id renders `req=-`, never `null`; captured via the shared `ListAppender` helper.
- No call path passes a `DeepseekConfig`, `DbConfig`, header map or raw key to `TraceLog` (reviewer check of the signatures).
- `./gradlew test` green (52 baseline tests unaffected).

### T-02 — Traffic formatters

- `renderMessages` renders a two-message prompt as one line `[system: "…", user: "…"]` (no newlines).
- `renderAssistant` covers three cases: text-only (`text="…"`), tool-call-only (`tool_calls=[{"name":…,"args":…}]`), and mixed; `args` is the JSON string from `MessagePart.Tool.Call`.
- `renderJson` returns compact one-line JSON for a `JsonObject`.
- All formatters are pure (no logger access) and all outputs are single-line for multi-line inputs.

### T-03 — Wire-format tool renderer

- `toWireJson` yields exactly `{"type":"function","function":{"name":…,"description":…,"parameters":…}}`; `parameters` comes from `OpenAICompatibleToolDescriptorSchemaGenerator`.
- For a descriptor with a required string `location` property, the rendered schema contains `"required":["location"]` and a `location` property.
- `renderAll(emptyList())` returns `"[]"`; `renderAll(list)` is one line; rendering never mutates or reorders the input list.
- The test builds the descriptor from a real tool (registry or anonymous `SimpleTool`) and asserts name/description round-trip.

### T-04 — Logging executor decorator

- For a delegated `execute`, exactly one `stage=deepseek-request` line is emitted **before** the delegate is called, containing `endpoint=`, `model=<id>`, the rendered messages, `tools_count=<n>`, the rendered tools and `tool_choice=<value|->`.
- The delegate receives the identical `tools` list (same contents and order; the test's fake records the argument and asserts equality) — the decorator never adds, filters or reorders.
- Empty tools: `tools_count=0` on the line plus one WARN-level duplicate; the call proceeds, no exception (NFR-04, D3).
- Success: exactly one `stage=deepseek-response` line with `text=` and `tool_calls=[…]` (empty list for a text-only reply).
- Failure: one `stage=deepseek-response … error=<describeError>` line and the original exception rethrown; `CancellationException` rethrown with no response line.
- `executeStreaming` logs one request line carrying `streaming=true` and forwards the frames unchanged; `close()` delegates to the wrapped executor.
- All body-like fields are truncated per T-01. Tests use a fake delegate only — no network.

### T-05 — `LlmTimeZoneResolver` rewritten on Koog

- The file contains no Ktor client usage, no `Authorization`/`Bearer`, no `chat/completions`, and the raw DTOs (`ChatMessage`, `ChatCompletionRequest`, `ChatCompletionResponse`, `Choice`, `ResponseFormat`) are deleted.
- Constructor is `(promptExecutor, model, toolDescriptorsProvider, apiKeyConfigured = true)`; `TimeZoneResolver` interface and `resolve` semantics unchanged.
- `apiKeyConfigured = false` → warn on the class logger, return `null`, executor call count is 0.
- The fake executor records the prompt: `temperature=0.0`, `maxTokens=64`, `toolChoice == LLMParams.ToolChoice.None`, the unchanged system instruction, user message `Location: <location>`.
- The executor receives the non-empty descriptor list from `toolDescriptorsProvider()` (the fake asserts a `get_weather` descriptor is present); the provider is invoked lazily and its result reused (`by lazy`).
- `parseZoneContent` (now `internal`) is unit-tested directly: plain JSON, ```` ```json ```` fences, `{"timezone":null}`, malformed text, invalid IANA id → `null`; text parts are concatenated before parsing.
- An answer containing only a tool call is not executed → `null`; a non-cancellation exception → warn + `null`; `CancellationException` rethrown.
- Every case of the old `LlmTimeZoneResolverTest` still exists, adapted to the fake executor.

### T-06 — Tool spec resource + loader

- `src/main/resources/tools/get-weather-tool.json` parses as JSON with `name = "get_weather"`, a non-blank `description`, `parameters.properties.location` present and `required = ["location"]` (content per design §Data model).
- `ToolSpecLoader.load()` decodes the shipped resource and returns the same values.
- The malformed fixture (`src/test/resources/tools/invalid-tool.json`) and a nonexistent path both throw `IllegalStateException` whose message names the resource path and carries the cause; blank name/description are rejected the same way.
- Loading logs exactly one INFO line on the loader's own class logger.

### T-07 — `GetWeatherTool` spec-driven + DB outcome log

- Constructor accepts `spec: ToolSpec`; `descriptor.name == spec.name` and `descriptor.description == spec.description` (FR-09), while the parameter schema is still generated from `GetWeatherArgs` (`location` required, `@LLMDescription` unchanged).
- The returned string is byte-for-byte identical to the current implementation for the same inputs (no suffix added).
- Successful save with id ≥ 0 → exactly one `stage=db tool=get_weather saved=true id=<n>` line; negative id (unique conflict) → `saved=false` with a reason; repository throwing → `saved=false reason=<message>` and the answer is still produced.
- Existing `GetWeatherToolTest` cases are all retained (only the constructor argument is added); new assertions cover the two `stage=db` outcomes.
- Consistency check: the shipped resource's `description` equals the descriptor description and `location` appears as a required parameter in the generated schema.

### T-08 — Koin wiring

- `appModules` binds `ToolSpec` via `ToolSpecLoader.load()` with `createdAtStart = true`, `ToolJsonRenderer`, `LoggingPromptExecutor` as the only `PromptExecutor` (delegate = `deepseekPromptExecutor(deepseek)`, endpoint = `<baseUrl>/chat/completions`), `GetWeatherTool` with the spec, and the resolver with `toolDescriptorsProvider = { get<ToolRegistry>().tools.map { it.descriptor } }` and `apiKeyConfigured = deepseek.apiKey.isNotBlank()`.
- Building the graph offline with test configs and resolving `PromptExecutor`, `TimeZoneResolver`, `ToolRegistry`, `GetWeatherTool`, `ToolSpec` and `WeatherAgent` (blank key) raises no circular-dependency exception and performs zero network calls; the resolved `PromptExecutor` is a `LoggingPromptExecutor`.
- Blank key still yields the 503-throwing `WeatherAgent` lambda; `Application.module(overrideModules = …)` behavior is untouched and the existing `ApplicationTest` stays green.
- The descriptor provider is lazy: no `ToolRegistry` resolution happens while the resolver bean is constructed (the cycle break of D11) — asserted by resolving the resolver alone in the wiring test.

### T-09 — Weather agent `stage=tool` logging

- `KoogWeatherAgentTest` (real `AIAgent`, fake `PromptExecutor` returning a tool call then text, fake `SimpleTool`): the tool executes exactly once, the answer is returned, and exactly one `stage=tool` line with `tool=`, `args=`, `result=`, `is_error=false` is captured.
- An error result yields `is_error=true`; args and result are truncated per T-01.
- Run inside `withContext(CallTrace(id))` the line carries `req=<id>`; run without it, `req=-` — no exception in either case.
- The fake executor records a non-empty descriptor list containing the registry's tool (the agent path can never send `tools: []`).
- The non-convergence warning and final-text join are unchanged (existing behavior assertions kept).

### T-10 — `RequestTracing` plugin

- `beginTrace(body)` logs exactly one `stage=inbound` line with `method=`, `path=`, `query=`, `client=` (`remoteHost:remotePort`) and `body=` when provided, stores the trace in `call.attributes`, and is idempotent (a second call adds no line and returns the same trace).
- `traceOrNull()` is `null` before `beginTrace` and non-null after.
- `respondTraced(body, status)` logs exactly one `stage=outbound` line with `status=` and the serialized body, then responds with the identical object and status (test with `testApplication`).
- A failure while serializing for the log does not change the response: the client still receives the intended status and body (NFR-04); the response object is never mutated.
- Body fields are truncated; the id on both lines is the same.

### T-11 — Route rewiring

- `install(CallLogging)` and its import are gone; captured test output contains no `… 200 OK: POST /weather` style line from the old plugin.
- Every `call.respond` in `Routing.kt`, `WeatherRouting.kt` and the four StatusPages handlers is replaced by `respondTraced`; StatusPages handlers call `beginTrace()` when the trace is missing.
- `POST /weather` logs the inbound body as `{"message":"…"}` re-serialized from the DTO; blank message → 400 with only `stage=inbound` and `stage=outbound`, zero agent calls.
- `agent.answer` and `resolver.resolve` run inside `withContext(trace)` (the test's fake records the context element if needed).
- `WeatherRoutesTest`/`ApplicationTest`: every documented status unchanged (200/400/503 for `/weather`; 200/400/404 for `/time`; 200 for `/`; 200/400 for `/weather/history`); a 200 response logs `status=200` and the body; 400/404/503 responses are logged with their `ErrorResponse` body; malformed JSON body → inbound line without body + outbound 400; all lines of one request share one `req=`.
- Tests stay offline (fake agent/repository, frozen clock).

### T-12 — Offline five-stage chain integration test

- One `POST /weather` (weather question) against test modules produces exactly one `stage=inbound`, one `stage=deepseek-request` per LLM round-trip (two for the normal tool loop), one matching `stage=deepseek-response` per round-trip, one `stage=tool`, at most one `stage=db`, and one `stage=outbound` — in that order, all sharing one `req=` id.
- The `stage=deepseek-request` line for the weather flow has `tools_count >= 1` and a `get_weather` function entry whose `description` equals the shipped resource file's description.
- `GET /time?location=<not in the built-in map>` through the real resolver chain produces `inbound`, `deepseek-request` (`tool_choice=none`, `tools_count >= 1`), `deepseek-response`, `outbound`; a `{"timezone":null}` reply → 404; no `stage=tool` line appears.
- No captured line contains `"tools":[]` or `tools_count=0`.
- Blank API key: `/time` → 404 and `/weather` → 503 with no DeepSeek request/response lines.
- No captured line contains the test's API-key or database-password fixture values; every body-like field is ≤ 4096 characters plus the truncation marker.
- The test runs with fakes only (no DB, no network) and passes under `./gradlew test`.

### T-13 — Static guard test for the Koog-only rule

- Scanning `src/main/kotlin/**`: zero occurrences of `"Bearer "`; `chat/completions` appears only in `weather/DeepSeekKoogLlm.kt` and `Application.kt`.
- On violation the test fails and prints the offending file path(s) and line numbers.

### T-14 — Full-suite regression pass

- `./gradlew test` exits 0 with no database and no API key configured; `./gradlew build` succeeds.
- The run report accounts for all 52 baseline tests: each either unchanged or adapted with its original scenario still asserted; no case deleted without an equivalent replacement.
- All new test classes (T-01…T-13) are present and green; a spot check confirms none opens a socket or a JDBC connection.

### T-15 — Shipped configuration

- `logback.xml` contains `<logger name="com.aiturbo.trace" level="INFO"/>` above `<root>`; the pattern, appender and root level are unchanged.
- `application.conf` DeepSeek comment describes the Koog-only path; the stale "over HTTP" phrasing is gone; no new configuration keys.
- `./gradlew test` unaffected; the manual smoke (M-05) shows the chain with the shipped configuration and requires no logback edit.

### T-16 — README

- README documents: the Koog-only DeepSeek path (no production raw HTTP caller), the five-stage chain with a sample line set (noting the extra `stage=db` tool-internal line), the tool JSON resource and that it drives the name/description sent to DeepSeek, and the Postman recipe verbatim from `01-requirements.md` including the `psql`/history check.
- No stale statement contradicting the shipped behavior (review of README vs the three pipeline docs vs the code finds none); the pipeline documents are linked.

## Traceability matrix

Every FR and NFR from `01-requirements.md` maps to at least one task. **All FRs/NFRs covered.**

| Requirement | Covered by |
|---|---|
| FR-01 (all DeepSeek traffic via Koog) | T-04, T-05, T-08, T-13 |
| FR-02 (never an empty `tools` array; tool users send `get_weather`) | T-03, T-04, T-05, T-07, T-09, T-12 |
| FR-03 (every inbound request logged incl. body) | T-01, T-10, T-11, T-12 |
| FR-04 (outbound DeepSeek request logged: model, messages, tools) | T-02, T-03, T-04, T-05, T-12 |
| FR-05 (DeepSeek response logged incl. tool calls and failures) | T-04, T-05, T-11, T-12 |
| FR-06 (tool invocation + DB outcome logged) | T-01, T-07, T-09, T-12 |
| FR-07 (every HTTP response logged with status and body) | T-10, T-11, T-12 |
| FR-08 (JSON file describing the tool under `src/main/resources/`) | T-06, T-08, T-12 |
| FR-09 (description sent to DeepSeek matches the file) | T-06, T-07, T-08, T-12 |
| FR-10 (Postman recipe) | T-16 (mapped verbatim); verified live at M-05 |
| FR-11 (README updated, stale statements removed) | T-15, T-16 |
| NFR-01 (five ordered entries, one id, shipped config) | T-01, T-04, T-09, T-10, T-11, T-12, T-15 |
| NFR-02 (no secrets in logs) | T-04 (inputs exclude secrets), T-12 (absence assertion), T-16 (documented) |
| NFR-03 (bodies ≤ 4096 chars + marker) | T-01 (mechanism), T-04/T-09/T-10/T-11 (callers), T-12 (assertion) |
| NFR-04 (logging never changes API behavior) | T-05 (null/404 preserved), T-10 (wrapped logging), T-11 (statuses pinned), T-12, T-14 |
| NFR-05 (offline tests, 52 green, new coverage offline) | T-14 (accounting) + every task's test acceptance criteria |
| NFR-06 (endpoints/status codes/shapes unchanged) | T-04, T-05, T-11 (route tests), T-12, T-14 |
| NFR-07 (pipeline artifacts exist in order) | produced by the running pipeline; T-16 (consistency check); feature DoD |
| NFR-08 (dedicated stage per artifact) | `01`/`02`/`03` each identify their stage; T-16 references them; feature DoD |

## Risk register

| ID | Source | Risk | Likelihood | Impact | Mitigation | Owner / verifier |
|---|---|---|---|---|---|---|
| R-01 | architect R1: FR-02/FR-05 text says `/time` sends no `tools` field, conflicting with the user's non-empty-tools rule | `/time` requests send `tools` + `toolChoice=none` (design D4); the requirements sentence is superseded | Med | Med | Implement D4 exactly; T-12 pins `tool_choice=none` and `tools_count>=1` on the `/time` line; document the supersession in README (T-16); if the user confirms the opposite at M-05, the revert is one line (`emptyList()` in T-05) plus test updates | Developer implements; reviewer checks against D4; user confirms before M-04 closes |
| R-02 | architect R2: DeepSeek may reject `tool_choice: "none"` with a non-empty `tools` array | Every LLM-resolved `/time` would fail with HTTP 400 → 404 | Low | High | Offline tests cannot see the provider, so the gate is the M-05 manual smoke (`Kisumu`, `Tula`); documented fallback if rejected: drop `toolChoice` from `LLMParams` and rely on the system instruction (a tool-call answer is logged and treated as unresolvable → 404, behavior preserved) | User/developer at M-05; tester verifies the parameter is sent as specified offline |
| R-03 | architect R4: dropping `response_format: json_object` (design D8) | Model answers with prose/fences more often → 404s for locations that used to resolve | Med | Med | Unchanged "return ONLY the JSON" instruction; fence stripping and tolerant parsing kept and unit-tested (T-05); M-05 smoke over three unusual locations; if the resolve rate degrades, a provider structured-output option is a new requirement (out of scope here) | User at M-05; tester pins parser cases |
| R-04 | architect R5: strict "exactly one entry per stage" reading vs the extra `stage=db` line | NFR-01 read literally would be violated by the tool-internal DB line | High (interpretation) | Low | Interpretation applied: exactly one entry per each of the five chain stages; `stage=db` is the FR-06 tool-internal detail (design D6). T-12 asserts one line per chain marker and allows exactly one `stage=db`; README explains it. If the user demands literally five lines: fold the DB outcome into `stage=tool` (requires changing the tool output — out of scope) or drop `stage=db` (loses FR-06's DB outcome) — needs an explicit user decision | Reviewer verifies T-12 assertions; user confirms at M-05 |
| R-05 | architect R10: startup fails fast when the tool JSON is missing/invalid | A packaging mistake prevents the service from starting | Low | High | Intentional (design D5); the resource ships in the jar; `ToolSpecTest` covers malformed/missing and the error names the path (T-06); a degraded-start fallback is a one-line change but must not be implemented without user confirmation | Developer; user confirms fail-fast is acceptable at M-05 |
| R-06 | architect R3: `KoogHttpClientException` visibility for `status=`/`body=` extraction | Status/body missing from failure lines (FR-05 quality) | Med | Low | Use the public type if it compiles; otherwise ship the cause-chain-only fallback (`SimpleName: message`); `TraceLogTest` covers whichever branch ships and the report says which | Developer; tester |
| R-07 | integration: Koin cycle or `createdAtStart` ordering breaks only the production graph (tests override modules) | 500/startup failure not caught by the existing suite | Med | High | Lazy `toolDescriptorsProvider` is the designed cycle break (D11); T-08 includes an offline graph-resolution test; T-12 boots modules mirroring the production shape; `createdAtStart` failure surfaces as a clear `IllegalStateException` (T-06) | Developer; tester |
| R-08 | unknown-unknown: design's verified assumption that Koog runs the strategy in the caller's coroutine context could fail on the shipped artifact | Chain lines lose `req=` linkage (NFR-01) | Low | High | T-09 asserts the id reaches the strategy; T-12 asserts all five lines share one id. If it fails, escalate to the architect (the design would need an explicit id handoff) — do not improvise | Tester; escalation to architect |
| R-09 | test gap: log-capture helper (ListAppender) flakiness or cross-test ordering assumptions | Flaky, order-dependent tests | Med | Low | One shared helper (T-01); synchronous appender; filter captured lines by `req=` id; assert order only within one request's lines, never globally | Tester |
| R-10 | test gap: the real Postman + DB + live LLM path cannot be exercised offline | FR-10 and the live chain remain unverified by CI | Certain (by nature) | Med | Explicit M-05 manual checklist with exact commands and expected output; everything testable offline is pinned by T-12/T-14; the checklist is part of the feature DoD | User (manual smoke), developer supports |
| R-11 | integration: constructor/route changes break existing tests, tempting deletions | Coverage loss against NFR-05 | High | Med | T-05/T-07/T-11 adapt the affected tests keeping every original case; T-14 accounts for all 52 baseline tests and forbids silent deletions | Developer; tester verifies the count |
| R-12 | open questions: OQ-01/OQ-02 resolved by the risky assumptions ASM-01/ASM-02 | Wrong scope (city data requested, or the file should be documentation-only) | Low | Med | Plan follows design D5 (file for the tool, runtime source of truth); changes are localized to T-06/T-07; flagged for user confirmation early; if ASM-02 flips, FR-09 and the spec wiring are dropped and the tool keeps a hardcoded description | User confirms; planner flag recorded here |
| R-13 | test gap: the logged tools JSON is our rendering, not the byte-exact wire body Koog serializes | Drift between log and reality if Koog's serialization changes | Low | Med | The renderer reuses Koog's own `OpenAICompatibleToolDescriptorSchemaGenerator` and mirrors `toOpenAIChatTool` (T-03); shape pinned by test; residual drift accepted and documented in T-16 | Reviewer |

## Definition of Done (feature level)

1. All 16 tasks are complete; each task's acceptance criteria above are verified by the tester (offline).
2. `./gradlew test` is green with **no real database and no live LLM**; all **52 pre-existing tests are
   still present and green** (adapted only where constructors/routes changed, without losing a covered
   case), and every new component has new offline coverage (T-01…T-13). `./gradlew build` succeeds.
3. The traceability matrix above is satisfied: every FR-01…FR-11 and NFR-01…NFR-08 maps to at least
   one verified task.
4. Manual acceptance on a running local server (M-05 checklist): Postman Request 1 → 200 with a
   natural-language answer, exactly one new `users` row, and `GET /weather/history?limit=5` returns
   that row; the console shows the five-stage chain in order sharing one correlation id, with a
   non-empty `tools` array containing `get_weather` and zero secret values; `GET /time?location=` for
   a non-built-in place resolves through Koog or returns 404 as documented.
5. `docs/features/koog-everything-and-logging/` contains `01-requirements.md`, `02-design.md` and
   `03-plan.md`, each identifying its stage and consistent with the shipped behavior (NFR-07/NFR-08);
   README updated per FR-10/FR-11.
6. The open interpretation flags R-01 (`tools` on `/time`) and R-04 (`stage=db` line) are confirmed
   by the user or explicitly accepted as implemented.
