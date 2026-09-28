# Kodein + Exposed Stack and a Selectable Local (Ollama) Model — Requirements

This feature brings the code in line with the stack already fixed in `CODE_STYLE.md`/`CLAUDE.md` —
**only Kotlin, Ktor, Koog, Kodein, Exposed ORM**: Koin is removed completely (DI moves to
`org.kodein.di`) and both repositories (local `mydb2.users`, read-only stage `fueling`) move from raw
JDBC to Exposed. Both Koog tools (`GetWeatherTool`, `FindFuelingTool`) move into `com.aiturbo.tools`.
`POST /weather` and `POST /fueling` gain an optional per-request provider field (`"model":
"local" | "deepseek"`, default `deepseek`) where `local` routes that request's LLM traffic through
Koog's Ollama client to a locally running Ollama (`http://localhost:11434`, `qwen3:8b`), and every
`stage=deepseek-request`/`stage=deepseek-response` line must show which target the request actually
went to. All 208 existing offline tests stay green (adapted only where DI/constructors/contracts
change), behavior and the trace chain are preserved, and a documented latency comparison
(local vs DeepSeek) is a deliverable. Ollama installation and management are **out of scope** — the
user runs Ollama themselves (installed via brew, model `qwen3:8b` downloaded, service currently
stopped); the application only targets the configured endpoint.

## Context and goals

**Problem.** The project's governing documents already declare the target stack —
`CLAUDE.md`: "Kotlin + Ktor + Koog + Kodein + Exposed ORM"; `CODE_STYLE.md`: "Только: Kotlin, Ktor,
Koog, Kodein, Exposed ORM. Никакого Spring, Koin, сырого JDBC" and "`com.aiturbo.tools` — все
Koog-тулы". The code, however, still runs on Koin 4.1.0 (Ktor plugin + `by inject` + Koin modules)
and raw JDBC (`DriverManager`/`PreparedStatement`/`ResultSet`), and the two tools still live in the
domain packages (`weather/GetWeatherTool.kt`, `fueling/FindFuelingTool.kt`) — the stage repository
even carries the user's inline note `// …делать через ОРМ, а не jdbc`. In addition, the user wants a
per-request choice between a locally running Ollama model and the DeepSeek API, with the log showing
where each LLM request went, and a measured latency comparison of the two.

**Why now / what success looks like.** The code matches its own code-style contract (grep finds no
Koin, no `dotenv-kotlin`, no JDBC symbols in `src/main`); both databases are accessed through Exposed
(the stage one strictly read-only); the tools live in `com.aiturbo.tools` with their JSON resource
contracts unchanged; the two agent endpoints accept `"model": "local" | "deepseek"` and serve both;
the trace chain identifies the actual LLM target per request without leaking secrets; a benchmark
table (local vs DeepSeek, median + min/max) is recorded in this feature's docs; and the suite —
the 208 existing tests plus the new ones — is green and fully offline.

**Who it is for.** The developer/operator running `./gradlew run` locally, sending requests from
Postman, and reading the `com.aiturbo.trace` chain to verify each step.

**Current state (verified in the repository):**

- Stack: Kotlin 2.3.10, Ktor 3.3.3, Koog 1.2.0, Koin 4.1.0, dotenv-kotlin 6.5.1, PostgreSQL driver
  42.7.13, logback 1.5.18, Gradle 8.14.1, JVM 17.
- Koin: `appModules(deepseek, db, weather)` and the additive `fuelingModule(stageDb, apiKeyConfigured)`
  with named qualifiers in `src/main/kotlin/com/aiturbo/Application.kt`; `install(Koin)` in
  `Application.module`; `by inject` in `plugins/Routing.kt`, `plugins/WeatherRouting.kt`,
  `plugins/FuelingRouting.kt`; 7 test files build Koin modules (`AppModulesTest`, `ApplicationTest`,
  `WeatherRoutesTest`, `FuelingRoutesTest`, `FuelingModulesTest`, `TraceChainIntegrationTest`,
  `FuelingChainIntegrationTest`); `koin-test` is a test dependency.
- Raw JDBC: `db/JdbcWeatherRecordRepository.kt` (insert into `users` with retry on the UNIQUE
  conflict on `data`, up to 3 attempts, plus the recent-records select) and
  `db/JdbcStageFuelingRepository.kt` (read-only SELECTs across `fuelings`, `fuelings_archive`,
  `fuelings_drop` with `lower(fueling_id) = ?`, related `partner_fueling_events` /
  `fueling_feedback` / `belka_tokens`, one connection per call, 10 s timeouts, no connection at
  startup). No other code touches a database.
- Tools: `weather/GetWeatherTool.kt` and `fueling/FindFuelingTool.kt` (to be moved);
  `tools/ToolSpec.kt` (fail-fast loader) and `tools/ToolJsonRenderer.kt` already in
  `com.aiturbo.tools`; JSON resources `src/main/resources/tools/get-weather-tool.json` and
  `find-fueling-tool.json` are the source of truth for tool name/description.
