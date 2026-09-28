# Kodein + Exposed + Local Model Routing — Review

**Verdict: APPROVED**

- **Feature:** kodein-exposed-ollama
- **Spec:** `docs/features/kodein-exposed-ollama/spec.md` (requirements FR-01…FR-15, NFR-01…NFR-10, design C1–C10, plan T-01…T-25)
- **Test report:** `docs/features/kodein-exposed-ollama/04-test-report.md` (revised 2026-09-28, post-fix)
- **Date:** 2026-09-28
- **Reviewer:** reviewer agent (read-only on code)
- **Diff note:** HEAD (`3c56077`) predates two features and most of `src/` is untracked, so `git diff` cannot isolate this change-set; the review was done against the current working tree using the plan's file inventory (see `## Scope check`).
- **Revision note:** the initial review returned CHANGES REQUESTED (1 blocker, B-1: local-provider failure returned 500 instead of the documented 503). B-1 was fixed in `plugins/Routing.kt` (only file changed), the fix was verified in code and by a green re-run, and the review now closes APPROVED.

## Verdict: APPROVED

The single blocker (B-1) is resolved: `StatusPages` now maps `KoogHttpClientException` to 503 "LLM provider is unavailable" before the `Throwable` catch-all (`plugins/Routing.kt:56-60`), satisfying FR-08/ASM-09/NFR-06's documented failure contract. All other areas — Koin removal, both Exposed repositories, EnvFile, the tools move, the request contract, the DeepSeek blank-key path, the log chain and the offline suite — comply. 0 blockers, 0 majors remain; the 7 minors and 4 nits below are recorded, none blocking.

## Compliance matrix

Legend: PASS = implemented and evidenced offline; PASS (review) = verified by reading code/jars, no executable offline assertion; PENDING-LIVE = T-24/T-25 (orchestrator/live) by design.

