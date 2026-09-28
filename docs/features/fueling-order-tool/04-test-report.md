# Fueling Order Lookup Tool — Test Report

- **Feature:** fueling-order-tool
- **Spec:** `docs/features/fueling-order-tool/spec.md` (requirements + design + plan, T-01…T-17)
- **Date:** 2026-09-23
- **Scope of this report:** offline verification of T-01…T-16 (the criteria marked "live (M-05)" are listed under Manual checks). No server, no stage DB, no live LLM, no network was used.
- **Verdict (offline scope):** **PASS** — `./gradlew clean build --offline` green; **203 tests, 0 failures, 0 errors, 0 skipped** (123 pre-existing + 80 new), all task criteria T-01…T-16 covered by ≥1 automated test or documented code review; frozen tests and `build.gradle.kts` untouched; secrets search 0 hits; stage sources read-only. No implementation defect found. T-17 (live stage smoke, M-05) is deferred to the orchestrator and remains **blocked until the operator adds `STAGE_DB_PASSWORD` to the git-ignored `.env`**. See Bugs found for three coverage caveats and one minor documentation defect.

## Scope

**Changed/added production files verified** (`git status` / `git diff` vs HEAD `3c56077`; the whole working tree is uncommitted, so files not in HEAD cannot be diffed — verified by review and by modification time):

- New: `src/main/kotlin/com/aiturbo/db/StageDbConfig.kt`, `db/StageFuelingRepository.kt`, `db/JdbcStageFuelingRepository.kt`, `fueling/FuelingId.kt`, `fueling/FindFuelingTool.kt`, `fueling/FuelingReport.kt`, `fueling/FuelingAgent.kt`, `plugins/FuelingRouting.kt`, `src/main/resources/tools/find-fueling-tool.json`.
- Modified: `Application.kt`, `plugins/Routing.kt`, `log/TraceLog.kt`, `tools/ToolSpec.kt`, `resources/application.conf`, `README.md`, `src/test/kotlin/com/aiturbo/DeepseekConfigTest.kt`, `ToolSpecTest.kt`, `TraceLogTest.kt`.
- New test classes: `FuelingTestFixtures.kt`, `StageDbConfigTest.kt`, `FuelingIdTest.kt`, `FuelingReportTest.kt`, `FindFuelingToolTest.kt`, `FuelingRoutesTest.kt`, `KoogFuelingAgentTest.kt`, `FuelingModulesTest.kt`, `FuelingChainIntegrationTest.kt`, `StageReadOnlyGuardTest.kt`.
- Frozen/untouched regression files re-run: `AppModulesTest.kt` (4 tests), `TraceChainIntegrationTest.kt` (6 tests), the whole `weather/` package and the weather repository, `build.gradle.kts` (empty `git diff`), `docs/features/`.

**Deliberately not tested (not automatable offline, deferred to T-17/M-05):**

- Real stage SQL against PostgreSQL (table/column names, partition-parent read, database name `fueling`, lowercase `fueling_id` assumption R-3/R-4) — the JDBC repository is only exercised by review and the static guard; no test opens a socket.
- Live DeepSeek tool calling with `deepseek-flash` (R-1) and the model lines on a real request (FR-13 live part).
- Real-row rendering cross-check (FR-07 timing/values), timeout budget (NFR-04: unreachable-host ≈10 s / endpoint <30 s), row-count invariance (NFR-03 live part), Postman walkthrough verbatim (FR-15 live part) and the secrets grep over a captured live log (NFR-02 live part).
- Logback console formatting — only the event stream on `com.aiturbo.trace` is asserted.

## Coverage of acceptance criteria

Legend: **PASS** = asserted by a running test; **PASS (review)** = verified by reading code/tests (no executable offline assertion possible); **PASS (test+review)** = partly asserted, partly reviewed.

### Task acceptance criteria (T-01…T-16)