- LLM plumbing: `weather/DeepSeekKoogLlm.kt` — `deepseekModel(id)` (DeepSeek provider, Tools
  capability) and `deepseekPromptExecutor(config)` →
  `MultiLLMPromptExecutor(LLMProvider.DeepSeek to OpenAILLMClient)`; `log/LoggingPromptExecutor.kt`
  is the single choke point logging `stage=deepseek-request` (with `endpoint=` and `model=`) and
  `stage=deepseek-response`; agents `weather/KoogWeatherAgent.kt` and `fueling/KoogFuelingAgent.kt`
  each run their own tool registry.
- Routes: `POST /weather` (`{"message"}` → `{"message","answer"}`), `POST /fueling` (same contract),
  `GET /time`, `GET /weather/history?limit=`, `GET /`; error contract 400/404/500/503 via
  StatusPages; `ignoreUnknownKeys = true` in the JSON config.
- Config/secrets: `application.conf` sections `deepseek.*`, `db.*`, `stageDb.*`, `weather.*`;
  secrets resolved config → environment variable → git-ignored `.env` (via dotenv-kotlin), never
  logged.
- Tests: **208 `@Test` methods across 30 files**, all offline (fakes, `MockEngine`, frozen clock,
  `LogCapture`); no real database, no live LLM, no network.
- Ollama (user-provided facts, not independently verified in this repo): installed via brew, version
  0.34.4, runtime at `/opt/homebrew/opt/ollama`, endpoint `http://localhost:11434`, model `qwen3:8b`
  downloaded, **service currently stopped**; the user manages it.
- Koog dependency: `ai.koog:koog-agents:1.2.0` declares `ai.koog:prompt-executor-ollama-client:1.2.0`
  transitively (verified in the cached POM; runtime scope) — no Ollama code exists in the repo yet.

**Goals.**

1. Koin is fully gone from the build, the sources and the tests; DI uses Kodein (`org.kodein.di`)
   with the same object graph and the same startup behavior.
2. Both databases are accessed only through Exposed ORM; no `DriverManager`, `PreparedStatement`,
   `ResultSet` or raw SQL strings in `src/main`; the stage access stays read-only; nothing connects
   at startup.
3. Both Koog tools live in `com.aiturbo.tools`; names/descriptions/behavior come from the existing
   JSON resources and do not change.
4. `POST /weather` and `POST /fueling` accept an optional per-request `model` field with `local` and
   `deepseek` values, `deepseek` as the default, and a clear 400 for an unknown value.
5. `local` routes the request through Koog's Ollama client to the configured Ollama endpoint/model
   (no DeepSeek key needed); `deepseek` keeps today's path unchanged.
6. Every LLM call is visible in the log with the target it actually went to (endpoint + model),
   under the existing correlation-id chain and the no-secrets rule.
7. The 208 existing tests stay green (adapted only where DI/constructors/contracts change); the suite
   stays fully offline; a local-vs-DeepSeek latency comparison is measured and recorded.

## Functional requirements