| Requirement | Implementation evidence | Test / verification evidence | Status |
|---|---|---|---|
| FR-01 Koin fully removed, Kodein DI, same graph | `src/main/kotlin/com/aiturbo/plugins/Di.kt:9-20`; `src/main/kotlin/com/aiturbo/Application.kt:105-238` (`DI.Module`, tags, eager tool specs); routes `by di.instance()` in `plugins/Routing.kt:30-31`, `plugins/WeatherRouting.kt:47-48`, `plugins/FuelingRouting.kt:42` | `AppModulesTest` (6), `FuelingModulesTest` (5), `ApplicationTest` (5), `WeatherRoutesTest`/`FuelingRoutesTest`, both chain tests; grep `io.insert-koin\|org.koin\|install(Koin)` = 0 | PASS |
| FR-02 Exposed for both DBs, same behavior, IO dispatch | `db/ExposedWeatherRecordRepository.kt`, `db/ExposedStageFuelingRepository.kt`, `db/PgDataSource.kt:19-35`, `db/UsersTable.kt`, `db/StageTables.kt`; blocking calls inside `withContext(Dispatchers.IO)` (`tools/GetWeatherTool.kt:62-63`, `plugins/WeatherRouting.kt:88`, `tools/FindFuelingTool.kt:55-56`) | `WeatherRecordRepositoryTest` (5, incl. wrapped `23505`), `FuelingModulesTest`, both chain tests; grep JDBC symbols in `src/main` = 0; no `Jdbc*Repository.kt` remains. Live write/read and stage fidelity: MS-12/MS-13 | PASS (offline); live parts PENDING-LIVE |
| FR-03 Stage read-only | `db/ExposedStageFuelingRepository.kt:33-74` (only `selectAll().where`), enum-derived tables (`:76-82`), no writes/DDL | `StageReadOnlyGuardTest` (4 tests, incl. the repointed weather-outside-scope test) | PASS |
| FR-04 `EnvFile` replaces dotenv-kotlin | `config/EnvFile.kt:25-54` (rules per C8), `Application.kt:95` (`loadDotenv`), resolution chains untouched | `EnvFileTest` (10); `DeepseekConfigTest`/`DbConfigTest`/`StageDbConfigTest` green; grep `io.github.cdimascio` = 0 | PASS |
| FR-05 Tools in `com.aiturbo.tools` | `tools/GetWeatherTool.kt`, `tools/FindFuelingTool.kt`; nothing under `weather/`/`fueling/` | `GetWeatherToolTest`, `FindFuelingToolTest`, agent tests, `TraceChainIntegrationTest.kt:220-227` and `FuelingChainIntegrationTest.kt:193-204` assert the tool JSON rendering | PASS |
| FR-06 `model` field contract | `plugins/WeatherRouting.kt:22-29,60-67`; `plugins/FuelingRouting.kt:18-25,54-61`; `llm/LlmTarget.kt:18-23`; `@EncodeDefault(NEVER)` on both DTOs | `LlmTargetTest` (7), `WeatherRoutesTest` (10, incl. the frozen inbound-body canary at `:196`), `FuelingRoutesTest` (9) | PASS |
| FR-07 Local routing via Koog Ollama client | `llm/OllamaLlm.kt:36-46` (verified `OllamaClient(baseUrl, timeoutConfig)`), `llm/ProviderRoutingPromptExecutor.kt:37-63`, DI wiring `Application.kt:147-162` | `ProviderRoutingPromptExecutorTest` (11), `AppModulesTest:58-83` (endpoint labels, tags); live MS-05/MS-06/MS-14 | PASS (offline); live PENDING-LIVE |
| FR-08 Graceful local failure (documented 503) | `plugins/Routing.kt:56-60` — `exception<KoogHttpClientException>` → 503 "LLM provider is unavailable", before the `Throwable` catch-all; `LoggingPromptExecutor.kt:76-83` rethrows after logging; no fallback anywhere | Code-level fix verified in `Routing.kt`; the suite cannot reach the real client path, so live confirmation is MS-09 (orchestrator step) | PASS (code-level; live MS-09 pending) |
| FR-09 DeepSeek unchanged, blank-key 503 | `ProviderRoutingPromptExecutor.kt:78-83` (guard before any log/HTTP); tagged executors `Application.kt:139-153`; `WeatherUnavailableException` extracted unchanged (`weather/WeatherUnavailableException.kt`) | `AppModulesTest:95-112`, `ProviderRoutingPromptExecutorTest:166-192`, `TraceChainIntegrationTest:296-318`, `FuelingChainIntegrationTest:344-383` | PASS |
| FR-10 Log identifies the target | `Application.kt:139-153` (endpoint labels), `log/LoggingPromptExecutor.kt:88-111`, `log/TraceLog.kt:41-73` | `TraceChainIntegrationTest` (8-stage chain, endpoint/model per request), `AppModulesTest` label assertions; live MS-10 | PASS (offline); live PENDING-LIVE |
| FR-11 Existing tests preserved, fully offline | No test deletion, no `@Ignore`/`@Disabled`; 30 pre-existing classes present | `./gradlew cleanTest test` BUILD SUCCESSFUL (pre- and post-fix); 248 `@Test` in 34 classes (verified by count); no network/DB/Ollama in tests | PASS (baseline accounting caveat, M-5) |
| FR-12 Benchmark recorded | Not in the offline scope | T-25 by the orchestrator | PENDING-LIVE |
| FR-13 `qwen3:8b` tool calling verified live | Not in the offline scope | MS-14 by the orchestrator | PENDING-LIVE |
| FR-14 README/docs current | `README.md:3,43-44,56-76,134-145,207-235`, feature-docs index; config `application.conf:29-36` | Textual review; no contradiction with the stack — but two wording defects, see M-6 | PASS with minors |
| FR-15 Pipeline + reviewers | `01-requirements.md`…`04-test-report.md` present and consistent; this review continues the record | This artifact + prior reviews | PASS |
| NFR-01 Offline testability | New tests use fakes/`MapApplicationConfig`/temp files/`LogCapture` only | All 248 tests run with no network/DB/Ollama/`.env` | PASS |
| NFR-02 Dependency hygiene | `build.gradle.kts:33-38` (kodein-di-jvm 7.32.0, exposed-core/jdbc 1.5.0); no koin-*/dotenv; Ollama client transitive | Tester's runtime-classpath check; `build.gradle.kts` review | PASS |
| NFR-03 Security | `EnvFile` non-data class, no logging (`config/EnvFile.kt:10-12`); executors receive only endpoint/model (`Application.kt:139-162`); no config object into `TraceLog` | Chain tests' no-secret assertions; repo grep for secret-shaped strings = 0; live MS-11 | PASS (offline); live PENDING-LIVE |
| NFR-04 Startup independence | `Database.connect` lazy (`Application.kt:126-130,213-223`), no connection in `pgDataSource` (`db/PgDataSource.kt:25-35`), clients connect on first use | `AppModulesTest`/`FuelingModulesTest` build the production graphs offline; live boot MS-02 | PASS (offline); live PENDING-LIVE |
| NFR-05 Observability | `TraceLog` unchanged apart from the `KoogHttpClientException` detail (`log/TraceLog.kt:144-156`); 4096 truncation | `TraceChainIntegrationTest:320-345` (bounded lines), one-id assertions | PASS |
| NFR-06 ≤10 s local failure, no hang, documented status | `llm/OllamaLlm.kt:39-43,49` (10 s request/connect/socket); immediate refusal on a closed local port; 503 mapping now present (`plugins/Routing.kt:56-60`) | MS-09 measures status + `time_total`; code-level mapping verified | PASS (code-level; live MS-09 pending) |
| NFR-07 Compatibility | Kotlin 2.3.10 / Ktor 3.3.3 / Koog 1.2.0 / Gradle 8.14.1 / JVM 17 untouched; HTTP contracts preserved | Route/chain tests | PASS |
| NFR-08 Build/test discipline | Verdict-only runs; logs by grep in the smoke | This review's runs; test report | PASS |
| NFR-09 Data safety | Guard test adapted (never deleted); SELECT-only stage repository | `StageReadOnlyGuardTest`; live row counts MS-13 | PASS (offline); live PENDING-LIVE |
| NFR-10 Benchmark reproducibility | Recipe unchanged from the requirements | T-25 by the orchestrator | PENDING-LIVE |