| Task / criterion | Test(s) | Level | Status |
|---|---|---|---|
| T-01 `deepseek.model` default `deepseek-flash`, `${?DEEPSEEK_MODEL}` intact | `DeepseekConfigTest.the default model is the cheapest deepseek tier`, `an explicit model value wins over the default`; conf line review | unit + review | PASS (test+review) |
| T-01 `DeepseekConfig.DEFAULT_MODEL == "deepseek-flash"` | same two tests | unit | PASS |
| T-02 `StageDbConfig.from` reads `stageDb.*`, defaults (25432/`fueling`/10) | `StageDbConfigTest.from reads the stage values…`, `the defaults match…`, `from falls back to the defaults…`, `non-numeric port and timeout…` | unit | PASS |
| T-02 `jdbcUrl == jdbc:postgresql://<host>:<port>/<db>` | same tests (both explicit and default URL) | unit | PASS |
| T-02 password resolution config → env → file, blank when unset, no dev default | `config password value takes priority…`, `env password is used when the config value is blank`, `dotenv password is used when config and env are blank`, `blank password when nothing is configured…` | unit | PASS |
| T-02 `application.conf` `stageDb` block, empty password, `${?STAGE_DB_*}`; `db.*` unchanged | conf inspection (`git diff` shows only the added block + model line); structural secret checks | review | PASS (review) |
| T-02 construction does no I/O; no tracked file has the password | data class review; `StageDbConfig` default `""`; `.env` git-ignored; secret search | review/static | PASS (review) |
| T-03 canonicalize accepts lower/upper/trimmed 8-4-4-4-12, lowercases | `FuelingIdTest.a canonical lowercase guid…`, `an uppercase guid…`, `surrounding whitespace…`, `any uuid version…` | unit | PASS |
| T-03 rejects compact 32-hex, braces, `urn:uuid:`, blank, `1-1-1-1-1`, non-hex; never throws | `a compact 32 character id…`, `braced and urn forms…`, `blank values…`, `short and malformed groups…`, `a numeric non-guid id…`, `non-hex characters…`, `canonicalize never throws` | unit | PASS |
| T-03 both exceptions with the design constructors | `the exceptions carry the design constructors`, `the exceptions are runtime exceptions` | unit | PASS |
| T-04 three `fuelingLookup*` functions, one `stage=db` line each, `capped` only when capped, `-` ids | `TraceLogTest.the fueling lookup lines carry the outcome, tables and counts`, `a missing id or reason renders as a dash…`, `a long unavailable reason is truncated` | unit | PASS |
| T-04 `toolDb` untouched | `git diff TraceLog.kt` has no deletions; existing `TraceLogTest` cases green | review | PASS (review) |
| T-05 all DTOs/fields per design §Data model; single interface method | code review of `StageFuelingRepository.kt` (22 `FuelingRecord` columns, epochs `Long?`, jsonb text, BigDecimal amounts) + all exercising tests compile/run | review | PASS (review) |
| T-06 found text, all fields, `-` for missing, related blocks with totals | `FuelingReportTest.the found header… / a large jsonb…`, `the related blocks…`, `a feedback row…`, `null values render as a dash` | unit | PASS |
| T-06 epochs in window → `yyyy-MM-dd HH:mm:ss UTC`, outside → `<n> (raw)`, null → `-` | `formatEpochMillis converts the window boundaries…`, `epoch values outside the plausibility window stay raw` | unit | PASS |
| T-06 jsonb cap 2000 + marker; 50-row cap with real totals; not-found text; multiple matches | `a large jsonb field is capped…`, `related rows are capped per table…`, `a not found result names every searched table`, `several matches are labelled…`, `rendering is deterministic…` | unit | PASS |
| T-07 six `SELECT`s fixed order, prepared statements, GUID bound, enum table names, connection per call + timeouts + `ApplicationName`; `SQLException` → `DatabaseUnavailableException` | `StageReadOnlyGuardTest` (4 tests) + repository review | static + review | PASS (test+review) |
| T-08 resource `find_fueling` with designed description/`orderId`/required; loader constant; defaults unchanged | `ToolSpecTest.the shipped fueling resource…`, `loading the fueling resource writes one info line…`, `the default resource path and the weather resource are unchanged`; pre-existing fail-fast tests | unit | PASS |
| T-09 fake repository (seedable, unavailable mode, call list) + sample rows | `FuelingTestFixtures` review; consumed by `FindFuelingToolTest`, `KoogFuelingAgentTest`, `FuelingChainIntegrationTest` | review | PASS (review) |
| T-10 descriptor from resource; one canonical repository call in `Dispatchers.IO`; one `stage=db` per outcome; invalid id → no call/no `db` line | `FindFuelingToolTest` (11 tests: descriptor, found line, uppercase/padded, IO thread, correlation id, multi-match, capped, not found, invalid, empty, unavailable) | unit | PASS |
| T-11 system prompt verbatim, `find_fueling`-only registry, temperature 0.0, maxIterations 10, id `fueling-agent`, `maxToolRounds` 3, `TraceLog.tool` per call | `KoogFuelingAgentTest` (7 tests: tool line with GUID, correlation id, failing tool `is_error=true`, `db` before `tool`, system prompt, registry tools, non-convergence) + code review (prompt text compared with the design) | integration (real `AIAgent`) + review | PASS (test+review) |
| T-12 `POST /fueling` 200 `{message,answer}`; blank 400; malformed JSON 400; blank key 503; one trace id | `FuelingRoutesTest` (6 tests incl. trimming and the weather route still registered); `Routing.kt` registers `fuelingRoutes()` | integration (testApplication) | PASS |
| T-13 additive `fuelingModule`; named qualifiers; `appModules` unchanged; composed default branch | `FuelingModulesTest` (5 tests: graph offline, both registries apart, both specs loaded once, JDBC repo, blank-key lambda, Koog agent) | integration (Koin) | PASS |
| T-14 eight stages in order, one id, `tools_count=1` + resource definition, `db` counts, `tool` GUID/`is_error`, invalid id without `db`, truncation, no secrets, blank-key 503 | `FuelingChainIntegrationTest` (5 tests) | e2e offline | PASS |
| T-15 README API row, fueling section (input/output), Postman recipe + other cases, `deepseek-flash` 8-line trace, `stageDb.*`/`STAGE_DB_*` table, `.env` step, test list, docs links | README inspection (lines 44–120, 198–235, 309–346, 386–404, 421–478, 486–556, 574–600); no statement contradicts shipped behaviour; placeholders only, no secret | review | PASS (review) — minor defect, see Bugs |
| T-16 clean-build suite offline, 123 baseline green, frozen files unchanged, secrets 0 hits, read-only scan, traceability | this report's Results + baseline accounting | process | PASS |