| ID | Requirement | Priority | Acceptance criterion (sketch) |
|---|---|---|---|
| FR-01 | Koin shall be removed completely: no `io.insert-koin` dependency (`koin-ktor`, `koin-core`, `koin-test`), no `install(Koin)`, no `org.koin` import anywhere in `src/`. Dependency injection shall be done with Kodein (`org.kodein.di`), binding configurations and components as singletons, keeping the same object graph as today (weather slice with a `get_weather`-only registry, fueling slice with a `find_fueling`-only registry, blank DeepSeek key yielding the unavailable-agent behavior). | Must | A text search over `build.gradle.kts` and `src/` finds 0 occurrences of `koin` except `org.kodein.di` references; `./gradlew test` is BUILD SUCCESSFUL; `./gradlew run` starts and serves every existing endpoint; the offline DI-graph tests resolve the Kodein graph (same components, no cycle, tool registries unchanged). |
| FR-02 | All simple database work shall go through Kotlin Exposed ORM for **both** databases; `DriverManager`, `PreparedStatement`, `ResultSet` and raw SQL strings shall disappear from `src/main`. The local `mydb2.users` repository shall keep the same behavior (insert with the UNIQUE-conflict retry budget, recent-records select, same JSON row shape); the stage repository shall keep the same lookup behavior (three tables, `lower(fueling_id)` matching, related rows, ordering, timeouts) with identical field coverage. Blocking calls stay inside `Dispatchers.IO` / suspended transactions per `CODE_STYLE.md`. | Must | A text search over `src/main` finds 0 of those JDBC symbols; `GET /weather/history` returns the same shape; a live weather request writes one `users` row exactly as before; a live stage lookup for the verified GUID returns the same values as the previous feature's report; the app starts with both databases absent (no connection at startup) and DB failures still map to `DatabaseUnavailableException` → 503. |
| FR-03 | Stage-database access shall remain read-only under Exposed: only read operations (`select` family) against the mapped tables, no `insert`/`update`/`delete`/`replace`/DDL and no schema management (`SchemaUtils`/`CREATE`/`ALTER`/`DROP`) for the stage connection; table names must not come from user input. | Must | Code review plus the adapted guard test (FR-11/ASM-11) finds 0 write operations in the stage repository; a manual lookup leaves the stage row counts unchanged; no DDL is ever issued. |
| FR-04 | `dotenv-kotlin` shall be removed and replaced by a small internal `.env` reader built on stdlib only, preserving the exact resolution order `application.conf` → environment variable → `.env` file → default (local DB password only) and the ignore-if-missing semantics. The reader shall be unit-testable offline and shall never expose a value in logs. | Must | `build.gradle.kts` has no dotenv dependency and `src/` has no `io.github.cdimascio` import; `DeepseekConfigTest`, `DbConfigTest`, `StageDbConfigTest` pass (adapted only where the API changes) and cover precedence and the missing-file case; running with secrets only in `.env` still resolves them; the app starts with no `.env` present. |
| FR-05 | Both Koog tools shall live in `com.aiturbo.tools` (`GetWeatherTool`, `FindFuelingTool`); no tool class may remain in a domain package. Tool names/descriptions shall continue to come from the JSON resources via the existing fail-fast loader, and tool behavior, registries and log output shall not change. | Must | A search finds no tool class under `weather/` or `fueling/`; the `deepseek-request` `tools=[…]` definitions are byte-identical to before the move; the tool test suites pass with import-only adjustments. |
| FR-06 | `POST /weather` and `POST /fueling` shall accept an optional request field selecting the model provider for that request: `model` with allowed values `local` and `deepseek` (per ASM-01 — trimmed, case-insensitive; absent or blank → `deepseek`; any other non-blank value → 400 without any LLM/tool call). The field selects the provider, not a model id (ids stay configurable). Selection is per request on a running server; response bodies keep their current shape; `GET /time` and `GET /weather/history` are unaffected (ASM-02). | Must | The Postman matrix below behaves exactly as specified (200/400, log shows the provider actually used); a request without the field is indistinguishable from today's behavior; an unknown value returns 400 with a message naming the allowed values and produces no `deepseek-request` line. |
| FR-07 | The `local` option shall route that request's LLM traffic through Koog's Ollama prompt-executor client (`ai.koog:prompt-executor-ollama-client`, transitively present via `koog-agents`) to the configured Ollama endpoint and model (defaults per ASM-12: `http://localhost:11434`, `qwen3:8b`). The same agents, tools, registries and the single logging choke point shall be used, so tool calling and the trace chain work identically to the DeepSeek path. | Must | With Ollama running, `model=local` requests on both endpoints return 200 with a plain-language answer; the chain shows `tools_count=1` with the same tool definitions and the Ollama endpoint/model; the tools execute normally (weather row written / fueling lookup performed). |
| FR-08 | Local-target failure shall degrade gracefully: Ollama stopped, unreachable, or the configured model absent → no crash, no hang, a clear error response (status per ASM-09), the server keeps serving, and other requests (including `model=deepseek`) are unaffected. There shall be no automatic fallback between providers. | Must | With Ollama stopped, a `model=local` request returns the documented status and message within ≤10 s; a following `model=deepseek` request succeeds; the failure is visible in the log without any secret value. |
| FR-09 | The DeepSeek option shall be unchanged: `model=deepseek` (or the field absent) uses the existing OpenAI-compatible client with the configured model id (`deepseek-flash` default, `DEEPSEEK_MODEL` override), and a blank API key keeps today's 503 contract. | Must | With a blank key, a default request on both endpoints returns 503 as before; with a key present, the log shows the DeepSeek endpoint and model id exactly as today. |
| FR-10 | Each LLM round-trip shall be identifiable in the log by the target it actually went to: every `stage=deepseek-request` and `stage=deepseek-response` line shall carry `endpoint=` and `model=` values that distinguish local Ollama from DeepSeek for that request (stage names, correlation id, chain order, 4096-char truncation and the no-secrets rule unchanged — ASM-03). | Must | The two runs from the Postman matrix produce chains that differ in `endpoint`/`model` while keeping `inbound → … → outbound` order under one `req=` id; a secrets search over the captured log finds 0 hits. |
| FR-11 | Existing behavior and tests shall be preserved: all 208 existing `@Test` methods stay (none deleted), adapted only where DI/constructors/contracts change; the suite stays fully offline (fakes, `MockEngine`, frozen clock, `LogCapture` — no real databases, no live LLM, no Ollama, no network); endpoint contracts and status codes are unchanged. All new coverage shall also be offline, and behavior rules testable today (UNIQUE retry, formatting, config precedence, read-only guard) shall remain covered through an equivalent offline seam — no silent coverage loss. | Must | `./gradlew test` BUILD SUCCESSFUL with no Ollama running, no databases and no `.env`; the 30 existing test files are still present; the test count is ≥ 208; `grep` finds no test dependency on a real DB/LLM/Ollama. |
| FR-12 | A latency comparison of the local model versus DeepSeek shall be measured after implementation and recorded in this feature's documentation, following the benchmark checklist below (per endpoint, per provider, median + min/max, method/date/versions). | Must | The recorded benchmark section contains the method, date, machine note, model ids/versions, per-endpoint medians with min/max, and a 2–3 line conclusion; the numbers are reproducible from the documented recipe. |
| FR-13 | qwen3:8b's tool-calling support shall be verified live at the smoke milestone (same pattern as the previous feature's risk R-1) for both tools before the feature is declared done; the outcome shall be recorded. The verification depends on the user-started Ollama service (out of scope for this feature). | Must | One recorded live run per endpoint shows a tool call in the chain (`deepseek-response` with `tool_calls=[…]`, `stage=tool`, final answer); if tool calling fails, it is documented as a blocker with the observed evidence instead of silently changing scope. |
| FR-14 | The README shall be updated where it would otherwise contradict the shipped state (it currently names Koin and plain JDBC); `CLAUDE.md`/`CODE_STYLE.md` already describe the target stack and need no change; this feature's docs (requirements → design → plan → spec/test report) shall be complete and consistent. | Should | A README review finds no statement contradicted by the new behavior (DI framework, DB access, tool packages, new request field). |
| FR-15 | The feature shall be produced through `/feature-design` and implemented through `/feature-implementation`; the review shall use the local agents `code-style-checker`, `reviewer-correctness`, `reviewer-deduplication`, `reviewer-git`. | Should | `01-requirements.md`, `02-design.md`, `03-plan.md` exist and are consistent; the review record references the four agents and their findings. |

