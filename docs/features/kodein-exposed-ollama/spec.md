# Specification: Kodein + Exposed + Local Model Routing

- **Slug:** kodein-exposed-ollama
- **Date:** 2026-09-28
- **Team:** feature-design (analyst → architect → planner agents)
- **Status:** ready for implementation

---

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

---

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

---

# Kodein + Exposed Stack and a Selectable Local (Ollama) Model — Implementation Plan

Source of truth: `01-requirements.md` (FR-01…FR-15, NFR-01…NFR-10, ASM-01…ASM-14) and
`02-design.md` (C1–C10, D1–D20, T1–T10, R1–R10). This plan re-designs nothing; where the design and
the pipeline directive differ (Kodein artifact coordinate), the difference is recorded as an
execution note and a risk, not silently resolved.

**Plan-level facts verified in the repository (2026-09-28):** 208 `@Test` methods across exactly 30
test files; Koin references in 11 files (7 tests, 4 main sources); JDBC references in
`Application.kt` + `StageReadOnlyGuardTest` + `FuelingModulesTest` + the two `Jdbc*Repository`
files; both tool classes referenced from 10 files; dotenv referenced from `build.gradle.kts`,
`Application.kt` and three config tests (the config tests use only the pure `resolve*` functions,
so they need no adaptation).

**Execution discipline (applies to every batch, per CLAUDE.md / NFR-08):** the only build command is
`./gradlew test`; read only `BUILD SUCCESSFUL` / `BUILD FAILED` (on failure, the first 20–40 lines).
Never copy build output or server logs into context; server logs only via `grep` by `req=`/`stage=`
or a tail of `logs/ai-turbo.log`. No secrets in any artifact. Every batch below ends with this
verdict check; a batch's intermediate tasks need not each end green, and any task ending with a
verdict gate is the batch's single green checkpoint. Tasks that must land in one change-set (file
deletion paired with a test that scans the deleted file; top-level declarations that would collide)
are flagged explicitly.

---

## Task list

Size legend: S ≤ ~2 h, M ≤ 1 developer-day. All tasks are ≤ 1 day. "FRs / design" lists the
requirements implemented and the design components/sections referenced.

