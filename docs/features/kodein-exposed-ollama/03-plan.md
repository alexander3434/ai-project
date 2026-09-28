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