### Model-selection contract (behavioral)

| Element | Value |
|---|---|
| Field | `model` |
| Where | JSON body of `POST /weather` and `POST /fueling` (optional) |
| Type | string |
| Allowed values | `local`, `deepseek` — trimmed, case-insensitive (ASM-01) |
| Absent / blank | `deepseek` — today's behavior (ASM-01) |
| Unknown value | 400 `{"error": "…"}` naming the allowed values; no LLM call, no tool call (ASM-01) |
| `local` | Ollama via Koog's Ollama client at `ollama.baseUrl` (default `http://localhost:11434`), model `ollama.model` (default `qwen3:8b`); no DeepSeek API key required (ASM-08, ASM-12) |
| `deepseek` | Existing OpenAI-compatible DeepSeek path (`deepseek.baseUrl`, `deepseek-flash` default) |
| Response | unchanged `{"message","answer"}` — the serving provider is visible in the log, not the response (ASM-14) |
| Other endpoints | `GET /time` keeps the DeepSeek path with no field (ASM-02); `GET /weather/history`, `GET /` have no LLM |

### Postman examples (manual verification)

Prerequisites: the server runs on `http://localhost:8080` (`./gradlew run`); the git-ignored `.env`
contains `DEEPSEEK_API_KEY` (and `STAGE_DB_PASSWORD` for fueling lookups); for the `local` cases the
**user-managed** Ollama service is running with `qwen3:8b` (e.g. `brew services start ollama` or
`ollama serve` — installing/managing Ollama is out of scope); the stage database is reachable.

**Request A — weather via the local model**

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `http://localhost:8080/weather` |
| Headers | `Content-Type: application/json` |
| Body | raw JSON: `{"message": "Какая сейчас погода в Москве?", "model": "local"}` |

**Request B — fueling via the local model** (same request, other endpoint; the GUID is the one
verified live against the stage in the previous feature)

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `http://localhost:8080/fueling` |
| Headers | `Content-Type: application/json` |
| Body | raw JSON: `{"message": "Найди данные по проливу для заказа 61574131-999F-48C9-93AE-3EAA68562177", "model": "local"}` |

**Request C** — the same two bodies with `"model": "deepseek"` and with the field removed, to
compare the chains.

Cases to try (same requests, different bodies):