### Requirement traceability (FR/NFR, from the spec matrix)

| Requirement | Covered by | Status |
|---|---|---|
| FR-01 `/fueling` 200/400/503 contract | `FuelingRoutesTest`, `FuelingChainIntegrationTest` | PASS |
| FR-02 tool registered, name/description from the resource | `ToolSpecTest`, `FuelingModulesTest`, `FindFuelingToolTest`, `FuelingChainIntegrationTest` (`tools_count=1` + `"name":"find_fueling"` + description) | PASS |
| FR-03 single `orderId` string, canonical GUID, trimmed, case-insensitive | `FuelingIdTest`, `FindFuelingToolTest`, `ToolSpecTest` (parameter schema) | PASS |
| FR-04 malformed → no lookup, no `stage=db`, `is_error=true`, plain answer | `FindFuelingToolTest`, `FuelingChainIntegrationTest` (invalid-id run), `KoogFuelingAgentTest` (failing tool) | PASS |
| FR-05 all three source tables searched, matches labelled | `FindFuelingToolTest` (multi-match, source names), `FuelingReportTest` (labelled records) | PASS offline; real DB read deferred to T-17 |
| FR-06 related rows collected by `fueling_id`, empty is normal | fixtures + `FindFuelingToolTest`, `FuelingReportTest` (2 events / 1 token / 0 feedback, empty case) | PASS |
| FR-07 every documented column, readable timestamps, no raw 13-digit leak | `FuelingReportTest` (all 22 fields, UTC/raw/`-`, jsonb cap, `assertFalse(\d{13})`) | PASS offline; real-row cross-check deferred to T-17 |
| FR-08 tool result fed back; second round-trip is the summary | `FuelingChainIntegrationTest` (two `deepseek-request/response` pairs, tool message), `KoogFuelingAgentTest` | PASS offline; model wording deferred to T-17 |
| FR-09 system prompt forces the tool call | `KoogFuelingAgentTest.the first prompt carries the system prompt…` + prompt review | PASS |
| FR-10 read-only stage access | `StageReadOnlyGuardTest` (4 tests) + repository review | PASS |
| FR-11 stage settings configurable, password env/`.env`, no reuse of `db.*` | `StageDbConfigTest`, conf review, `FuelingModulesTest` (separate repo binding) | PASS |
| FR-12 graceful degradation, app starts without stage DB | `FindFuelingToolTest` (unavailable), `FuelingChainIntegrationTest` (stage failure 200), `FuelingModulesTest` (no connection at startup) | PASS offline; timeout budget deferred to T-17 |
| FR-13 `deepseek-flash` default, `DEEPSEEK_MODEL` override | `DeepseekConfigTest` (+2), `FuelingChainIntegrationTest` (`model=deepseek-flash` lines) | PASS (conf default by review; live lines deferred) |
| FR-14 one correlation id, full chain, no secrets | `FuelingChainIntegrationTest` (8 stages, `assertOneId`), `TraceLogTest` | PASS offline (live log deferred) |
| FR-15 documentation + Postman example | README review (`01-requirements.md` mirrored) | PASS (review); live use deferred to T-17 |
| FR-16 existing behaviour preserved | `AppModulesTest`, `TraceChainIntegrationTest`, `ApplicationTest`, `WeatherRoutesTest` and the rest of the baseline green and unmodified | PASS |
| FR-17 pipeline artifacts | `01/02/03/spec.md` present, consistent (diff vs spec empty except headers/separators) | PASS (review) |
| NFR-01 observability 8-stage chain | `FuelingChainIntegrationTest`, `TraceLogTest` | PASS offline |
| NFR-02 secrets only env/`.env`, 0 in tracked files/logs/responses | structural checks (below); chain test fixtures 0 hits (weak assertion, see Bugs) | PASS offline |
| NFR-03 data safety — SELECT-only, no writes | `StageReadOnlyGuardTest`; live row-count check deferred to T-17 | PASS offline |
| NFR-04 timeouts 10 s / <30 s budget | code review (connect/socket/login/query timeouts); measurement deferred to T-17 | PASS (review) |
| NFR-05 offline suite, 123 baseline green, no stage DB/`.env` needed | observed runs + baseline accounting | PASS |
| NFR-06 no new dependency, endpoints unchanged | `git diff build.gradle.kts` empty; `WeatherRoutesTest`/`ApplicationTest` green | PASS |
| NFR-07 cheapest model everywhere | `DeepseekConfigTest`, `FuelingChainIntegrationTest`; no fallback in code | PASS offline |
| NFR-08 robustness (malformed, not found, oversized jsonb, DB errors never 500) | `FuelingIdTest`, `FuelingReportTest` (caps), `FindFuelingToolTest`, `FuelingRoutesTest` (400/503), `FuelingChainIntegrationTest` (truncation) | PASS |
| NFR-09 docs/process | README review, docs present, this report | PASS |