| ID | Title | Depends on | Size | Files to touch | Description | FRs / design |
|---|---|---|---|---|---|---|
| T-01 | Add Kodein-DI and Exposed dependencies | — | S | `build.gradle.kts` | Add `org.kodein.di:kodein-di-jvm:7.32.0` (JVM variant; see execution note E-01), `org.jetbrains.exposed:exposed-core:1.5.0`, `org.jetbrains.exposed:exposed-jdbc:1.5.0`. No removals, no other dependency. | NFR-02; design D2/D5, R3 |
| T-02 | Kodein container skeleton + module conversion | T-01 | M | `plugins/Di.kt` (new), `Application.kt` | Create `installDi`/`Application.di`/`Route.di` (C1). Convert `appModules(deepseek, db, weather)` and `fuelingModule(stageDb, apiKeyConfigured)` from Koin `Module` to `DI.Module` with the same object graph, tags instead of qualifiers, eager singletons for the two tool specs; `Application.module` builds one `DI` container via `installDi`; same `overrideModules` shape (now `List<DI.Module>`). `install(Koin)` removed. Signature still without `ollama` — that parameter arrives in T-20. | FR-01; design C1, D1–D4 |
| T-03 | Routes resolve via Kodein lazily | T-02 | S | `plugins/Routing.kt`, `plugins/WeatherRouting.kt`, `plugins/FuelingRouting.kt` | Replace `by inject` with `by di.instance()`; keep lazy resolution inside route builders so route registration with a partial graph (frozen route tests) still works. No handler/status-code change. | FR-01; design C1, D4 |
| T-04 | Adapt the 7 Koin-building test files to Kodein | T-02, T-03 | M | `AppModulesTest.kt`, `ApplicationTest.kt`, `WeatherRoutesTest.kt`, `FuelingRoutesTest.kt`, `FuelingModulesTest.kt`, `TraceChainIntegrationTest.kt`, `FuelingChainIntegrationTest.kt` | Koin `koinApplication`/`module { }`/`get<X>()` → `DI { import(DI.Module("…") { … }) }`/`instance()`; `createEagerInstances()` disappears; `application { module(overrideModules = listOf(DI.Module(...))) }`; `PromptExecutor is LoggingPromptExecutor` and `StageFuelingRepository is JdbcStageFuelingRepository` assertions updated only where the type name changes (the stage one changes again in T-11 — update to `ExposedStageFuelingRepository` there). No assertion deleted. | FR-01, FR-11; design T8 |
| T-05 | Remove Koin dependencies + batch verdict | T-04 | S | `build.gradle.kts` | Remove `koin-ktor`, `koin-core`, `koin-test`; run `./gradlew test` (verdict only) and the grep sweep. | FR-01; design T8, R9 |
| T-06 | `EnvFile` reader + `loadDotenv` swap + tests | T-02 | M | `config/EnvFile.kt` (new), `EnvFileTest.kt` (new), `Application.kt` (helper only) | Implement `EnvFile.parse(text)`/`EnvFile.load(directory, fileName)` per design C8 (rules fixed: split on first `=`, skip blanks/whole-line `#`, trim, strip one matching quote pair, no interpolation, empty value = `""`, duplicate key = last, line without `=` ignored, missing file = empty reader). Non-data class, no logging. `loadDotenv(): EnvFile`. `resolveApiKey`/`resolveDbPassword`/`resolveStageDbPassword` and their call sites unchanged. | FR-04; design C8, D10, T1 |
| T-07 | Remove dotenv-kotlin + batch verdict | T-05, T-06 | S | `build.gradle.kts` | Remove `io.github.cdimascio:dotenv-kotlin`; `./gradlew test` verdict + grep `io.github.cdimascio`. | FR-04; design C8 |
| T-08 | `pgDataSource` (no pool, no connection at construction) | T-01 | S | `db/PgDataSource.kt` (new) | `internal fun pgDataSource(jdbcUrl, user, password, applicationName, timeoutSeconds?)` over `PGSimpleDataSource`; sets connect/socket/login timeouts only when `timeoutSeconds != null`; `AI_TURBO_APPLICATION_NAME = "ai-turbo"`. No `DriverManager`. | FR-02, NFR-04; design C7, D6 |
| T-09 | Exposed weather repository (+ delete JDBC weather repo, switch DI binding) | T-08, T-02 | M | `db/UsersTable.kt` (new), `db/ExposedWeatherRecordRepository.kt` (new), delete `db/JdbcWeatherRecordRepository.kt`, `Application.kt` (binding), `WeatherRecordRepositoryTest.kt` | `UsersTable` per C5 (`integer("id").autoIncrement()`, `varchar("data",255)`, nullable `time`/`created_at`, PK). Repository: `insertReturning(listOf(id))` inside one transaction per retry attempt via the unchanged `insertWithRetry(start, MAX_INSERT_ATTEMPTS, insert)`; unset `id`/`created_at` omitted (DB defaults); unique violation detected by walking the cause chain for `SQLException` sqlState `23505`; exhausted → `-1` + existing warning text; `recent(limit)` same ordering/limit/DTO; failures → `DatabaseUnavailableException`; `DATA_FORMAT`/`TIME_FORMAT`/`insertWithRetry`/`MAX_INSERT_ATTEMPTS` keep names, package, signatures. Test adaptation: wrapped-violation case added; direct cases unchanged; no `@Test` removed. Must land with the deletion (duplicate top-level declarations otherwise). | FR-02, FR-11, NFR-09; design C5, D8/D9 |
| T-10 | Stage table mappings | T-01 | M | `db/StageTables.kt` (new) | `FuelingsColumns` base + `FuelingsTable`/`FuelingsArchiveTable`/`FuelingsDropTable`, `PartnerFuelingEventsTable`, `FuelingFeedbackTable`, `BelkaTokensTable` with the exact column sets and types of design C6 (`text` for ids/strings/jsonb, `decimal(38,18)` nominal for numeric/epoch, `vendor_transaction_date` as `text`). No DDL, no write ops in the file. | FR-02, FR-03; design C6, D8 |
| T-11 | Exposed stage repository (+ delete JDBC stage repo, switch DI binding) | T-10, T-08 | M | `db/ExposedStageFuelingRepository.kt` (new), delete `db/JdbcStageFuelingRepository.kt`, `Application.kt` (stage binding), `FuelingModulesTest.kt` (type assertion) | One `transaction` per lookup; three main lookups via enum-derived table map with `lowerCase() eq fuelingId` (bound value, no added ORDER BY); related rows with `DESC NULLS LAST`; same DTO mapping incl. `epochMillisOrNull(BigDecimal?)` and `vendorTransactionDate` as text; `ExposedSQLException`/`SQLException` → `DatabaseUnavailableException`; no `insert`/`update`/`delete`/`replace`/`SchemaUtils`/raw SQL string. Must land together with T-12 (guard test scans this file's path). | FR-02, FR-03, NFR-09; design C6, D7/D8 |
| T-12 | Adapt `StageReadOnlyGuardTest` to the Exposed mechanism | T-11 (same change-set) | S | `StageReadOnlyGuardTest.kt` | Scan the Exposed stage sources (`ExposedStageFuelingRepository.kt`, `StageTables.kt`, `StageDbConfig.kt`, `StageFuelingRepository.kt`) for the Exposed write DSL and DDL tokens listed in design T9; assert the case-insensitive `lowerCase()` predicate and enum-derived tables remain; repoint the "weather repository is outside the scope" test from the deleted `JdbcWeatherRecordRepository.kt` to `ExposedWeatherRecordRepository.kt` (by-design write = `insertReturning`). Test is adapted, never deleted. | FR-03, FR-11, NFR-09; design T9, D7, ASM-11 |
| T-13 | Exposed batch verdict + JDBC-symbol sweep | T-09, T-12 | S | none (verification) | `./gradlew test` verdict; greps over `src/main`: 0 `DriverManager`/`PreparedStatement`/`ResultSet`; no `Jdbc*Repository.kt` file remains; stage guard tests green. | FR-02, FR-11; design R9 |
| T-14 | Move both tools to `com.aiturbo.tools` | T-13 | S | create `tools/GetWeatherTool.kt`, `tools/FindFuelingTool.kt`; delete `weather/GetWeatherTool.kt`, `fueling/FindFuelingTool.kt`; update imports in `Application.kt`, `GetWeatherToolTest.kt`, `FindFuelingToolTest.kt`, `KoogWeatherAgentTest.kt`, `KoogFuelingAgentTest.kt`, `AppModulesTest.kt`, `FuelingModulesTest.kt`, `TraceChainIntegrationTest.kt`, `FuelingChainIntegrationTest.kt` | Package line only; class names, constructors, `@LLMDescription` arguments, JSON resources and behaviour unchanged; `deepseek-request tools=[…]` definitions byte-identical. | FR-05; design C9, D11, T10 |
| T-15 | Verify the Koog 1.2.0 Ollama client API against the resolved jar | — | S | none (verification note recorded in the implementation notes/`spec.md`) | Inspect the resolved artifact in the Gradle cache (`find ~/.gradle/caches -name 'prompt-executor-ollama-client*.jar'`, `jar tf`/`javap`) and the compile classpath; record exact FQCN, constructor, whether a `KoogHttpClient`/timeout config must be supplied for the ≤10 s budget, and whether the artifact is usable on the compile classpath transitively. Choose between the two candidate forms (R2) before any `OllamaLlm.kt` is written. | FR-07, NFR-06; design C2, R2 |
| T-16 | Move DeepSeek LLM factory to `com.aiturbo.llm`; extract `WeatherUnavailableException` | T-14 | S | create `llm/DeepSeekLlm.kt`, `weather/WeatherUnavailableException.kt`; delete `weather/DeepSeekKoogLlm.kt`; update imports in `Application.kt`, `plugins/Routing.kt`, `RawDeepSeekCallTest.kt`, `LoggingPromptExecutorTest.kt`, `LlmTimeZoneResolverTest.kt`, `KoogWeatherAgentTest.kt`, `KoogFuelingAgentTest.kt`, `TraceChainIntegrationTest.kt`, `FuelingChainIntegrationTest.kt` | `deepseekModel`/`deepseekPromptExecutor` moved verbatim; exception class same package/name so `Routing.kt`'s 503 mapping is untouched. No behaviour change. | FR-09; design C2, D16 |
| T-17 | `OllamaConfig` + `ollama.*` config + `ollamaModel`/`ollamaPromptExecutor` + tests | T-15, T-16 | M | `OllamaConfig.kt` (new), `src/main/resources/application.conf`, `llm/OllamaLlm.kt` (new), `OllamaConfigTest.kt` (new) | `OllamaConfig` with defaults `http://localhost:11434` / `qwen3:8b`, `from(config)`, `chatEndpoint`; `application.conf` `ollama { baseUrl = ${?OLLAMA_BASE_URL}, model = ${?OLLAMA_MODEL} }`; factory per design C2 with the verified constructor and explicit ≤10 s timeouts if the API allows; capabilities `Completion`, `Temperature`, `Tools`; no I/O at construction. | FR-07, FR-08, NFR-06; design C2, D19, T2, ASM-12 |
| T-18 | `LlmTarget` + `LlmTargetContext` + `currentLlmTarget()` + tests | T-16 | S | `llm/LlmTarget.kt` (new), `LlmTargetTest.kt` (new) | Exactly design C3: `fromRequest` (null/blank → DEEPSEEK, `local`/`deepseek` trimmed+case-insensitive, unknown non-blank → null), `ALLOWED_VALUES = "local, deepseek"`, coroutine-context element, default DEEPSEEK when no element. | FR-06; design C3, D12/D13, T3, ASM-01 |
| T-19 | `ProviderRoutingPromptExecutor` + tests | T-17, T-18 | M | `llm/ProviderRoutingPromptExecutor.kt` (new), `ProviderRoutingPromptExecutorTest.kt` (new) | Mirror `LoggingPromptExecutor`'s overload set (`execute(LLModel)`, `execute(ResolvedModel)`, `executeStreaming`, `moderate`, `close`) per C4; LOCAL → local delegate with the substituted Ollama model; DEEPSEEK → DeepSeek delegate with the passed model; blank key guard `WeatherUnavailableException("DeepSeek API key is not configured")` before any delegate/log line; `close()` closes both; streaming reads the element at collection time. | FR-06, FR-07, FR-08, FR-09; design C4, D13/D14/D15/D16, T4, ASM-07/08/09 |
| T-20 | DI wiring of both providers + routing executor | T-19 | M | `Application.kt`, `AppModulesTest.kt` | `appModules` gains the `ollama: OllamaConfig` parameter; tagged `LoggingPromptExecutor` bindings with per-provider endpoint labels (`${deepseek.baseUrl.trimEnd('/')}/chat/completions`, `ollama.chatEndpoint`); unnamed `PromptExecutor` binding = `ProviderRoutingPromptExecutor(deepseek, local, ollamaModel(cfg.model), deepseekConfigured = apiKey.isNotBlank())`; `WeatherAgent` unconditional; `TimeZoneResolver` unchanged in shape (routing executor + DeepSeek model); `fuelingModule` keeps its `apiKeyConfigured` agent guard; `Application.module` passes `OllamaConfig.from`. AppModulesTest asserts the routing type, the endpoint labels and the tags. | FR-01, FR-07, FR-09, FR-10; design C1/C4, T8 |
| T-21 | Request `model` field, 400 validation, per-request context wrap + route tests | T-20 | M | `plugins/WeatherRouting.kt`, `plugins/FuelingRouting.kt`, `WeatherRoutesTest.kt`, `FuelingRoutesTest.kt` | DTOs gain `@EncodeDefault(NEVER) val model: String? = null`; `LlmTarget.fromRequest` validation after the blank-message check with `400 {"error":"Field 'model' must be one of: local, deepseek"}` and no LLM/tool call; agent call wrapped in `withContext(trace + LlmTargetContext(target))`. Tests: unknown → 400 (agent never called, no `stage=deepseek` line), padded/case `" LOCAL "` → LOCAL observed by the fake agent via `currentLlmTarget()`, absent/blank → DEEPSEEK, frozen inbound-body assertion is the `@EncodeDefault(NEVER)` canary. | FR-06, FR-10; design API design, D12, T5/T6, ASM-01/02/03, R6 |
| T-22 | Engineering-complete sweep: offline verdict + stack hygiene | T-21 | S | none (verification) | `./gradlew test` verdict; greps: 0 `io.insert-koin`/`org.koin`/`install(Koin)`, 0 `io.github.cdimascio`, 0 JDBC symbols in `src/main`; dependency set = pre-feature set − koin-*/dotenv + kodein-di + exposed-core/jdbc (Ollama client still transitive); test files still 30, test count ≥ 208 plus new tests. | FR-01…FR-11, NFR-02; design R9 |
| T-23 | README and docs consistency | T-22 | S | `README.md` | Stack line (Kodein + Exposed, no Koin/plain JDBC), tools package, request-field matrix (local/deepseek/absent/blank/padded/unknown), `ollama.*` keys and `OLLAMA_*` overrides, one example chain per provider, secrets rules unchanged. No latency claims in the README. Feature docs (requirements→design→plan→spec/test report) complete and consistent. | FR-14, FR-15; design C10, D20 |
| T-24 | Live smoke run incl. FR-13 tool-calling verdict | T-23 | M | none; evidence into `docs/features/kodein-exposed-ollama/04-test-report.md` | Execute smoke checklist MS-01…MS-14 (below). Local-model steps run only if the Ollama probe answers; otherwise recorded BLOCKED-ON-USER with the exact instructions from MS-01. FR-13: one recorded run per endpoint showing a tool call in the chain; failure = blocker with observed evidence, no scope change. | FR-07, FR-08, FR-09, FR-10, FR-13; design R1, F1–F6 |
| T-25 | Latency benchmark (local vs DeepSeek) | T-24 | M | none; benchmark table into `docs/features/kodein-exposed-ollama/04-test-report.md` | FR-12/ASM-13 protocol (unchanged from the requirements): 1 warm-up + ≥5 measured sequential requests per provider per endpoint, same prompt per endpoint, median + min/max, date/machine/versions, single-machine caveat, 2–3 line conclusion. Commands in the benchmark section below. | FR-12, NFR-10; design NFR-10, D20 |

**Execution notes (recorded, not re-designed).**

- E-01 (Kodein coordinate): the design (D2) names `org.kodein.di:kodein-di:7.32.0`; the pipeline
  directive names `org.kodein.di:kodein-di-jvm:7.32.0`. Primary: the JVM variant coordinate. If
  Gradle cannot resolve it, fall back to the KMP root at the same version and record which was used
  (risk R-11). Never 7.33.0 (Kotlin 2.4 metadata).
- E-02 (intermediate signatures): `appModules` keeps today's 3-parameter signature through batches
  2–4; the `ollama` parameter and the routing-executor binding are introduced exactly once, in
  T-20, so no intermediate state references `OllamaConfig` before T-17.
- E-03 (same change-sets): T-09 (create Exposed + delete `JdbcWeatherRecordRepository.kt`) and
  T-11 + T-12 (create Exposed stage repo + delete `JdbcStageFuelingRepository.kt` + repoint the
  guard test) must land as one change-set each — duplicate top-level declarations and file-path
  scans otherwise break compilation/tests.
- E-04 (Ollama client artifact): it stays transitive per NFR-02 and the pipeline directive; if T-15
  finds it is not on the compile classpath, this is escalated as a blocker with the evidence before
  any explicit dependency is added.
- E-05 (smoke interpretation): "the smoke starts it if it is running" is executed as a probe only —
  the feature never starts/stops Ollama (out of scope); a not-running service yields
  BLOCKED-ON-USER records for the local-model steps, with the exact user instructions.

---

## Work order and milestones

The order follows design R-9: dependencies → DI + EnvFile → Exposed → tools move → model
field/Ollama path → docs → live smoke + benchmark. Each batch ends with exactly one
`./gradlew test` verdict check (CLAUDE.md discipline, NFR-08).

### Batch 1 — Dependencies on board
Tasks: T-01.
Parallel: none.
**Exit gate (M-01):** `./gradlew test` BUILD SUCCESSFUL with the three new dependencies present;
208 tests still green; only the expected `exposed 1.5.0 → kotlin-stdlib 2.3.20` resolution warning
(if the build fails, apply the R-03 step-down before continuing).

### Batch 2 — DI migration to Kodein
Tasks: T-02 → T-03 → T-04 → T-05 (serial; `Application.kt` and the test files overlap).
**Exit gate (M-02):** `./gradlew test` BUILD SUCCESSFUL; grep over `build.gradle.kts` and `src/`
finds 0 `io.insert-koin`, 0 `org.koin`, 0 `install(Koin)`; the 7 previously-Koin test files build
Kodein containers; the DI-graph assertions (same object graph, `get_weather`-only and
`find_fueling`-only registries, blank-key unavailable agent) still hold.

### Batch 3 — EnvFile (dotenv replacement)
Tasks: T-06 → T-07. T-06 may start in parallel with T-03/T-04 (disjoint files except
`Application.kt`, which T-02 has already finished by then); T-07 serializes on `build.gradle.kts`
after T-05.
**Exit gate (M-03):** `./gradlew test` BUILD SUCCESSFUL; grep finds 0 `io.github.cdimascio`;
`EnvFileTest` green; `DeepseekConfigTest`/`DbConfigTest`/`StageDbConfigTest` still green without
semantic changes (their `resolve*` seams are untouched).

### Batch 4 — Exposed repositories
Tasks: T-08; then T-09 and T-10 in parallel (disjoint files); then T-11 + T-12 as one change-set
(E-03); then T-13.
**Exit gate (M-04):** `./gradlew test` BUILD SUCCESSFUL; 0 `DriverManager`/`PreparedStatement`/
`ResultSet` in `src/main`; no `Jdbc*Repository.kt` file remains; adapted `StageReadOnlyGuardTest`
green; `WeatherRecordRepositoryTest` green (including the wrapped-violation case).

### Batch 5 — Tools move
Tasks: T-14.
**Exit gate (M-05):** `./gradlew test` BUILD SUCCESSFUL; no tool class under `weather/` or
`fueling/`; tool tests green with import-only adjustments.

### Batch 6 — Model field + local (Ollama) provider
Tasks: T-15 (can start immediately after T-01, it is offline jar inspection; it must complete
before T-17), T-16 → T-17 → T-18 → T-19 → T-20 → T-21 → T-22.
**Exit gate (M-06):** `./gradlew test` BUILD SUCCESSFUL; test count ≥ 208 plus the new suites
(`EnvFileTest`, `OllamaConfigTest`, `LlmTargetTest`, `ProviderRoutingPromptExecutorTest`, route
additions); `LlmTarget`/routing/route tests green; `@EncodeDefault(NEVER)` canary green (R-06
fallback only with the failing test as evidence); dependency set within the allowed list.

### Batch 7 — Documentation
Tasks: T-23.
**Exit gate (M-07):** README contains no statement contradicted by the shipped state; feature docs
consistent.

### Batch 8 — Live smoke and benchmark (final milestone)
Tasks: T-24 → T-25.
**Exit gate (M-08):** smoke checklist MS-01…MS-14 executed and recorded; FR-13 verdict recorded
per endpoint (or local steps BLOCKED-ON-USER with the exact instructions); benchmark table
recorded in `04-test-report.md` with method/date/machine/versions/median/min-max/conclusion.

### Milestones (summary with verifiable exits)

| ID | Milestone | Reached after | Verifiable exit |
|---|---|---|---|
| M-01 | Dependency baseline | Batch 1 | `./gradlew test` = BUILD SUCCESSFUL; 208 tests; new deps resolve |
| M-02 | Kodein DI complete, Koin gone | Batch 2 | verdict + 0 Koin references (grep) + DI-graph tests green |
| M-03 | `.env` without dotenv-kotlin | Batch 3 | verdict + 0 `io.github.cdimascio` + EnvFileTest green |
| M-04 | Both repositories on Exposed | Batch 4 | verdict + 0 JDBC symbols in `src/main` + guard test green |
| M-05 | Tools in `com.aiturbo.tools` | Batch 5 | verdict + no tool class in domain packages |
| M-06 | Model field + local path wired, engineering complete offline | Batch 6 | verdict + new tests green + dependency set within the allowed list |
| M-07 | Docs current | Batch 7 | README review finds no contradiction |
| M-08 | Live smoke + FR-13 + benchmark recorded | Batch 8 | MS checklist results + benchmark table in the feature docs (or BLOCKED-ON-USER records) |

### Final milestone M-08 — manual smoke checklist (MS-01…MS-14)

Prerequisites: `./gradlew run` on `http://localhost:8080`; git-ignored `.env` with
`DEEPSEEK_API_KEY` (and `STAGE_DB_PASSWORD` for the fueling lookup); stage database reachable;
hardware noted for the benchmark. Identifiers use the **MS-** prefix to avoid colliding with the
milestone IDs M-01…M-08 (skill convention). Run commands one at a time; keep server logs out of
context except via the greps shown.

- **MS-01 — Ollama probe (user-managed; blocking gate for local steps).**
  `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:11434/api/tags` and
  `curl -s http://localhost:11434/api/tags | grep -o '"qwen3:8b"' | head -1`.
  If it answers and lists `qwen3:8b`, continue. If not: do not start Ollama (out of scope) — record
  MS-05…MS-07, MS-09(local part), MS-14-local as **BLOCKED-ON-USER** with these exact
  instructions: `brew services start ollama` (or `ollama serve`), then
  `ollama list | grep qwen3:8b` (if missing: `ollama pull qwen3:8b`), then re-run the blocked steps.
- **MS-02 — Health and `/time` unchanged.** `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/`
  → 200; `curl -s -w '\nHTTP %{http_code}\n' 'http://localhost:8080/time?location=Moscow'`
  → 200 and, in the log, the DeepSeek endpoint/model (no `model` field accepted here).
- **MS-03 — Default provider (field absent).**
  `curl -s -w '\nHTTP %{http_code} time=%{time_total}\n' -H 'Content-Type: application/json' -d '{"message":"Какая сейчас погода в Москве?"}' http://localhost:8080/weather`
  → 200 `{"message","answer"}`; log shows `endpoint=https://api.deepseek.com/chat/completions` and
  the DeepSeek model id; behavior indistinguishable from before the feature.
- **MS-04 — Explicit `"model":"deepseek"`** — same body plus `"model":"deepseek"` → 200, same
  endpoint/model lines as MS-03.
- **MS-05 — Local weather (FR-07; requires MS-01 OK).**
  `curl -s -w '\nHTTP %{http_code} time=%{time_total}\n' -H 'Content-Type: application/json' -d '{"message":"Какая сейчас погода в Москве?","model":"local"}' http://localhost:8080/weather`
  → 200 + answer; chain shows `endpoint=http://localhost:11434/api/chat`, `model=qwen3:8b`,
  `tools_count=1`, `tool_calls=[{"name":"get_weather",…}]`, `stage=db saved=true`, `stage=tool`,
  second round trip, `stage=outbound`.
- **MS-06 — Local fueling (FR-07; requires MS-01 OK).**
  `curl -s -w '\nHTTP %{http_code} time=%{time_total}\n' -H 'Content-Type: application/json' -d '{"message":"Найди данные по проливу для заказа 61574131-999F-48C9-93AE-3EAA68562177","model":"local"}' http://localhost:8080/fueling`
  → 200 + summary; `stage=tool`/`stage=db` lines as in the DeepSeek run.
- **MS-07 — Padded / case-insensitive.** `{"message":"…","model":" LOCAL "}` on `/weather` → same
  as MS-05 (ASM-01).
- **MS-08 — Unknown value.** `{"message":"…","model":"gpt-4"}` → HTTP 400
  `{"error":"Field 'model' must be one of: local, deepseek"}`; the log has `inbound`/`outbound` but
  no `deepseek-request` line (no LLM, no tool, no DB call).
- **MS-09 — Ollama stopped / unreachable (FR-08).** Run only while the service is stopped
  (default state); if the user is running it and will not stop it, mark BLOCKED-ON-USER with
  `brew services stop ollama`. Command: the MS-05 body → expect HTTP 503 with a plain-language
  message and `time_total ≤ 10 s`; the log shows `stage=deepseek-response … error=…` with
  endpoint/model and no secret; then a default (DeepSeek) request succeeds and the server never
  hangs (NFR-06).
- **MS-10 — Chain integrity (FR-10, NFR-05).**
  `grep 'req=' logs/ai-turbo.log | tail -n 60` — verify, for one MS-03 run and one MS-05 run, one
  `req=` id each, order `inbound → deepseek-request → deepseek-response → tool → db → outbound`,
  and that only `endpoint=`/`model=` distinguish local from DeepSeek; body values truncated at
  4096 chars.
- **MS-11 — Secrets (NFR-03).**
  `grep -c "$(grep '^DEEPSEEK_API_KEY=' .env | cut -d= -f2- | tr -d '"')" logs/ai-turbo.log` and
  the same for `STAGE_DB_PASSWORD` → both 0; a repo search over tracked files and the feature docs
  finds no secret value. Never paste the values into the report.
- **MS-12 — `/weather/history` shape (FR-02).**
  `curl -s 'http://localhost:8080/weather/history?limit=5'` → `{"records":[…]}` same shape; one new
  `users` row from MS-05, `created_at` populated by the DB default.
- **MS-13 — Stage lookup fidelity (FR-02/FR-03, R5, NFR-09).**
  Diff the MS-06 `find_fueling` output for the verified GUID against the recorded report in
  `docs/features/fueling-order-tool/04-test-report.md` — values identical; row counts of the
  touched stage tables unchanged.
- **MS-14 — FR-13 tool-calling verdict (live gate, R-01).**
  For both MS-05 and MS-06, record one live run showing `deepseek-response … tool_calls=[…]`,
  `stage=tool`, final plain-language answer. If `qwen3:8b` does not emit tool calls: record the
  observed evidence (status, chain excerpt), report BLOCKED (user decision on another tool-capable
  local tag) — no silent scope change.

### Benchmark commands (T-25, FR-12 / ASM-13)

Warm-up (1 request per provider per endpoint, discard), then ≥5 measured sequential requests, one
provider at a time, identical prompt per endpoint; record `time_total` in ms. Example loop for
`/weather`, local:

```bash
for i in 1 2 3 4 5; do curl -s -o /dev/null -w '%{time_total}\n' -H 'Content-Type: application/json' \
  -d '{"message":"Какая сейчас погода в Москве?","model":"local"}' http://localhost:8080/weather; done
```

and the same with `"model":"deepseek"`; repeat both for `/fueling` with the MS-06 GUID prompt. A
≥2-run loop is the smoke-day sanity check; the ≥5-run requirement is the FR-12 deliverable.
Expected report format in `04-test-report.md`:

| Endpoint | Provider | `model` field | model id | runs (ms) | median | min | max |
|---|---|---|---|---|---|---|---|
| /weather | local | local | qwen3:8b (Ollama 0.34.4) | … | … | … | … |
| /weather | deepseek | deepseek/absent | deepseek-flash | … | … | … | … |
| /fueling | local | local | qwen3:8b | … | … | … | … |
| /fueling | deepseek | deepseek/absent | deepseek-flash | … | … | … | … |

Plus: method reference (1 warm-up + ≥5 measured, client-visible latency, same prompt per endpoint),
date, one-line machine note (CPU/GPU/RAM), versions, single-machine/single-user caveat, and a 2–3
line conclusion (which provider is faster here and by how much; note that tool flows include two
LLM round-trips). No latency claims in the README (D20).

---

## Acceptance criteria

**T-01 — Dependencies.**
- `build.gradle.kts` contains `org.kodein.di:kodein-di-jvm:7.32.0` (or the recorded fallback
  coordinate per E-01), `org.jetbrains.exposed:exposed-core:1.5.0`,
  `org.jetbrains.exposed:exposed-jdbc:1.5.0`; no other dependency added or removed.
- `./gradlew test` = BUILD SUCCESSFUL; test count 208 across 30 files.
- Resolution runs without metadata errors; the `kotlin-stdlib 2.3.20` request from Exposed surfaces
  at most as a warning. On failure: step-down applied (R-03) and recorded before continuing.

**T-02 — Kodein container and modules.**
- `plugins/Di.kt` provides `Application.installDi(DI)`, `Application.di`, `Route.di`; nothing is
  resolved at install time.
- `appModules`/`fuelingModule` are `DI.Module`s with unique names; same bindings as today with tags
  (`DEEPSEEK_EXECUTOR_TAG`, `LOCAL_DATABASE_TAG`, `STAGE_DATABASE_TAG`, `FUELING_TOOL_SPEC`,
  `FUELING_TOOL_REGISTRY` as `String` tags); both `ToolSpec` loaders stay `eagerSingleton`
  (fail-fast at container build); the unnamed `ToolRegistry` stays `get_weather`-only.
- `Application.module(overrideModules: List<DI.Module>)` builds exactly one container; production
  path imports both modules; `install(Koin)` gone.
- `./gradlew compileKotlin` = BUILD SUCCESSFUL; `grep -rn 'org.koin' src/main/kotlin/com/aiturbo/Application.kt src/main/kotlin/com/aiturbo/plugins/Di.kt` → 0.

**T-03 — Routes via Kodein.**
- The three route files resolve beans with `by di.instance()` lazily inside the route builders; no
  `org.koin` import remains in `src/main`.
- Route registration with a partial graph (the frozen route tests' pattern) still works — no bean
  resolved at registration time.
- Handler bodies, status codes and response shapes unchanged by this task.

**T-04 — Koin tests adapted.**
- The 7 listed test files compile and pass; each replaced Koin API has a direct Kodein equivalent
  (`DI { import(…) }`, `instance()`, `instance(tag = …)`); `createEagerInstances()` is gone.
- No `@Test` method removed; per-file assertion counts do not decrease.
- `AppModulesTest`/`FuelingModulesTest` still assert the same object graph (registries, agent
  unavailable-on-blank-key), with type names updated only where the type changes (stage repository
  type becomes `ExposedStageFuelingRepository` in T-11).

**T-05 — Koin removal.**
- `koin-ktor`, `koin-core`, `koin-test` absent from `build.gradle.kts`.
- `./gradlew test` = BUILD SUCCESSFUL; 208 tests green.
- Grep over `build.gradle.kts` and `src/`: 0 `io.insert-koin`, 0 `org.koin`, 0 `install(Koin)`;
  `org.kodein.di` references are the only "kodein/koin" family left.

**T-06 — EnvFile.**
- `EnvFile.parse` follows the fixed rules (first `=`, blanks/whole-line `#` skipped, trimmed key and
  value, one matching quote pair stripped, no interpolation, empty value = `""`, last duplicate
  wins, line without `=` ignored); `EnvFile.load` returns an empty reader for a missing/unreadable
  file; non-data class; no logging; no value ever in `toString()`/trace output.
- `Application.kt`'s `loadDotenv(): EnvFile` uses it; `DeepseekConfig`/`DbConfig`/`StageDbConfig`
  resolution chains and the `resolve*` functions unchanged.
- `EnvFileTest` covers every parse rule plus the missing-file and purity cases; all offline with
  temp files; `./gradlew test` green.

**T-07 — dotenv removal.**
- `dotenv-kotlin` absent from `build.gradle.kts`; grep `io.github.cdimascio` over `src/` → 0.
- `./gradlew test` = BUILD SUCCESSFUL; config tests green with no semantic changes; startup with no
  `.env` is covered by `EnvFile.load`'s empty-reader test (live start deferred to the smoke).

**T-08 — pgDataSource.**
- `pgDataSource(...)` returns a `PGSimpleDataSource` with URL/user/password/applicationName set;
  timeouts (`setConnectTimeout`/`setSocketTimeout`/`setLoginTimeout`) set only when
  `timeoutSeconds != null`.
- No connection is opened at construction; no `DriverManager` import; function is `internal` in
  `com.aiturbo.db`.

**T-09 — Exposed weather repository.**
- `UsersTable` matches the design C5 mapping; `save()` inserts through one transaction per attempt
  via the unchanged `insertWithRetry`; unset `id`/`created_at` are omitted (DB defaults);
  `insertReturning(listOf(id))` yields the new id.
- Unique violation is recognised through the cause chain (`ExposedSQLException` wrapping
  `SQLException` with sqlState `23505`); direct `SQLException` cases still recognised; exhausted
  attempts → `-1` + the existing warning text; other failures → `DatabaseUnavailableException`.
- `recent(limit)` preserves ordering, limit and DTO shape (same JSON as before).
- `DATA_FORMAT`/`TIME_FORMAT`/`insertWithRetry`/`MAX_INSERT_ATTEMPTS` keep names, package,
  signatures; `WeatherRecordRepositoryTest` passes including the new wrapped-violation case; no
  `@Test` removed; `JdbcWeatherRecordRepository.kt` deleted in the same change-set (E-03).

**T-10 — Stage tables.**
- All six table objects exist with the design C6 column sets/types; `FuelingsColumns` is shared by
  the three fuelling tables; `DECIMAL_PRECISION`/`DECIMAL_SCALE` = 38/18 nominal.
- No DDL, no write DSL, no raw SQL in the file; table names are literals matching the live schema.

**T-11 — Exposed stage repository.**
- Implements `StageFuelingRepository` unchanged; one `transaction` per lookup; three main lookups
  via the enum-derived table map with `lowerCase() eq fuelingId` (bound value, no ORDER BY added);
  related rows ordered `DESC NULLS LAST`; same 22/8/6/4 column coverage and identical DTO mapping
  (`epochMillisOrNull` for epoch columns, `vendorTransactionDate` as text).
- No `insert`/`update`/`delete`/`replace`/`SchemaUtils`/raw SQL string in the file; table names
  never derive from user input.
- `ExposedSQLException`/`SQLException` → `DatabaseUnavailableException` with the existing message
  shape; `JdbcStageFuelingRepository.kt` deleted in the same change-set with `Application.kt`'s
  binding switched to the Exposed repository (E-03); `FuelingModulesTest`'s type assertion updated;
  `StageEpochMappingTest` green.

**T-12 — Guard test adapted.**
- `StageReadOnlyGuardTest` scans the Exposed stage sources for the write/DDL tokens from design T9
  (`insert`, `insertReturning`, `batchInsert`, `upsert`, `replace`, `update`, `deleteWhere`,
  `deleteAll`, `SchemaUtils`, `createStatement`, `prepareStatement`, `execute(`, raw
  `SELECT/INSERT/UPDATE/DELETE` strings) and finds none.
- It asserts the case-insensitive `lowerCase()` predicate and enum-derived tables remain; the
  weather-outside-scope test points at `ExposedWeatherRecordRepository.kt` and its by-design
  `insertReturning`; the test is adapted, never deleted.

**T-13 — Exposed batch verdict.**
- `./gradlew test` = BUILD SUCCESSFUL; grep over `src/main` → 0 `DriverManager`, 0
  `PreparedStatement`, 0 `ResultSet`; no `Jdbc*Repository.kt` file remains.

**T-14 — Tools move.**
- Both tool classes live only under `src/main/kotlin/com/aiturbo/tools/`; no tool class under
  `weather/` or `fueling/`.
- Class names, constructors, `@LLMDescription` arguments and JSON resources unchanged; the
  `stage=deepseek-request … tools=[…]` rendering is byte-identical (canary: the frozen tool tests
  and `ToolsChainIntegrationTest`-style assertions); all tool/agent test suites pass with
  import-only adjustments; `./gradlew test` green.

**T-15 — Ollama client API verified.**
- A recorded note names the exact artifact and FQCN of the Ollama prompt-executor client resolved by
  the current dependency set, its constructor form (and whether a `KoogHttpClient`/timeout config
  must be passed), and the timeout configuration used for the ≤10 s budget.
- Whether the artifact is on the compile classpath transitively is recorded; if not, the blocker is
  escalated per E-04 before any code depends on it.

**T-16 — DeepSeek factory moved; exception extracted.**
- `com.aiturbo.llm.DeepSeekLlm` exposes `deepseekModel(id)`/`deepseekPromptExecutor(config)`
  verbatim; `com.aiturbo.weather.WeatherUnavailableException` exists with the same package/name as
  before; `weather/DeepSeekKoogLlm.kt` is deleted; no reference to it remains.
- `Routing.kt`'s `WeatherUnavailableException → 503` mapping is untouched; `./gradlew test` green
  (batch gate).

**T-17 — Ollama factory and config.**
- `OllamaConfig` defaults `http://localhost:11434`/`qwen3:8b`, `from(config)` reads
  `ollama.baseUrl`/`ollama.model`, `chatEndpoint` = `${baseUrl trimmed}/api/chat`.
- `application.conf` gains the `ollama` section with `${?OLLAMA_BASE_URL}`/`${?OLLAMA_MODEL}`
  overrides; no secret involved.
- `ollamaPromptExecutor` builds a `MultiLLMPromptExecutor(LLMProvider.Ollama to <verified client>)`
  with the verified constructor and ≤10 s timeouts if available; `ollamaModel` declares
  `Completion`, `Temperature`, `Tools`; construction performs no I/O.
- `OllamaConfigTest` covers defaults, config overrides and the endpoint label; green offline.

**T-18 — LlmTarget.**
- `fromRequest(null)`, `fromRequest("")`, `fromRequest("   ")` → `DEEPSEEK`; `"local"`, `" LOCAL "`,
  `"Local"` → `LOCAL`; `"deepseek"`, `"DEEPSEEK"` → `DEEPSEEK`; `"gpt-4"` → `null`;
  `ALLOWED_VALUES == "local, deepseek"`.
- `currentLlmTarget()` returns `DEEPSEEK` when no `LlmTargetContext` element is present; the element
  travels through `withContext` (test proves read-back).

**T-19 — Routing executor.**
- With no element (and with `LlmTargetContext(DEEPSEEK)`): the DeepSeek delegate is called with the
  passed model unchanged.
- With `LlmTargetContext(LOCAL)`: the local delegate is called with the Ollama model
  (`provider == LLMProvider.Ollama`, id `qwen3:8b`), regardless of `deepseekConfigured`.
- `deepseekConfigured = false` + DEEPSEEK/no element → `WeatherUnavailableException` thrown before
  any delegate call and before any log line; local still works with a blank key.
- `close()` closes both delegates (both attempted even if one throws); `executeStreaming` routes by
  target at collection time; `moderate` follows the same selection.

**T-20 — DI wiring.**
- Tagged bindings for both `LoggingPromptExecutor`s carry the correct endpoint labels (DeepSeek
  `${baseUrl}/chat/completions`, Ollama `chatEndpoint`); the unnamed `PromptExecutor` is the
  routing executor with `deepseekConfigured = apiKey.isNotBlank()`; `ollamaModel(cfg.model)` is
  passed for local substitutions.
- `WeatherAgent` is bound unconditionally (local works without a key); `fuelingModule` keeps its
  `apiKeyConfigured` behavior for the DeepSeek path; `TimeZoneResolver` keeps today's shape on the
  DeepSeek branch.
- `AppModulesTest` updated: routing executor type, both endpoint labels, tags; `./gradlew test`
  BUILD SUCCESSFUL (batch gate M-06).

**T-21 — Route contract.**
- `@EncodeDefault(NEVER) val model: String? = null` on both request DTOs; a request without the
  field renders the inbound body byte-identically to today (frozen assertion is the canary).
- Unknown non-blank value → 400 `{"error":"Field 'model' must be one of: local, deepseek"}`; the
  fake agent is never called and no `deepseek-request` line appears.
- `" LOCAL "` → target LOCAL observed by the fake agent through `currentLlmTarget()`; absent/blank →
  DEEPSEEK; response bodies keep `{"message","answer"}` (no provider echo).
- The same cases hold on `/fueling`; `/time` and `/weather/history` are untouched.

**T-22 — Engineering-complete sweep.**
- `./gradlew test` = BUILD SUCCESSFUL; 30 test files still present; test count ≥ 208 + new.
- Greps: 0 `io.insert-koin`/`org.koin`/`install(Koin)`; 0 `io.github.cdimascio`; 0
  `DriverManager`/`PreparedStatement`/`ResultSet` in `src/main`; no `SchemaUtils` anywhere in
  `src/main`.
- Dependency set = pre-feature set − `koin-ktor`/`koin-core`/`koin-test`/`dotenv-kotlin` +
  `kodein-di-jvm` + `exposed-core`/`exposed-jdbc`; the Ollama client still transitive (or the E-04
  escalation recorded).

**T-23 — README/docs.**
- README: stack line (Kodein + Exposed, no Koin/plain JDBC), tools package, request-field matrix
  (local/deepseek/absent/blank/padded/unknown), `ollama.*` keys and `OLLAMA_*` overrides, one
  example chain per provider, secrets rules; no latency claims.
- Feature docs complete and consistent (requirements → design → plan → spec/test report); no
  contradiction found in review.

**T-24 — Live smoke.**
- MS-01…MS-14 executed; each item recorded PASS/FAIL/BLOCKED-ON-USER with the evidence (HTTP code,
  `time_total`, chain excerpts by `req=`).
- FR-13 verdict recorded per endpoint (tool call observed) or a blocker record with observed
  evidence; no scope change.
- Server and DeepSeek steps executed regardless of the Ollama state.

**T-25 — Benchmark.**
- The FR-12 protocol was followed (1 warm-up discarded + ≥5 measured sequential requests per
  provider per endpoint, same prompt per endpoint, one provider at a time).
- The table (median/min/max, ms) plus date, machine note, versions (`qwen3:8b`, Ollama 0.34.4,
  `deepseek-flash`), single-machine caveat and a 2–3 line conclusion is recorded in
  `docs/features/kodein-exposed-ollama/04-test-report.md`; readable/reproducible from the
  documented recipe; no latency claims in the README.

---

## Traceability matrix

| Requirement | Covering tasks |
|---|---|
| FR-01 Koin removed, Kodein DI, same graph | T-01, T-02, T-03, T-04, T-05, T-20, T-22 |
| FR-02 Exposed for both DBs, same behavior, IO dispatch | T-08, T-09, T-10, T-11, T-13, T-22; live parts MS-12/MS-13 |
| FR-03 Stage read-only | T-10, T-11, T-12; live check MS-13 |
| FR-04 `.env` reader replaces dotenv-kotlin | T-06, T-07, T-23 |
| FR-05 Tools in `com.aiturbo.tools`, JSON contracts unchanged | T-14 |
| FR-06 `model` field contract (local/deepseek/absent/blank/unknown→400) | T-18, T-19, T-20, T-21; smoke MS-03/MS-04/MS-07/MS-08 |
| FR-07 Local routing via Koog Ollama client | T-15, T-17, T-19, T-20; smoke MS-05/MS-06 |
| FR-08 Graceful local failure, no fallback | T-17 (timeouts), T-19 (error propagation), T-20; smoke MS-09 |
| FR-09 DeepSeek path unchanged incl. blank-key 503 | T-16, T-19, T-20; smoke MS-02/MS-03/MS-04 |
| FR-10 Log identifies the actual target | T-17 (labels), T-19, T-20 (endpoint binding), T-21; smoke MS-10 |
| FR-11 Existing tests preserved, offline | T-04, T-05, T-07, T-09, T-12, T-13, T-14, T-21, T-22 (batch gates; no deletions) |
| FR-12 Latency comparison recorded | T-25; smoke benchmark section |
| FR-13 qwen3:8b tool calling verified live | T-15, T-24 (MS-14); risk R-01 |
| FR-14 README/docs current | T-23, T-07 (config docs via T-17) |
| FR-15 Pipeline + four reviewers used | Process (DoD item); evidence records T-22, T-24, T-25; T-23 doc consistency |
| NFR-01 Offline testability | T-04, T-06, T-09, T-12, T-17, T-18, T-19, T-21 (all new tests offline), T-22 |
| NFR-02 Dependency hygiene (exact allowed deltas) | T-01, T-05, T-07, T-22; E-04 |
| NFR-03 Security / no secrets | T-06 (no logging), T-20 (no config into trace), T-22, T-23; smoke MS-11 |
| NFR-04 Startup independence (DBs/Ollama/`.env` absent) | T-02 (eager singletons limited to JSON), T-06, T-08, T-09/T-11 (lazy Database), T-17 (no I/O at construction); smoke MS-01 partial |
| NFR-05 Observability (single req id, stage order, truncation) | T-17, T-19, T-21; smoke MS-10 |
| NFR-06 ≤10 s local failure, no hang | T-15, T-17 (timeouts), T-19; smoke MS-09 |
| NFR-07 Compatibility (versions, contracts) | T-01, T-17, T-20, T-21, T-22 |
| NFR-08 Build/test discipline (verdict-only) | Every batch exit gate (T-05, T-07, T-13, T-22); plan-level execution discipline |
| NFR-09 Data safety (stage read-only) | T-11, T-12, T-13; smoke MS-13 |
| NFR-10 Benchmark reproducibility | T-25 (protocol, versions, recipe) |

**All FRs (FR-01…FR-15) and all NFRs (NFR-01…NFR-10) are covered by at least one task.**

---

## Risk register

| ID | Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| R-01 | qwen3:8b tool calling (architect R1): the whole local path depends on the model emitting tool calls through the Koog client on both tools; live, user-dependent gate (Ollama currently stopped) | Med | High | FR-13 verification is the smoke milestone MS-14 with one recorded run per endpoint; failure = recorded blocker with observed evidence; alternatives (another local tag) are a user decision, never a silent scope change; local steps report BLOCKED-ON-USER if the service is down | tester (live), orchestrator for escalation |
| R-02 | Koog 1.2.0 Ollama client API shape unverified (architect R2): class/package/constructor and whether a `KoogHttpClient`/timeout config is needed are not confirmed; artifact may not be on the compile classpath transitively | Med | Med | T-15 verifies against the resolved jar in the Gradle cache before `OllamaLlm.kt` is written; documented fallback = the `baseUrl` convenience factory; ≤10 s timeouts if the API allows; explicit dependency only if compilation requires (E-04) and flagged before adding | developer |
| R-03 | Exposed 1.5.0 dependency nuances (architect R3): requests `kotlin-stdlib 2.3.20` (> project 2.3.10, warning expected) and pulls `kotlinx-datetime`/coroutines transitively; Kodein 7.32.0 keeps stdlib 2.2.21 | Med | Med | First build after T-01 is the check; on metadata/version failure: pin stdlib via a resolution strategy or step down (exposed 1.4.x, kodein-di 7.29.0) and record the chosen versions in the test report | developer |
| R-04 | Exposed insert/transaction semantics (architect R4): that unset `id`/`created_at` are omitted, `insertReturning` yields the id, and `transaction { }` does not internally retry (which would multiply UNIQUE retries) | Low-Med | High | Live weather smoke MS-05/MS-12 checks one row per request with `created_at` set; the F9 two-requests-in-one-second case is exercised live; if v1 retries internally, disable it explicitly at the `transaction` call site | developer + tester (live) |
| R-05 | Stage column-type fidelity (architect R5): `decimal(38,18)`/`text` mapping must not alter any value of the previously live-verified report | Med | High | MS-13 diffs the MS-06 output against `docs/features/fueling-order-tool/04-test-report.md`; `StageEpochMappingTest` guards the epoch seam offline | tester (live) |
| R-06 | `@EncodeDefault(NEVER)` assumption (architect R6): if the defaulted `null` is not suppressed, the frozen inbound-body assertion fails | Low | Med | T-21 asserts the rendered body explicitly (no `model` key when absent); fallback only with the failing test as evidence: dedicated trace rendering or an adapt-the-assertion decision, recorded | developer |
| R-07 | ≤10 s local failure budget on macOS (architect R7): a closed port refuses immediately, a wrong reachable host relies on the client connect timeout | Low | Med | MS-09 measures `time_total`; if needed, an unreachable-host variant; budget is a client-timeout configuration, tuned in T-17 | developer + tester |
| R-08 | Container lifecycle (architect R8): no explicit executor close on shutdown (unchanged from Koin behavior) | Low | Low | Not required by any FR/NFR; optional `ApplicationStopped` close hook may be added in T-20 if idiomatic — never at the cost of scope | developer |
| R-09 | Frozen-test churn (architect R9 + frozen-test rule): 7 files change DI shape, 1 guard test is rewritten, ~10 files get import-only edits; risk of accidental test loss | Med | Med | Tests are adapted, never deleted; every batch gate asserts BUILD SUCCESSFUL; T-22 asserts 30 files and count ≥ 208; per-file assertions preserved except mechanical API mapping | developer; tester verifies counts |
| R-10 | Secret-handling regression while replacing dotenv-kotlin (architect R10): the new reader must not log/expose values and must keep the resolution precedence | Low | High | `resolve*` signatures unchanged (tests unchanged); `EnvFile` non-data, no logging; `EnvFileTest` covers parse/load; MS-11 secret search over logs and tracked files | developer; reviewer-git |
| R-11 | Kodein 7.32.0 vs 7.33.0 metadata constraint and coordinate nuance: 7.33.0 is compiled against Kotlin 2.4 metadata (rejected by 2.3.10); design names `kodein-di`, the pipeline names `kodein-di-jvm` | Med | Med | Pin 7.32.0; primary coordinate `kodein-di-jvm` with the KMP root as recorded fallback (E-01); 7.29.0 as last-resort step-down (R-03); never 7.33.0 | developer |
| R-12 | Build/test output discipline (CLAUDE.md): build logs, test dumps or server logs leaking into agent context | Med | Low-Med | Every batch gate is verdict-only (`BUILD SUCCESSFUL`/`BUILD FAILED`, first 20–40 lines on failure); logs only via `grep 'req='`/tail; the code-style-checker verifies compliance at review time | all agents; code-style-checker |
| R-13 | Integration unknown-unknown: `LlmTargetContext` must be readable inside the Koog strategy loop at the executor call site (same mechanism as `CallTrace`, but not yet proven for this layer) | Low | High | T-21 proves it offline with a fake agent inside `withContext`; the live smoke re-confirms; if it fails, escalate — an alternative wiring is a design change (D13), not an improvisation | developer |
| R-14 | Offline test-surface gap: Exposed runtime behavior (SQL generation, defaults, driver interaction) is not covered offline (design D18 rejects H2/Testcontainers) | Med | Med | Compensating offline seams (retry tests T-09, guard scan T-12, config/selector/routing tests) plus the live smoke items MS-05/MS-12/MS-13 and the F9 live case; the gap is stated in the test report | tester |
| R-15 | Live smoke depends on the user-managed Ollama service (currently stopped) and the user's environment | High | Med | MS-01 probe first; if down, local steps are recorded BLOCKED-ON-USER with exact instructions (`brew services start ollama` / `ollama serve`, `ollama pull qwen3:8b`) and re-runnable without code changes; server/DeepSeek steps always executable | tester, orchestrator |

---

## Definition of Done (feature level)

1. `./gradlew test` is BUILD SUCCESSFUL, fully offline (no network, no real DB, no Ollama, no live
   LLM); the 30 pre-existing test files are still present and the 208 pre-existing `@Test` methods
   are all still present (adapted, never deleted); the count is ≥ 208 plus the new tests.
2. Repository search finds 0 Koin (`io.insert-koin`, `org.koin`, `install(Koin)`), 0
   `dotenv-kotlin`/`io.github.cdimascio`, and 0 `DriverManager`/`PreparedStatement`/`ResultSet` in
   `src/main`; Kodein and Exposed are the only DI/ORM tools; the dependency set is the pre-feature
   set minus koin-*/dotenv plus `kodein-di`(jvm) + `exposed-core`/`exposed-jdbc` only (NFR-02;
   Ollama client transitive or the E-04 escalation recorded).
3. The app starts with both databases absent, no `.env` and Ollama stopped; `GET /` and `GET /time`
   work (boot check in the smoke).
4. `model=local` returns 200 with a plain-language answer on both endpoints (Ollama running) with
   the local endpoint/model in the chain and the tool executed; `model=deepseek` and the absent
   field behave exactly as before the feature.
5. `model=gpt-4` → 400 naming `local`/`deepseek`, with no LLM/tool call; `" LOCAL "` behaves as
   local; `GET /time` stays DeepSeek-only.
6. Ollama stopped/unreachable + `model=local` → documented 503 within ≤10 s, no hang; a following
   DeepSeek request succeeds; no automatic fallback exists.
7. The trace chain keeps one `req=` id, the documented stage order, 4096-char truncation and 0
   secrets; every `deepseek-request`/`deepseek-response` line carries `endpoint=`/`model=`
   identifying the actual target.
8. The FR-13 live tool-calling verification is recorded once per endpoint (or blocked, with
   evidence, on the user-managed Ollama).
9. The FR-12 benchmark table (method, date, machine, versions, median/min/max per provider per
   endpoint, caveat, 2–3 line conclusion) is recorded in this feature's docs; no latency claims in
   the README.
10. `README.md` no longer contradicts the shipped stack, request field, config keys or tool
    packages (FR-14); the feature docs are complete and consistent (FR-15); the review uses the four
    local agents (`code-style-checker`, `reviewer-correctness`, `reviewer-deduplication`,
    `reviewer-git`) with `CODE_STYLE.md` compliance verified by `code-style-checker`.
11. Stage access is read-only by construction and by the adapted guard test; the live lookup's row
    counts are unchanged and its values match the previous feature's verified report.
