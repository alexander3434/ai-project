# Kodein + Exposed + Local Model Routing — Test Report

- **Feature:** kodein-exposed-ollama
- **Spec:** `docs/features/kodein-exposed-ollama/spec.md` (requirements + design + plan, T-01…T-25)
- **Date:** 2026-09-28
- **Scope of this report:** offline verification of T-01…T-23 (T-24/T-25 are the orchestrator's live smoke and benchmark and are listed under Manual checks). No server, no real database, no live LLM, no network was used.
- **Verdict (offline scope):** **PASS** — `./gradlew cleanTest test` and `./gradlew build` both `BUILD SUCCESSFUL`; **248 tests, 0 failures, 0 errors, 0 skipped** in 34 classes; every T-01…T-23 criterion is covered by a running test, an executable sweep, or documented code review; stack-hygiene greps all 0; no pre-existing test class deleted, none disabled. B-1 (local-provider failure mapping) is **fixed at code level** — `plugins/Routing.kt` now maps `KoogHttpClientException` to 503 "LLM provider is unavailable" before the `Throwable` catch-all; the live confirmation remains smoke step MS-09. Two coverage gaps (T-08 `pgDataSource`, T-10 stage tables have no direct automated test) and one 207-vs-208 baseline accounting nit are recorded under Bugs found.

## Scope

**Production changes verified** (the whole working tree is uncommitted and HEAD `3c56077` predates two features, so `git status`/`git diff` cannot isolate this change-set; attribution was done by reading every file listed in the plan and by modification time):

- `build.gradle.kts` (Kodein + Exposed added, koin-\*/dotenv removed), `src/main/resources/application.conf` (`ollama.*` block).
- DI: `plugins/Di.kt` (new), `Application.kt` (Kodein modules, tags, routing-executor binding), `plugins/Routing.kt`, `plugins/WeatherRouting.kt`, `plugins/FuelingRouting.kt`.
- DB: `db/PgDataSource.kt`, `db/UsersTable.kt`, `db/ExposedWeatherRecordRepository.kt`, `db/StageTables.kt`, `db/ExposedStageFuelingRepository.kt` (new); `db/JdbcWeatherRecordRepository.kt`, `db/JdbcStageFuelingRepository.kt` deleted.
- Config: `config/EnvFile.kt` (new); dotenv-kotlin call sites replaced.
- LLM: `llm/LlmTarget.kt`, `llm/OllamaLlm.kt`, `llm/OllamaConfig.kt`, `llm/ProviderRoutingPromptExecutor.kt`, `llm/DeepSeekLlm.kt` (new); `weather/DeepSeekKoogLlm.kt` deleted, `weather/WeatherUnavailableException.kt` extracted.
- Tools: `tools/GetWeatherTool.kt`, `tools/FindFuelingTool.kt` moved from `weather/`/`fueling/`.
- `README.md` (T-23).

**New test classes (4 files, 31 tests):** `EnvFileTest` (10), `LlmTargetTest` (7), `OllamaConfigTest` (3), `ProviderRoutingPromptExecutorTest` (11).

**Adapted pre-existing classes (5 files, +10 tests):** `AppModulesTest` (4→6), `FindFuelingToolTest` (11→12), `FuelingRoutesTest` (6→9), `WeatherRecordRepositoryTest` (4→5), `WeatherRoutesTest` (7→10). The other 25 pre-feature test classes were re-run unchanged and stay green.

**Deliberately not tested (not automatable offline, deferred to T-24/T-25):** live Ollama and DeepSeek calls, real local/stage PostgreSQL access and real DTO mapping (MS-12/MS-13), runtime timeout behaviour of `pgDataSource` and of the Ollama client (NFR-04/NFR-06 live parts), the FR-13 tool-calling verdict for `qwen3:8b`, the benchmark, and README rendering. No test performs network or DB I/O; `.env` may only be read via the existing `resolveApiKey`/`EnvFile` paths, never asserted on and never logged.

## Coverage of acceptance criteria

Legend: **PASS** = asserted by a running test; **PASS (review)** = verified by reading code/jars/config (no executable offline assertion exists); **PASS (sweep)** = verified by an executable grep/dependency/verdict check.

| Task / criterion | Test(s) | Level | Status |
|---|---|---|---|
| T-01 deps: kodein-di-jvm 7.32.0, exposed-core/jdbc 1.5.0, nothing else | `build.gradle.kts` review + runtime-classpath check | build | PASS (sweep) |
| T-02 Kodein container + module conversion, same object graph, tags, eager tool specs | `AppModulesTest` (6), `ApplicationTest` (5), `FuelingModulesTest` (5), `TraceChainIntegrationTest` (6), `FuelingChainIntegrationTest` (5) | integration | PASS |
| T-03 routes resolve via `by di.instance()`, partial graph still registers | `WeatherRoutesTest`, `FuelingRoutesTest` (agent-only module registered), integration tests | e2e | PASS |
| T-04 the 7 Koin-building test files adapted, no assertion deleted | the 7 files run green; class counts ≥ their pre-feature counts | integration | PASS |
| T-05 koin-\* removed; grep sweep 0; verdict | grep `io.insert-koin\|org.koin\|install(Koin)` = 0; build green | sweep | PASS (sweep) |
| T-06 `EnvFile` reader per design C8 + `loadDotenv` swap | `EnvFileTest` (10: blanks, comments, quotes, duplicates, missing file, no interpolation, empty value, first `=`, no-`=` line) | unit | PASS |
| T-07 dotenv-kotlin removed | grep `io.github.cdimascio` = 0; build green | sweep | PASS (sweep) |
| T-08 `pgDataSource` over `PGSimpleDataSource`, timeouts only when set, no `DriverManager` | **no direct automated test** (0 test references); code review of `db/PgDataSource.kt`; `DriverManager` grep = 0 | review | PASS (review) — gap |
| T-09 Exposed weather repo: `insertReturning`, cause-chain `23505`, retries, `recent` ordering/DTO | `WeatherRecordRepositoryTest` (5, incl. wrapped-cause unique violation) | unit | PASS |
| T-10 stage table mappings per C6 column sets/types | **review of `db/StageTables.kt` only** (R-14 acknowledged in the spec: no offline DB to validate against) | review | PASS (review) — gap |
| T-11 Exposed stage repo: `lowerCase() eq`, enum tables, `DESC NULLS LAST`, no writes, `DatabaseUnavailableException` | `StageReadOnlyGuardTest` (4), `FuelingModulesTest` (binding is `ExposedStageFuelingRepository`), `FuelingChainIntegrationTest` (5); live DTO mapping → MS-12/MS-13 | unit + static + integration | PASS (test+review; live part deferred) |
| T-12 guard test adapted to Exposed mechanism, weather-repo test repointed | `StageReadOnlyGuardTest` (4, scans `ExposedStageFuelingRepository.kt`/`StageTables.kt`/`StageDbConfig.kt`/`StageFuelingRepository.kt`) | static | PASS |
| T-13 Exposed batch verdict + JDBC-symbol sweep | suite green; `DriverManager\|PreparedStatement\|ResultSet` = 0; no `Jdbc*Repository.kt` file | sweep | PASS (sweep) |
| T-14 tools moved to `com.aiturbo.tools`; JSON contracts byte-identical | package grep (4 files, `com.aiturbo.tools`); `GetWeatherToolTest` (8), `FindFuelingToolTest` (12), `KoogWeatherAgentTest`/`KoogFuelingAgentTest` (7/7); `deepseek-request tools=[…]` assertions in `TraceChainIntegrationTest`/`FuelingChainIntegrationTest`; tool JSON resources untouched (mtime) | unit + integration | PASS |
| T-15 Ollama client API verified against the resolved jar | `llm/OllamaLlm.kt` KDoc note; independently re-verified: `ai.koog.prompt.executor.ollama.client.OllamaClient` constructor `(baseUrl, timeoutConfig)` exists in `prompt-executor-ollama-client-jvm-1.2.0.jar`; no `OllamaLLMClient` | review | PASS (review) |
| T-16 `deepseekModel`/`deepseekPromptExecutor` moved verbatim; exception extracted, 503 mapping intact | `RawDeepSeekCallTest` (4), `LoggingPromptExecutorTest` (8), `LlmTimeZoneResolverTest` (12), agent tests, both chain-integration tests; `Routing.kt` mapping review | unit + integration | PASS |
| T-17 `OllamaConfig` defaults + `from(config)` + `chatEndpoint`; `ollama.*` conf; `ollamaModel`/`ollamaPromptExecutor`, ≤10 s timeouts | `OllamaConfigTest` (3), `AppModulesTest` (local endpoint label `http://localhost:11434/api/chat`) | unit | PASS |
| T-18 `LlmTarget.fromRequest`, `ALLOWED_VALUES`, context element, DEFAULT DEEPSEEK | `LlmTargetTest` (7) | unit | PASS |
| T-19 `ProviderRoutingPromptExecutor`: overload set, LOCAL→local model substitution, DEEPSEEK passthrough, blank-key guard before delegates/log, `close()` both, collection-time streaming read | `ProviderRoutingPromptExecutorTest` (11) | unit | PASS |
| T-20 DI wiring of both delegates, endpoint labels, tags, unconditional `WeatherAgent`, `fuelingModule` guard | `AppModulesTest` (6, asserts routing type, tags, endpoint labels) | unit | PASS |
| T-21 `model` field (`@EncodeDefault(NEVER)`), 400 for unknown, context wrap | `WeatherRoutesTest` (10: `POST weather with an unknown model returns 400 without calling the agent` incl. the frozen inbound-body canary, `… padded local model …`, `… without or with a blank model targets deepseek`), `FuelingRoutesTest` (9: same matrix on `/fueling`) | e2e | PASS |
| T-22 offline verdict + stack hygiene + test-count baseline | this section's commands, greps and counts | sweep | PASS (sweep) |
| T-23 README/docs consistency, no latency claims | review of `README.md` (stack line, tools package, request-field matrix, `ollama.*`/`OLLAMA_*`, per-provider example chains, historical Koin/JDBC mentions marked superseded, secrets rules) | review | PASS (review) |

T-24 (live smoke MS-01…MS-14 incl. the FR-13 verdict) and T-25 (benchmark) are outside the offline scope and are listed under Manual checks.

## Results

**Commands run (observed):**

- `./gradlew cleanTest test` → `BUILD SUCCESSFUL` (re-run after the B-1 fix: same verdict)
- `./gradlew build` → `BUILD SUCCESSFUL`
- `build/test-results/test` XMLs: **34 classes, tests=248, failures=0, errors=0, skipped=0** (post-B-1-fix run).

**Baseline accounting (T-22):** 30 pre-feature test classes are all still present (none deleted, 0 `@Ignore`/`@Disabled`); 4 new classes were added and 5 pre-existing classes gained tests. Reconstructing the pre-feature count from the previous feature report (203) plus `StageEpochMappingTest` (3) plus `FuelingReportTest` (1) gives **207**, while the spec states 208; the observed 248 = 207 + 31 new-class tests + 10 added-to-existing tests, consistent with the reconstruction. Every class shows at least its last documented count, so this looks like a 1-test difference in the original accounting, not a deleted test — flagged for the orchestrator, no action needed for the verdict.

**Stack-hygiene sweep (all observed, `src/main`/`src/test`):** `io.insert-koin|org.koin|install(Koin)` = 0; `io.github.cdimascio` = 0; `DriverManager|PreparedStatement|ResultSet` in `src/main` = 0; `Jdbc*Repository.kt` files = 0; all tool files under `com.aiturbo.tools` (4 files: `GetWeatherTool.kt`, `FindFuelingTool.kt`, `ToolJsonRenderer.kt`, `ToolSpec.kt`), no tool class left in `weather`/`fueling`; `@Ignore`/`@Disabled` = 0; tool JSON resources untouched.

**Dependency check:** runtime classpath contains `org.kodein.di:kodein-di-jvm:7.32.0` (+`kaverit-jvm:2.12.0` transitive), `org.jetbrains.exposed:exposed-core:1.5.0`, `exposed-jdbc:1.5.0`, and the Ollama client `ai.koog:prompt-executor-ollama-client-jvm:1.2.0` as a transitive of `koog-agents`; no koin-\*, no dotenv, no other additions — matches NFR-02. Kotlin stdlib resolves to 2.3.21 (the plan mentioned 2.3.20); benign, no failure.

**Deviation assessment.** The developer's "eight documented deviations" list was not found anywhere in the repository (docs, notes, code comments), so only the three deviations named by the orchestrator plus deviations I identified from the diff could be assessed:

1. *`UsersTable` uses a custom `TimestampColumnType` instead of the `datetime()` DSL* — justified: the java.time column DSL ships in a separate Exposed artifact that is not on the classpath (`exposed-core` + `exposed-jdbc` only, NFR-02). The custom type reproduces the previous JDBC read path (`java.sql.Timestamp` → `LocalDateTime`), no DDL is issued. **Accepted.**
2. *`OllamaClient` used as a constructor rather than a factory function* — verified against `prompt-executor-ollama-client-jvm-1.2.0.jar`: `ai.koog.prompt.executor.ollama.client.OllamaClient(baseUrl, timeoutConfig)` is the real API, the code form is correct and explicit timeouts are applied. **Accepted** (minor: the KDoc in `llm/OllamaLlm.kt` calls it a "factory function … JVM name ollamaClient", which does not match the jar — wording fix suggested, no functional impact).
3. *Kodein `DependencyLoopException` workaround: `toolDescriptorsProvider = { … }` plus `weatherToolDescriptor(spec)` / `DescriptorOnlyWeatherTool` in `GetWeatherTool.kt`* — descriptor-equivalent to the real tool (same name/description/parameter schema; the byte-level `deepseek-request tools=[…]` assertions in the chain tests stay green), eager tool-spec singletons removed the loop. **Accepted** for the offline scope; MS-05/MS-06 should confirm tool listing and execution live.
4. Other differences identified from the diff are all prescribed by the plan (`EnvFile` shape per C8, guard ordering in `ProviderRoutingPromptExecutor` per T-19, `Application.module(overrideModules)` signature kept, `resolveApiKey` unchanged), i.e. not deviations.

## Bugs found

1. **B-1 — fixed at code level (was: an unreachable Ollama returned HTTP 500, not the documented 503; FR-08, ASM-09, MS-09).** Original defect: `plugins/Routing.kt` mapped only `DatabaseUnavailableException` and `WeatherUnavailableException` to 503; a failed local-provider call surfaces as `ai.koog.http.client.KoogHttpClientException` (verified: `extends java.lang.Exception`, `http-client-core-jvm-1.2.0.jar`) or a Ktor `IOException`, and everything unmatched fell through to `exception<Throwable>` → 500. Fix verified by reading `plugins/Routing.kt` (the only file changed): `exception<KoogHttpClientException>` now responds 503 "LLM provider is unavailable", placed before the `Throwable` catch-all; post-fix `./gradlew cleanTest test` is green with the same 248/248. The suite cannot reach this path (the real client is built inside the `ollamaPromptExecutor` factory), so **live confirmation remains MS-09** — including that a refused connection indeed surfaces as `KoogHttpClientException` rather than a raw Ktor exception.
2. **Coverage gap: T-08 `pgDataSource` has no direct automated test** (0 test references). Verified by review only: `PGSimpleDataSource`, timeouts set only when `timeoutSeconds != null`, `AI_TURBO_APPLICATION_NAME = "ai-turbo"`, no `DriverManager`. Runtime timeout behaviour belongs to MS-12/MS-13.
3. **Coverage gap: T-10 stage table mappings verified by review only** (no offline DB; R-14 acknowledged in the spec). Live validation is MS-12/MS-13.
4. **Minor accounting nit:** pre-feature test count reconstructs to 207 vs the spec's stated 208 (see Results). No class lost tests, no test deleted or disabled.
5. **Minor documentation defect:** `llm/OllamaLlm.kt` KDoc describes the Ollama client as a "factory function (JVM name `ollamaClient`)" while the resolved 1.2.0 jar exposes a constructor `OllamaClient(...)` — wording only, code is correct.

## Manual checks

Deferred to the orchestrator (T-24/T-25; exact steps in the spec's smoke checklist and benchmark sections):

1. **MS-09 (highest value) — graceful local failure:** stop Ollama, start the app, `POST /weather {"message":"Какая погода в Москве?","model":"local"}`. The spec requires **503** with a plain-language message and no fallback to DeepSeek; the chain must show one `stage=deepseek-request` line with `endpoint=http://localhost:11434/api/chat`. This is the live confirmation for B-1 (the code-level 503 mapping is already in place). Restore Ollama afterwards.
2. **MS-01…MS-08, MS-10, MS-11, MS-14:** live smoke for both providers. Local-model steps run only if the Ollama probe (MS-01) answers — Ollama is user-managed, so record **BLOCKED-ON-USER** with the MS-01 instructions if it does not. Includes the FR-13 verdict: one recorded run per endpoint showing a tool call for `qwen3:8b` (R-1).
3. **MS-12/MS-13:** real local-db insert/read and real stage lookup (covers T-08/T-10/T-11 live parts).
4. **T-25 benchmark:** FR-12/ASM-13 protocol (1 warm-up + ≥5 measured sequential requests per provider per endpoint, same prompt per endpoint, median + min/max, date/machine/versions, single-machine caveat, 2–3 line conclusion).
5. **README:** visual/doc review on a rendered view (content was verified textually under T-23).

---

## Live smoke and benchmark record (2026-09-28, orchestrator)

**Environment:** server on :8080 (fresh build, 249 tests green); Ollama installed but NOT running (user-managed); local Docker Postgres (TimeAndData) running; stage PostgreSQL 10.138.11.60:25432 **refusing connections from the machine** (environmental — VPN/stage host down; verified both from host and from the Docker container).

| Step | Result |
|---|---|
| MS-01 Ollama probe | `http://localhost:11434/api/tags` unreachable → local steps BLOCKED-ON-USER. To run them: `brew services start ollama` (or `ollama serve`), check `ollama list` has `qwen3:8b` (else `ollama pull qwen3:8b`). |
| MS-02 health + `/time` | root 200, `/time?location=Moscow` 200 |
| MS-03 default provider | 200, 5.83s, chain `endpoint=https://api.deepseek.com/chat/completions` `model=deepseek-flash` |
| MS-04 explicit `"model":"deepseek"` | 200, 4.02s |
| MS-05 local weather | BLOCKED-ON-USER (Ollama down) |
| MS-06 local fueling | BLOCKED-ON-USER (Ollama down) |
| MS-07 padded `" LOCAL "` | BLOCKED-ON-USER (Ollama down) |
| MS-08 unknown `"model":"gpt-4"` | **400** `{"error":"Field 'model' must be one of: local, deepseek"}`, no LLM line |
| MS-09 Ollama stopped | First run exposed a real bug: `LLMClientException` was unmapped → 500. **Fixed** (StatusPages maps both `LLMClientException` and `KoogHttpClientException` → 503) + regression test. Re-run: **503** `{"error":"LLM provider is unavailable"}` in **0.23s** ✓ |
| MS-10 chain integrity | Verified on MS-03/MS-09 runs: one `req=` id, `inbound → deepseek-request → deepseek-response → … → outbound`, `endpoint=`/`model=` identify the provider |
| MS-11 secrets | stage password and API key: **0 occurrences** in the smoke log |
| MS-12 history shape | 200 `{"records":[…]}` (after restarting the local Docker Postgres) |
| MS-13 stage fidelity | BLOCKED-ENVIRONMENTAL: stage host refuses connections; app degrades gracefully ("stage временно недоступно", 200, within budget) — correct FR-12 behavior |
| MS-14 FR-13 tool-calling | DeepSeek side confirmed earlier runs; **qwen3:8b tool-calling verdict pending** (BLOCKED-ON-USER: start Ollama, send the MS-05/MS-06 bodies, check `tool_calls=[…]` in the log) |

### Benchmark (T-25) — DeepSeek side; local side pending Ollama

Method: 1 warm-up + 5 sequential requests per provider, client-visible `time_total`, identical prompt.
Machine: Apple Silicon (arm64), macOS Darwin 24.6.0. Server: ai-turbo (Kotlin 2.3.10/Ktor 3.3.3/Koog 1.2.0).

| Endpoint | Provider | model id | runs (s) | median | min | max |
|---|---|---|---|---|---|---|
| /weather | deepseek | deepseek-flash | 3.12, 3.87, 4.21, 3.40, 3.17 | **3.40** | 3.12 | 4.21 |
| /weather | local | qwen3:8b | BLOCKED-ON-USER (Ollama down) | — | — | — |
| /fueling | deepseek | deepseek-flash | BLOCKED-ENVIRONMENTAL (stage down → degraded path) | — | — | — |
| /fueling | local | qwen3:8b | BLOCKED-ON-USER | — | — | — |

Conclusion (partial): DeepSeek `/weather` full tool flow ≈ 3.1–4.2s (two LLM round-trips). Local comparison follows when the user starts Ollama and stage is reachable — run the same loops with `"model":"local"` and the fueling GUID, and append the rows.

---

## Local-model verification + benchmark update (2026-09-28, after user started Ollama)

- **MS-01**: Ollama up, `qwen3:8b` listed.
- **MS-05/MS-07**: `model=local` on `/weather` → **200**, plain-language answer from real tool data; chain via `endpoint=http://localhost:11434/api/chat model=qwen3:8b`.
- **MS-14 / FR-13 gate: PASSED** — `qwen3:8b` emits tool calls (`tool_calls=[{"name":"get_weather",…}]`), full chain `inbound → deepseek-request → deepseek-response → tool → db → … → outbound` with one `req=`.
- **MS-09**: unreachable-Ollama case re-confirmed fast (503 ≤ 0.3 s, connect refusal); generation-side timeouts fixed along the way: request/socket wait now `OLLAMA_TIMEOUT_SECONDS` (default 120 s), connect stays 10 s (two live bugs found and fixed, 250 tests green).
- Local-model latency is CPU-bound: first request 108 s (model load + 2 tool rounds), warm requests ≈ 40–49 s.

| Endpoint | Provider | model id | runs (s) | median | min | max |
|---|---|---|---|---|---|---|
| /weather | deepseek | deepseek-flash | 3.12, 3.87, 4.21, 3.40, 3.17 | 3.40 | 3.12 | 4.21 |
| /weather | local | qwen3:8b (Ollama 0.34.4, CPU) | 44.46, 40.42, 42.95, 48.63, 42.48 | **42.95** | 40.42 | 48.63 |
| /fueling | deepseek | deepseek-flash | BLOCKED-ENVIRONMENTAL (stage 10.138.11.60:25432 unreachable from the machine) | — | — | — |
| /fueling | local | qwen3:8b | BLOCKED-ENVIRONMENTAL (same) | — | — | — |

**Conclusion:** on this machine qwen3:8b answers with the same quality of tool flow but is ~12× slower than the DeepSeek API for `/weather` (median 42.95 s vs 3.40 s, CPU inference, no GPU acceleration) — expected for an 8B model on CPU. DeepSeek stays the right default; the local model is a working offline/cheap alternative. A GPU-accelerated Ollama (or a smaller model like qwen3:1.7b/4b) would close the gap — worth measuring if needed.