## Findings

### Blocker

**None.** B-1 (reported in the initial review) is resolved — see "Resolved" below.

### Major

None.

### Resolved

**B-1 (resolved). `model=local` with Ollama stopped previously returned HTTP 500 instead of the documented 503 (FR-08, ASM-09, NFR-06, MS-09).**
- Original location: `src/main/kotlin/com/aiturbo/plugins/Routing.kt:33-59` (StatusPages); surfaced through `llm/ProviderRoutingPromptExecutor.kt:37-49` and `log/LoggingPromptExecutor.kt:76-83`.
- Fix: `plugins/Routing.kt:56-60` adds
  ```kotlin
  exception<KoogHttpClientException> { call, cause ->
      call.application.environment.log.warn("LLM provider is unavailable", cause)
      call.beginTrace()
      call.respondTraced(ErrorResponse("LLM provider is unavailable"), HttpStatusCode.ServiceUnavailable)
  }
  ```
  placed before the `Throwable` catch-all; the message is deliberately generic (no internal error text leaks). `Routing.kt` is the only file changed. Verified: mapping present in code; post-fix `./gradlew cleanTest test` → BUILD SUCCESSFUL with the same 248/248 tests; test report revised accordingly (`04-test-report.md:7,82`).
- Residual (live, not a finding): the offline suite cannot reach the real client path, so MS-09 must still confirm live that a refused Ollama connection surfaces as `KoogHttpClientException` (not a raw Ktor exception) and that the response is 503 within ≤10 s with one `deepseek-request` line showing `endpoint=http://localhost:11434/api/chat`. If a raw exception ever escapes instead, the fix would need the provider-level conversion noted in the initial review; the current Koog client wraps HTTP errors into `KoogHttpClientException`, so this is expected to hold. `application.conf:31`'s "documented 503" comment is now accurate.