## Results

Commands actually run in `/Users/murkka/Work/ai-project` (offline; no server, no stage DB, no live LLM; `.env` present but **without** `STAGE_DB_PASSWORD`):

| Command | Observed result |
|---|---|
| `./gradlew clean test --offline` | `BUILD SUCCESSFUL in 5s`; the 29 fresh `build/test-results/test/TEST-*.xml` files contain **203 tests, 0 failures, 0 errors, 0 skipped** |
| `./gradlew clean build --offline` | `BUILD SUCCESSFUL in 7s` (10 tasks executed: jar, distTar, distZip, test, check, build) |

Per-class counts (observed; baseline/new status in parentheses):

| Class | Tests | Class | Tests |
|---|---|---|---|
| `AppModulesTest` | 4 (baseline) | `KoogWeatherAgentTest` | 7 (baseline) |
| `ApplicationTest` | 5 (baseline) | `LlmTimeZoneResolverTest` | 12 (baseline) |
| `DbConfigTest` | 7 (baseline) | `LoggingPromptExecutorTest` | 8 (baseline) |
| `DeepseekConfigTest` | 6 (baseline 4 +2, T-01) | `OpenMeteoWeatherClientTest` | 6 (baseline) |
| `FindFuelingToolTest` | 11 (new, T-10) | `RawDeepSeekCallTest` | 4 (baseline) |
| `FuelingChainIntegrationTest` | 5 (new, T-14) | `RequestTracingTest` | 6 (baseline) |
| `FuelingIdTest` | 14 (new, T-03) | `StageDbConfigTest` | 8 (new, T-02) |
| `FuelingModulesTest` | 5 (new, T-13) | `StageReadOnlyGuardTest` | 4 (new, T-07) |
| `FuelingReportTest` | 12 (new, T-06) | `TimeServiceTest` | 5 (baseline) |
| `FuelingRoutesTest` | 6 (new, T-12) | `TimeZoneResolverTest` | 5 (baseline) |
| `GetWeatherToolTest` | 8 (baseline) | `ToolJsonRendererTest` | 4 (baseline) |
| `KoogFuelingAgentTest` | 7 (new, T-11) | `ToolSpecTest` | 9 (baseline 6 +3, T-08) |
| | | `TraceChainIntegrationTest` | 6 (baseline) |
| | | `TraceFormatsTest` | 6 (baseline) |
| | | `TraceLogTest` | 12 (baseline 9 +3, T-04) |
| | | `WeatherRecordRepositoryTest` | 4 (baseline) |
| | | `WeatherRoutesTest` | 7 (baseline) |