| Case | Body | Expected |
|---|---|---|
| Local weather | `{"message": "Какая сейчас погода в Москве?", "model": "local"}` | 200 + answer; log lines show the Ollama endpoint and `model=qwen3:8b` |
| Local fueling | example GUID + `"model": "local"` | 200 + summary; `stage=tool`/`stage=db` lines as with DeepSeek |
| Explicit DeepSeek | same bodies + `"model": "deepseek"` | 200; log shows `https://api.deepseek.com/chat/completions` and the DeepSeek model id |
| Field absent (default) | bodies without `model` | Identical behavior to before the feature (DeepSeek) |
| Case-insensitive / padded | `"model": " LOCAL "` | Same as `local` (trimmed, case-insensitive) |
| Unknown value | `"model": "gpt-4"` | 400 with an error naming `local`/`deepseek`; no LLM line in the log |
| Ollama stopped | local case, Ollama not running | Documented status/message (ASM-09) within ≤10 s; server and later DeepSeek requests unaffected |
| `/time` unaffected | `GET /time?location=Moscow` | Unchanged; when the LLM fallback is used, it stays on DeepSeek |

Illustrative local chain (the design chooses exact wording; `endpoint`/`model` must identify the
target, everything else per FR-10):

```
req=ab12cd34 stage=inbound method=POST path=/weather … body={"message":"Какая сейчас погода в Москве?","model":"local"}
req=ab12cd34 stage=deepseek-request endpoint=http://localhost:11434/api/chat model=qwen3:8b tool_choice=- tools_count=1 tools=[…get_weather…] messages=[…]
req=ab12cd34 stage=deepseek-response model=qwen3:8b text="" tool_calls=[{"name":"get_weather","args":"{\"location\":\"Москва\"}"}]
req=ab12cd34 stage=db tool=get_weather saved=true id=…
req=ab12cd34 stage=tool tool=get_weather … is_error=false …
req=ab12cd34 stage=deepseek-request endpoint=http://localhost:11434/api/chat model=qwen3:8b … messages=[…, tool: "…"]
req=ab12cd34 stage=deepseek-response model=qwen3:8b text="Сейчас в Москве …" tool_calls=[]
req=ab12cd34 stage=outbound status=200 body={"message":"…","answer":"…"}
```

### Benchmark checklist (deliverable of FR-12)

1. **Prerequisites.** Server running; Ollama running with `qwen3:8b` (user-managed); `DEEPSEEK_API_KEY`
   configured; stage database reachable for the fueling prompt; the machine otherwise idle; note the
   hardware in one line (CPU/GPU/RAM).
2. **Warm-up.** One request per provider per endpoint, discarded — this removes the Ollama model-load
   cold start and LLM connection setup from the measurement.
3. **Measure.** At least 5 sequential identical requests per provider per endpoint (default 5;
   increase if the spread is large), one provider at a time, same prompt per endpoint (the Postman
   examples above). Record the client-visible latency in milliseconds (Postman "Time", or
   `curl -w '%{time_total}' -o /dev/null`), and note the server-side span (`inbound` → `outbound`)
   where convenient.
4. **Record per run.** Date; endpoint; `model` field value; model id (`deepseek-flash` vs `qwen3:8b`,
   Ollama 0.34.4); whether the tool path was exercised (`tools_count=1`, i.e. 2 LLM round-trips for
   tool flows); latency ms.
5. **Report.** A comparison table local vs DeepSeek per endpoint with **median and min/max**, the
   single-machine/single-user caveat, and a 2–3 line conclusion (which is faster here and by how
   much; note that latency includes the tool-calling round-trips).
6. **Store.** In this feature's docs (e.g. the test report of `docs/features/kodein-exposed-ollama/`);
   update the README only if it ends up making latency claims (default: no README change needed).

### End-to-end acceptance criteria (walkthrough)

1. `./gradlew test` is BUILD SUCCESSFUL, fully offline; the 208 pre-existing tests are still present
   and green, plus the new offline tests.
2. Repository search: 0 Koin, 0 `dotenv-kotlin`, 0 `DriverManager`/`PreparedStatement`/`ResultSet`
   in `src/main`; Kodein and Exposed are the only DI/ORM tools.
3. The app starts with both databases absent and Ollama stopped; `GET /` and `GET /time` work.
4. `POST /weather` with `"model": "local"` (Ollama running) → 200 + answer; the chain shows the
   Ollama endpoint/model and the `get_weather` tool call.
5. `POST /fueling` with `"model": "local"` and the stage-verified GUID → 200 + summary equivalent to
   the DeepSeek run.
6. The same requests without the field behave exactly as before the feature (DeepSeek).
7. `"model": "gpt-4"` → 400 with the allowed values named; no LLM traffic.
8. Ollama stopped + `"model": "local"` → documented status/message within ≤10 s; a subsequent
   DeepSeek request succeeds; the server never hangs.
9. The live qwen3:8b tool-calling verification (FR-13) has been run once per endpoint and recorded.
10. The benchmark table (FR-12) is recorded with method/date/versions/numbers.
11. Review notes from the four local agents exist; the README no longer contradicts the stack or the
    new request field.

## Non-functional requirements