### Minor

**M-1. `/fueling` with `model=local` and a blank DeepSeek key returns 503 instead of serving the local provider.**
- Location: `src/main/kotlin/com/aiturbo/Application.kt:231-237`; asserted by `src/test/kotlin/com/aiturbo/FuelingModulesTest.kt:89-95`.
- Rationale: ASM-08/FR-06 say `local` needs no DeepSeek key, but the fueling slice keeps the blank-key "unavailable agent" stub, so the stub fires before the routing executor can route the request to Ollama; `/weather` is already unconditional (`Application.kt:187`). The design itself prescribes this shape (C1, T-20), so the implementation follows the spec, but the observable contract differs between the two endpoints. No offline test covers `/fueling` + `local` + blank key.
- Suggested fix: bind `KoogFuelingAgent` unconditionally exactly like `WeatherAgent` — the routing executor already reproduces the blank-key 503 for the DeepSeek path (`ProviderRoutingPromptExecutor.kt:78-83`) — and adapt the `FuelingModulesTest` blank-key assertion accordingly. If the design's choice is kept deliberately, record the `/fueling` exception in the spec (ASM-08) and README so the contract is not silently inconsistent.

**M-2. T-08 `pgDataSource` has no direct automated test (confirmed gap).**
- Location: `src/main/kotlin/com/aiturbo/db/PgDataSource.kt:19-35`; 0 test references.
- Rationale: the plan's T-08 criterion (URL/user/password/applicationName set, timeouts only when `timeoutSeconds != null`, no connection at construction) is verified by review only. It is cheaply testable offline against the returned `PGSimpleDataSource` without opening a connection.
- Suggested fix: add a small `PgDataSourceTest` asserting the configured values/timeouts for both the null and non-null `timeoutSeconds` case.

**M-3. T-10 `StageTables` mapping has no direct automated test (confirmed gap).**
- Location: `src/main/kotlin/com/aiturbo/db/StageTables.kt:18-79`.
- Rationale: the column sets and their types (jsonb as `text`, epoch columns as `decimal(38,18)`, `vendor_transaction_date` as `text`) are the fidelity contract for MS-13 and are review-only (design D18/R-14 accepts the absence of a live DB, but not the absence of any assertion). Column metadata (`Table.columns`) is inspectable offline.
- Suggested fix: add a structural test asserting the six table objects' column names/types and the shared `FuelingsColumns` reuse; it pins the mapping against accidental drop/type change at zero cost.

**M-4. `llm/OllamaLlm.kt` KDoc misstates the T-15 verification result; no durable T-15 record.**
- Location: `src/main/kotlin/com/aiturbo/llm/OllamaLlm.kt:27-34`.
- Rationale: the KDoc says "the factory function `OllamaClient(baseUrl, timeoutConfig, ...)` … JVM name `ollamaClient`"; the resolved 1.2.0 jar exposes a constructor (verified with `javap`; the revised test report records the same, `04-test-report.md:76,86`). The code is correct — the wording is not. T-15 also asks for a recorded note; the note exists only as this inaccurate KDoc (now partly captured in the test report).
- Suggested fix: correct the KDoc to "the `OllamaClient(baseUrl, timeoutConfig, ...)` constructor" and keep the T-15 note (artifact `ai.koog:prompt-executor-ollama-client-jvm:1.2.0`, transitive compile classpath, 10 s `ConnectionTimeoutConfig`) in the test report.

**M-5. The 208-test baseline cannot be verified; evidence points to a spec accounting error (207).**
- Location: `04-test-report.md:67`; spec DoD item 1.
- Rationale: no pre-feature source is in git, so the baseline was reconstructed (203 + 3 + 1 = 207). All 30 pre-existing classes are present, none disabled, and per-class counts did not decrease — no test was lost; the difference is in the original count. The DoD's "208 pre-existing tests are all still present" is therefore unprovable as stated.
- Suggested fix: record the reconstruction in the test report as the accepted baseline explanation (as the tester did) and note the 1-test discrepancy in the feature docs; no code action.