### 123-baseline accounting (T-16, NFR-05)

The pre-feature suite was 123 tests in 20 classes (recorded in `docs/features/koog-everything-and-logging/04-test-report.md`). Every baseline class still has its exact previous count — `AppModulesTest` 4, `ApplicationTest` 5, `DbConfigTest` 7, `DeepseekConfigTest` 4 (+2 new), `GetWeatherToolTest` 8, `KoogWeatherAgentTest` 7, `LlmTimeZoneResolverTest` 12, `LoggingPromptExecutorTest` 8, `OpenMeteoWeatherClientTest` 6, `RawDeepSeekCallTest` 4, `RequestTracingTest` 6, `TimeServiceTest` 5, `TimeZoneResolverTest` 5, `ToolJsonRendererTest` 4, `ToolSpecTest` 6 (+3 new), `TraceChainIntegrationTest` 6, `TraceFormatsTest` 6, `TraceLogTest` 9 (+3 new), `WeatherRecordRepositoryTest` 4, `WeatherRoutesTest` 7 → 123. Arithmetic: 123 baseline + 72 new (9 new classes) + 8 additive (DeepseekConfig +2, ToolSpec +3, TraceLog +3) = **203**, matching the observed total. No test is disabled or removed (0 `@Disabled`/`@Ignore`).

### Frozen-test rule and untouched files