| ID | Category | Measurable target |
|---|---|---|
| NFR-01 | Testability / offline | `./gradlew test` passes with no network access, no local/stage database, no Ollama and no live LLM; all 208 pre-existing tests remain and are adapted only where DI/constructors/contracts change; new tests use fake executors/repositories and never read the real `.env` (config values are injected). |
| NFR-02 | Dependency hygiene | Final Gradle dependency set = current set **minus** `koin-ktor`, `koin-core`, `koin-test`, `dotenv-kotlin` **plus** Kodein-DI (`org.kodein.di`) and Exposed (core + JDBC artifacts); the Ollama client is consumed from `koog-agents` transitively (verified present as `prompt-executor-ollama-client:1.2.0`, runtime scope) and declared explicitly only if compilation requires it (ASM-05, ASM-06); no other new runtime dependency; the PostgreSQL driver (Exposed's driver) and logback stay. |
| NFR-03 | Security | After a full manual run, `DEEPSEEK_API_KEY`, `STAGE_DB_PASSWORD` and the DB password appear 0 times in tracked files, logs and response bodies; the new `.env` reader never logs a value; no config object is passed to the trace log. |
| NFR-04 | Startup independence | The application starts and serves every existing endpoint with the local DB down, the stage DB unreachable and Ollama stopped; 0 database connections are opened at startup; a missing `.env` does not prevent startup. |
| NFR-05 | Observability | For one request, the log keeps a single correlation id and the documented stage order; every LLM line carries `endpoint` and `model` identifying the actual target; body-like values stay truncated at 4096 chars; 0 secrets in the chain. |
| NFR-06 | Latency / robustness (local path) | An unreachable Ollama yields a definite error response within ≤10 s (connect budget), never a hang; generation latency itself is model-bound and is reported by the benchmark (no artificial cap required); the server remains responsive to other requests. |
| NFR-07 | Compatibility | The stack stays Kotlin 2.3.10 / Ktor 3.3.3 / Koog 1.2.0 / Gradle 8.14.1 / JVM 17; all HTTP contracts and status-code semantics (200/400/404/500/503) are unchanged from the documented behavior. |
| NFR-08 | Build/test discipline | Build and tests are read as a verdict only (`BUILD SUCCESSFUL`/`BUILD FAILED`, first 20–40 lines on failure); server logs only via `grep` by `req=`/`stage=` or the log tail — no full dumps into agent context (`CLAUDE.md`). |
| NFR-09 | Data safety | The stage database receives read-only traffic only: 0 write/DDL statements in the stage repository; the read-only guard test is adapted (never deleted) and passes; row counts of the touched tables are unchanged after a manual lookup. |
| NFR-10 | Reproducibility of the benchmark | The recorded numbers carry method, date, machine note and versions (Ollama 0.34.4, `qwen3:8b`, `deepseek-flash`) and can be reproduced from the checklist in this document. |

## Out of scope

- **Installing, upgrading, starting, stopping or managing Ollama.** The user manages it themselves
  (install exists at `/opt/homebrew/opt/ollama`, `qwen3:8b` downloaded, service currently stopped);
  the feature only targets the configured endpoint. A live smoke run depends on the user starting it.
- **Model management for the local provider** (pulling/removing models, choosing other tags,
  quantization, GPU/CPU tuning, prompt tuning for `qwen3:8b`).
- **Automatic fallback or routing between providers.** Selection is explicit per request; no retry
  chain, no cost-based or health-based routing, no local→DeepSeek fallback (and none in the other
  direction).
- **Applying the model field to `GET /time`** (stays DeepSeek-only, ASM-02); `/weather/history` and
  `/` have no LLM and are unchanged.
- **Changing the default provider or model.** Every request without the field keeps DeepSeek and the
  `deepseek-flash` default; `DEEPSEEK_MODEL` remains the override.
- **New endpoints, UI or Postman collections as deliverables.** The Postman examples are
  documentation.
- **Writes to the stage database, and schema changes/migrations via Exposed.** Both schemas already
  exist; no `SchemaUtils`/DDL, no data migration.
- **Rewriting business logic, prompts, tool JSON definitions, fuel-report rendering, GUID validation
  or the trace semantics** beyond what the DI/ORM migration and the model field force.
- **Replacing logback** (stays — infrastructure logging per `CODE_STYLE.md`), Ktor, Koog or
  kotlinx-serialization, and **removing the PostgreSQL JDBC driver** (Exposed's database driver).
- **An automated benchmark harness or CI performance gate.** The benchmark is a documented manual
  measurement (FR-12).

## Open questions

| ID | Question | Why it matters | Default assumption (ASM-xx) |
|---|---|---|---|
| OQ-01 | What exactly is the model-selection field — name, allowed values, behavior on absent/blank/unknown/mixed-case input — and does it select the provider or a model id? | Defines the request contract for both endpoints, the Postman recipe, the 400 behavior and the tests; changing it later means rewriting route tests and docs. | ASM-01 (affects FR-06): field `model`, string, allowed `local` \| `deepseek`; trimmed and case-insensitive; absent **or blank** → `deepseek`; any other non-blank value → 400 naming the allowed values, no LLM call; it selects the **provider**, not a model id (ids stay in config). |
| OQ-02 | Should `GET /time` (whose fallback calls the LLM) also accept a model selection, and what about the other endpoints? | `/time` shares the executor and would silently stay on DeepSeek; a field there would change an existing GET contract and its tests. | ASM-02: no — `/time` keeps the DeepSeek path with no request field; `/weather/history` and `/` have no LLM and are untouched. If the user wants `/time` selectable later, it is a separate change. |
| OQ-03 | Should the log stage names `deepseek-request`/`deepseek-response` change for local Ollama calls (they are now provider-generic LLM stages)? | The stage names are part of the documented chain, the README examples and several tests (including frozen assertions); the misleading name would otherwise persist for local calls. | ASM-03: keep the stage names unchanged (chain contract, tests, README stay valid); the `endpoint=`/`model=` fields identify the actual target, and an extra provider marker is the design's option. |
| OQ-04 | Which existing pieces count as "the stack" — does `dotenv-kotlin` go, does logback stay, and what about kotlinx-serialization / the JDBC driver? | The user asked for the stack to be "only Kotlin, Ktor, Koog, Kodein, Exposed ORM"; `CODE_STYLE.md` names logback as infrastructure, but dotenv is an unlisted third-party helper. | ASM-04: logback stays (infrastructure logging, per `CODE_STYLE.md`); `dotenv-kotlin` is replaced by a small internal stdlib `.env` reader (same precedence, missing file ignored, no interpolation assumed; supports `KEY=VALUE`, `#` comments, blank lines, optional surrounding quotes); Ktor/Koog/kotlinx-serialization stay; the PostgreSQL driver stays as Exposed's driver. |
| OQ-05 | Which Kodein artifact(s) and version, and is a Kodein–Ktor integration artifact needed for Ktor 3.3.3? | Wrong or incompatible artifacts break the build; the Kodein Ktor integration historically targets older Ktor versions, while a plain container needs no integration at all. | ASM-05: use core `org.kodein.di:kodein-di` (latest stable release whose metadata is readable by Kotlin 2.3.10; exact version verified at implementation against Maven Central); no Kodein–Ktor integration artifact unless it is proven compatible with Ktor 3.3.3 — default is to build the container in `Application.module` and pass/resolve beans without the plugin; no new test dependency. |
| OQ-06 | Which Exposed artifacts and version, and how is the JDBC connection/transaction managed without opening connections at startup? | Exposed needs `exposed-core` + `exposed-jdbc` (and a driver); version compatibility with Kotlin 2.3.10/JVM 17 and the "app starts with DBs absent" rule must hold. | ASM-06: `exposed-core` + `exposed-jdbc` at a stable version compatible with Kotlin 2.3.10/JVM 17, pinned (extras such as `exposed-json` only if strictly needed and justified); connections stay lazy per call (transaction blocks inside `Dispatchers.IO`/suspended transactions); no DDL/schema management; the existing PostgreSQL driver is the JDBC driver. |
| OQ-07 | How are the two executors wired so a single running server can serve both providers per request (two bound executors, a resolver, per-request selection)? | It is the load-bearing mechanism for FR-06/FR-07 and affects startup (must not need a live LLM) and the DI modules' shape. | ASM-07: the wiring approach is the design's choice; requirements fix only the observable contract — per-request selection on a running server, no restart, default `deepseek`, both paths built without a live LLM at startup. |
| OQ-08 | Must a `model=local` request work when `DEEPSEEK_API_KEY` is blank (Ollama needs no key)? | Today a blank key turns the agents into "unavailable" stubs (503) — keeping that for the local path would make the feature unusable without a DeepSeek account. | ASM-08: yes — `model=local` works without the DeepSeek key; `model=deepseek`/absent keeps the existing 503-when-blank contract; startup must not require the key for the local path. |
| OQ-09 | What is the exact failure contract when the local target is unavailable (status, body, budget), and is there a fallback? | Defines FR-08's observable behavior and the Postman "Ollama stopped" case; an accidental fallback would hide the misconfiguration and change the benchmark meaning. | ASM-09: HTTP 503 with a clear plain-language message (consistent with the existing "agent/LLM unavailable" mapping), reached within ≤10 s, no crash/hang; no automatic fallback to DeepSeek; the design may align the wording with the existing error bodies. |
| OQ-10 | Does `qwen3:8b` (via Ollama) actually support tool calling with the Koog client, on both tools? | The whole local path depends on the model emitting tool calls; the previous feature's analogous assumption (R-1) was load-bearing and had to be smoke-verified. | ASM-10 (risky): it works; mandatory live verification at the smoke milestone (FR-13) once the user starts Ollama; if it does not, this is raised as a blocker with the observed evidence (options then include another local tool-capable model tag — a user decision, not a silent scope change). |
| OQ-11 | How is the stage read-only guarantee verified once the SQL strings are gone (the current `StageReadOnlyGuardTest` greps JDBC SQL)? | The read-only property is a hard constraint; the guard test mechanism must not be lost in the migration. | ASM-11: `StageReadOnlyGuardTest` is adapted to the Exposed mechanism (assert the stage repository uses only read operations / no write DSL or raw SQL) and kept — never deleted; FR-03/NFR-09 behavior is unchanged. |
| OQ-12 | What are the Ollama configuration keys and defaults, and which environment variables override them? | Needed for FR-07 and for the user's environment (service currently stopped; user may change host/model later). | ASM-12: `ollama.baseUrl` (default `http://localhost:11434`) and `ollama.model` (default `qwen3:8b`) in `application.conf`, with the project's existing override style (`OLLAMA_BASE_URL`, `OLLAMA_MODEL`); no secret involved. |
| OQ-13 | What exactly is the benchmark protocol and where do the numbers live? | FR-12 is a user-visible deliverable; without a fixed protocol the comparison is not reproducible or comparable. | ASM-13: 1 warm-up + ≥5 measured sequential requests per provider per endpoint (same prompt per endpoint), client-visible latency in ms, median + min/max, recorded in this feature's docs with date, machine note and versions; single-machine caveat stated. |
| OQ-14 | Should the response body tell which model/provider served the request (e.g. echo `model`)? | Would change the existing response contract and the route tests; the user asked for the information in the **logs**, not the response. | ASM-14: no — response shape stays `{"message","answer"}`; the serving target is observable in the trace chain only. |

## Glossary

- **Stack (fixed)** — Kotlin, Ktor, Koog, Kodein, Exposed ORM only; logback is infrastructure
  logging; secrets live only in the git-ignored `.env` (`CODE_STYLE.md`).
- **Koin / Kodein** — the current DI framework (Koin, to be removed) and the mandated one
  (Kodein, `org.kodein.di`, to be introduced), with the same object graph.
- **Exposed ORM** — the Kotlin SQL framework the repositories must use (`Table` objects, DSL
  queries); raw JDBC (`DriverManager`, `PreparedStatement`, `ResultSet`) is forbidden in code.
- **Local provider / `local`** — the request option that routes LLM traffic to Ollama, the locally
  running model server (user-managed; `http://localhost:11434`, model `qwen3:8b`).
- **Ollama** — the local LLM runtime installed by the user via brew (v0.34.4, runtime at
  `/opt/homebrew/opt/ollama`); the service is currently stopped; installing/managing it is out of
  scope.
- **`qwen3:8b`** — the local model tag the `local` option targets (the model must support tool
  calling — the load-bearing assumption, ASM-10).
- **DeepSeek provider / `deepseek`** — the existing OpenAI-compatible API path (default), model id
  `deepseek-flash` (DeepSeek-V4.1-Flash) overridable with `DEEPSEEK_MODEL`.
- **Koog Ollama client** — `ai.koog:prompt-executor-ollama-client` (1.2.0, transitively present via
  `koog-agents`) — the Koog prompt-executor implementation for Ollama used by the `local` path.
- **Prompt executor / choke point** — Koog's `PromptExecutor`; `LoggingPromptExecutor` is the single
  decorator every LLM call passes through, writing the `deepseek-request`/`deepseek-response` lines.
- **Model-selection field** — the new optional `model` field in the `POST /weather` and
  `POST /fueling` JSON body (`local` | `deepseek`, default `deepseek`).
- **Tools package** — `com.aiturbo.tools`, the single home of all Koog tools (`GetWeatherTool`,
  `FindFuelingTool`, plus `ToolSpec`/`ToolJsonRenderer`); tool JSON resources under
  `src/main/resources/tools/` remain the source of truth for names/descriptions.
- **Trace chain / `req=`** — the ordered log stages `inbound`, `deepseek-request`,
  `deepseek-response`, `tool`, `db`, `outbound` on logger `com.aiturbo.trace`, tied by one
  correlation id per request.
- **Offline tests** — the suite strategy (fakes, `MockEngine`, frozen clock, `LogCapture`) with no
  real databases, no live LLM, no Ollama and no network; currently 208 `@Test` methods in 30 files.
- **Smoke milestone / R-1 pattern** — the manual live-verification step used by the previous feature
  for a load-bearing model assumption; here applied to `qwen3:8b` tool calling (FR-13) and to the
  local/DeepSeek request paths.
- **Benchmark (latency comparison)** — the recorded local-vs-DeepSeek measurement per endpoint
  (median + min/max) produced by the checklist in this document (FR-12).
- **`.env` reader** — the planned internal stdlib replacement for `dotenv-kotlin`, preserving the
  config → environment → `.env` → default precedence and the ignore-if-missing behavior.