**M-6. README wording defects contradict the shipped state.**
- Location: `README.md:406-410` (the paragraph "The weather tool records every request…" is duplicated verbatim); `README.md:452-453` ("login/connect/socket **and query timeouts**").
- Rationale: FR-14 requires no statement contradicted by the implementation. The stage repository sets connect/socket/login timeouts only (`db/PgDataSource.kt:30-34`); per design D6 there is no per-statement query timeout in the Exposed path.
- Suggested fix: delete the duplicated paragraph and reword to "login/connect/socket timeouts (`stageDb.timeoutSeconds`, default 10 s)".

**M-7. The R-13 assumption (element readable inside the real Koog strategy loop) is proven only with a fake agent offline.**
- Location: `src/test/kotlin/com/aiturbo/WeatherRoutesTest.kt:204-219` (fake `WeatherAgent` reads `currentLlmTarget()`); no test runs the real `KoogWeatherAgent` with `ProviderRoutingPromptExecutor` under `LlmTargetContext(LOCAL)`.
- Rationale: design R-13 accepts exactly this offline proof plus the live smoke, so this is a knowingly carried risk, not a violation — but the missing link is one cheap test away and is the load-bearing mechanism of FR-07.
- Suggested fix: add an offline chain test wiring `KoogWeatherAgent` + the real routing executor with a scripted local delegate under `withContext(LlmTargetContext(LOCAL))` and asserting the local model id reaches the delegate (same shape as `TraceChainIntegrationTest.kt:118-162`).

### Nit

**N-1. `LoggingPromptExecutor` KDoc is now provider-stale.** `src/main/kotlin/com/aiturbo/log/LoggingPromptExecutor.kt:18-27` still says "the single choke point for every DeepSeek call". It decorates both providers; only the stage names stay `deepseek-*` (ASM-03). Reword to "every provider call".

**N-2. Redundant catch and visibility drift in the weather repository.** `src/main/kotlin/com/aiturbo/db/ExposedWeatherRecordRepository.kt:62-66` catches `ExposedSQLException` and then `SQLException`, but `ExposedSQLException extends java.sql.SQLException` (verified in `exposed-core-1.5.0.jar`), so the first catch is unreachable as written unless kept for documentation; also `MAX_INSERT_ATTEMPTS` (`:17`) is now `private` while the design C5 lists it among the preserved seam names. Tests do not reference it, so there is no breakage — consider collapsing the catches and restoring `internal` for seam stability.

**N-3. Test report previously listed the wrong path for `OllamaConfig.kt`.** The file is `src/main/kotlin/com/aiturbo/OllamaConfig.kt` (root package, as design C2 requires); the revised report still carries a stale `llm/OllamaConfig.kt` reference in its scope list. Wording fix.

**N-4. Dangling KDoc reference.** `src/main/kotlin/com/aiturbo/db/ExposedWeatherRecordRepository.kt:70` — "Inserts via [insert]" links a symbol that does not exist in scope (the code calls `insertReturning`). Fix the link text.

## Scope check

`git diff` cannot isolate the feature (HEAD predates two features; most of `src/` is untracked), so the scope was checked against the plan's file inventory:

- **Created (all planned):** `config/EnvFile.kt`, `llm/LlmTarget.kt`, `llm/DeepSeekLlm.kt`, `llm/OllamaLlm.kt`, `llm/ProviderRoutingPromptExecutor.kt`, `OllamaConfig.kt`, `plugins/Di.kt`, `db/PgDataSource.kt`, `db/UsersTable.kt`, `db/ExposedWeatherRecordRepository.kt`, `db/StageTables.kt`, `db/ExposedStageFuelingRepository.kt`, `tools/GetWeatherTool.kt` (moved), `tools/FindFuelingTool.kt` (moved), `weather/WeatherUnavailableException.kt`, tests `EnvFileTest`/`OllamaConfigTest`/`LlmTargetTest`/`ProviderRoutingPromptExecutorTest`.
- **Modified (all planned):** `build.gradle.kts`, `application.conf`, `Application.kt`, `plugins/Routing.kt` (B-1 fix, post-review), `plugins/WeatherRouting.kt`, `plugins/FuelingRouting.kt`, `README.md`, tests T5–T10.
- **Deleted (all planned):** `db/JdbcWeatherRecordRepository.kt`, `db/JdbcStageFuelingRepository.kt`, `weather/GetWeatherTool.kt`, `fueling/FindFuelingTool.kt`, `weather/DeepSeekKoogLlm.kt`.
- **Unrelated/extra:** untracked repo scaffolding not part of the feature inventory — `.claude/`, `CLAUDE.md`, `CODE_STYLE.md`, `docs/`, `gradle/`, `.gitignore`. macOS `.DS_Store` files exist under `src/` but are covered by `.gitignore`. No unrelated source change was found in this change-set.

## Checks run

- `./gradlew test` → `BUILD SUCCESSFUL` (up-to-date).
- `./gradlew cleanTest test` → `BUILD SUCCESSFUL` (initial review; tests re-executed).
- `./gradlew cleanTest test` (post-B-1-fix re-check) → `BUILD SUCCESSFUL`, 2 executed; `@Test` count unchanged at 248.
- Test-count verification: 248 `@Test` across 34 classes (plus 2 helper files) — matches the revised test report.
- Sweeps: `io.insert-koin|org.koin|install(Koin)` = 0 (only the guard test's own regex tokens mention write/DDL names); `io.github.cdimascio` = 0; `DriverManager|PreparedStatement|ResultSet` in `src/main` = 0; no `Jdbc*Repository.kt`; no `@Ignore`/`@Disabled`.
- Jar verification via `javap`: `ExposedSQLException extends java.sql.SQLException`; `KoogHttpClientException extends java.lang.Exception`; `OllamaClient(baseUrl, timeoutConfig, …)` constructor present in `prompt-executor-ollama-client-jvm-1.2.0.jar`; `exposed-core-1.5.0.jar` has no `javatime` package, confirming the custom `TimestampColumnType` deviation is justified.
- Linters/static analysis: none configured in `build.gradle.kts` (no ktlint/detekt), so nothing to run; `StageReadOnlyGuardTest` is the repo's static guard and is green.
- Deviation claims spot-checked: custom `TimestampColumnType` (justified), Ollama client constructor form (correct; KDoc wording is M-4), `internal` endpoint visibility, Kodein `DependencyLoopException` descriptor workaround (`tools/GetWeatherTool.kt:97-117` + `Application.kt:164-182`; descriptor-equivalent, chain assertions stay byte-identical), test-side `di.direct.instance()`, `@EncodeDefault(NEVER)` canary (`WeatherRoutesTest.kt:196`), README/KDoc greps (M-4/M-6/N-1/N-3/N-4).

## Out of scope

- The repository's git state (two features' work uncommitted, most sources untracked) predates this review and is the user's commit decision; it is not a finding against the implementation.
- `.DS_Store` files under `src/` are macOS artifacts, already git-ignored.
- Historical feature docs (`koog-everything-and-logging`, `fueling-order-tool`) still describe Koin/JDBC wiring in places; the README already marks them as superseded and the spec excludes rewriting them.
- MS-01…MS-14 and the FR-12 benchmark (T-24/T-25) are the orchestrator's live deliverables; MS-09 is the live confirmation for the resolved B-1 and for the NFR-06 latency bound.

---

**Verdict: APPROVED** — B-1 resolved and verified in code with a green post-fix suite; 0 blockers, 0 majors, 7 minor and 4 nit findings recorded, none blocking. Live MS-09 remains the one confirmation step for the resolved failure contract.