- `git diff --stat build.gradle.kts` → empty (no new dependency, NFR-06).
- `AppModulesTest.kt` (4 tests) and `TraceChainIntegrationTest.kt` (6 tests) contain no reference to the fueling feature, keep their baseline test counts and are green; their modification time is 2026-09-16 (before this feature's work, 2026-09-23) and the developer reports never having written to them. They are untracked in git (HEAD predates the weather feature), so no `git diff` baseline exists — mtime + content + counts are the evidence.
- Weather sources (`src/main/kotlin/com/aiturbo/weather/*`, `db/JdbcWeatherRecordRepository.kt`) are untouched (mtime 2026-09-16); `src/main/resources/tools/get-weather-tool.json` mtime 2026-09-16; `ToolSpecLoader.RESOURCE_PATH` unchanged (asserted by `ToolSpecTest`).
- `TraceLog.kt` diff contains additions only — `toolDb` (weather `stage=db` line) untouched.
- `application.conf` diff: one line changed (`deepseek.model`), the `stageDb` block added; the `db.*` block is byte-identical.

### Secrets (NFR-02, structural — the value is unknown)

- `.env` is git-ignored (`git check-ignore .env` → `.env`) and currently contains **only** `DEEPSEEK_API_KEY`; `STAGE_DB_PASSWORD` is **absent** (SM-00 check → 0), so the operator step R-6/R-7 is still pending and T-17/M-05 is blocked.
- `StageDbConfig.password` default is `""`; `application.conf` `stageDb.password` is `""` with `${?STAGE_DB_PASSWORD}`; the repository uses the password only in JDBC connection `Properties` and never logs it (no `TraceLog` call receives config, password or API key).
- Tracked-file search: no password-like literal in `src/main` / `src/test`; the value of `DEEPSEEK_API_KEY` from `.env` appears in 0 tracked files; README/conf contain placeholders only (`<the stage password>`, `...`).

### Read-only proof (FR-10, NFR-03)

`StageReadOnlyGuardTest` (green): the `fueling/` package + the three stage `db` files contain no `INSERT/UPDATE/DELETE/MERGE/DROP/ALTER/CREATE/TRUNCATE/GRANT/REVOKE`; the repository uses `prepareStatement` only (no `createStatement`, `executeUpdate`, `addBatch`, generic `execute`), the four `WHERE fueling_id = ?` predicates are bound, and only `FuelingSource.entry` table names are interpolated into SQL; the weather repository (which writes by design) is outside the scan scope.

### Deviation check

The developer's five documented deviations (from the implementation report) assessed against the spec:

1. **`Application.module` extracts `val deepseek = DeepseekConfig.from(environment.config)`** so one value feeds both modules. Acceptable — the design's composition snippet requires exactly this; `appModules` signature and bindings and the `overrideModules` branch are untouched (diff + `AppModulesTest` green).
2. **`FuelingChainIntegrationTest` builds an in-test production-shaped module with fakes** instead of overriding the JDBC binding inside the real `fuelingModule`. Acceptable — plan T-14 says "production-shaped graph with fakes" (same precedent as the frozen `TraceChainIntegrationTest`); the real `appModules + fuelingModule` composition is still exercised by `FuelingModulesTest` and the composed-graph blank-key 503 test (design R-11). Coverage caveat: the real `fuelingModule` has never run a lookup end-to-end offline (its repository is JDBC); the tool/agent/registry bindings are identical to the tested ones.
3. **`FuelingRoutesTest` decodes responses with kotlinx.serialization** rather than substring matching. Acceptable — test-only, and it asserts the typed contract.
4. **`DeepseekConfig.from` probes the git-ignored `.env` through the pre-existing `loadDotenv()`**; the added `DeepseekConfigTest` cases assert only the model. Acceptable — the assertions do not depend on `.env` contents (default and explicit-value paths only), and `ignoreIfMissing = true` keeps them green with `.env` absent.
5. **`.env` was not created/modified: `STAGE_DB_PASSWORD` is missing locally.** Acceptable and expected — R-6/R-7 is an operator step documented in the README; the app starts and the tool degrades gracefully. Only T-17/M-05 is blocked by it.

One reviewer note beyond the list: `StageDbConfigTest` resolves `StageDbConfig.from(...)` in three tests, which internally calls `loadDotenv()`; the design's NFR-05 sentence says no test resolves `StageDbConfig.from` or reads `.env`. No assertion depends on the file (the password assertions use `resolveStageDbPassword` directly and the `from` tests assert host/port/name/user/timeout, never the password), so the suite stays deterministic and green with `.env` absent.

## Bugs found

**None.** No production-code defect was found; production code was not modified by this verification. Observations/coverage caveats for the reviewer:

1. **README duplicated paragraph (minor documentation defect).** Lines 355–359 repeat "The weather tool records every request into a PostgreSQL `users` table. The local development setup is a Docker container:" twice (the `git diff` shows the second copy added together with the new `### Local weather database` heading). Cosmetic only; it does not contradict the shipped behaviour, so T-15 still passes. Fix: delete one copy.
2. **`FuelingChainIntegrationTest.assertNoSecrets` is a weak assertion.** It checks only that the API-key/stage-password fixtures never appear in the captured lines, but the fixtures are passed as `apiKey.isNotBlank()` / an unused `StageDbConfig`, so no component that logs ever receives them — the assertion cannot fail by construction. The real guarantee is structural (the repository/TraceLog never receive credentials) plus the SM-10 live grep. Same caveat as the previous feature's report.
3. **Timeout and SQL correctness are unexercised offline.** The 10 s connect/socket/login/query timeout wiring and the exact stage SQL are verified by code review and the static guard only; a wrong column/table name or database name would surface only in T-17 (SM-01/SM-02/SM-08).
4. **`.env` without `STAGE_DB_PASSWORD`** blocks T-17/M-05 (see Manual checks); this is the documented R-6/R-7 operator step, not a defect.

## Manual checks

Required for T-17/M-05 (the only milestone touching the stage DB or the live LLM); exact commands are in `03-plan.md` §"M-05 live smoke checklist" (SM-00…SM-10), executed by the orchestrator/operator:

1. **SM-00 preconditions:** add `STAGE_DB_PASSWORD=<value>` to the git-ignored `.env` (currently absent; `DEEPSEEK_API_KEY` is present), `DEEPSEEK_MODEL` unset.
2. **SM-01 stage sanity (read-only SQL):** confirm `current_database() = fueling` (R-4), `fueling_id <> lower(fueling_id)` counts are 0 in all three tables (R-3), record baseline row counts and a real GUID per source table; note that the example GUID `5e12bef2-2f78-48f0-aab5-ccb6bfeb8469` must exist in stage data or the README example must be substituted (OQ-02).
3. **SM-02 success:** `POST /fueling` with the example question → 200, one plain-language Russian summary; verify the 8-stage chain in order with one `req=` id and cross-check 3–4 values against SM-01 SQL (FR-07, R-8: every timestamp readable/plausible).
4. **SM-03 source tables:** repeat for a GUID in `fuelings` and one only in archive/drop; the answer and `tables=` must name the correct table.
5. **SM-04/SM-05/SM-06:** unknown valid GUID → 200 "not found", `lookup=not_found`; `12345` → 200 invalid-GUID answer, `is_error=true` and no `stage=db`; no id → asks for GUID; blank message → 400 with no LLM/tool lines. SM-05 also probes R-2 (readability of Koog's error tool result).
6. **SM-07 model probe (R-1, the load-bearing risk):** every `deepseek-request/response` line shows `model=deepseek-flash` and ≥1 `tool_calls=[{"name":"find_fueling"…}]`; if no tool call appears, report the blocker and the `DEEPSEEK_MODEL=deepseek-chat` fallback result.
7. **SM-08 unreachable stage DB:** blackhole host → 200 with "temporarily unavailable" in <30 s (≈10 s), `lookup=unavailable` + `is_error=true`, `/time` still 200, server stays up (NFR-04).
8. **SM-09 latency + regression + read-only:** reachable lookup <5 s; `GET /`, `POST /weather`, `GET /weather/history` unchanged; SM-01 row counts identical after the run (NFR-03).
9. **SM-10 secrets:** greps over the captured log for `STAGE_DB_PASSWORD` and `DEEPSEEK_API_KEY` values → 0; tracked-file grep → 0; no secret in any response body.

Not needed here: unit-level behaviors (all covered by the offline suite above).
