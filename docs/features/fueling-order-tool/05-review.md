# Fueling Order Lookup Tool — Review

## Verdict: APPROVED

No blockers and no majors found in the reviewed diff. The approval covers the offline-verifiable
scope (code, wiring, tests, docs): `./gradlew clean test --offline` re-run by the reviewer is green
(203 tests, 0 failures). The live milestone **T-17/M-05 stays outstanding** — it needs the operator's
`STAGE_DB_PASSWORD` in the git-ignored `.env` and confirmation of R-1 (`deepseek-flash` tool calling)
/ R-3 / R-4 on the real stage database; it does not block this verdict.

Reviewed state: working tree is fully uncommitted (HEAD `3c56077` predates even the weather
feature), so the feature boundary was established by mtime (`2026-09-23` files, exactly the plan's
file list), by `git diff` for the five tracked files, and by reading every changed file in full.

## Compliance matrix

| Requirement | Implementation evidence | Test evidence | Status |
|---|---|---|---|
| FR-01 `POST /fueling` 200 `{message,answer}`, blank 400, JSON contract | `plugins/FuelingRouting.kt:31-48` (mirrors `/weather`; blank 400 at 39-42); registered `plugins/Routing.kt:64` | `FuelingRoutesTest.kt` (6 tests: 200/trim/400 blank/400 malformed/503/weather still routed); `FuelingChainIntegrationTest.kt:154-223` | PASS |
| FR-02 tool registered; name/description from the JSON resource; log shows definition + `stage=tool` GUID | `tools/ToolSpec.kt:25-28`; `find-fueling-tool.json`; `Application.kt:168-183`; `fueling/FindFuelingTool.kt:37-44` | `ToolSpecTest.kt:82-112`; `FindFuelingToolTest.kt:32-58`; `FuelingChainIntegrationTest.kt:187-221` (`tools_count=1`, `"name":"find_fueling"`, resource description) | PASS |
| FR-03 one `orderId` string, canonical 8-4-4-4-12, trimmed, case-insensitive | `FuelingId.kt:11-25` (regex, trim, lowercase, no `UUID.fromString`) | `FuelingIdTest.kt` (14); `FindFuelingToolTest.kt:61-67` (uppercase+padded); `ToolSpecTest.kt:89-95` | PASS |
| FR-04 malformed id → no DB access, no `stage=db`, `is_error=true`, plain answer; no id → asks for it | `FindFuelingTool.kt:47-50` (throws before any repository call) | `FindFuelingToolTest.kt:156-178`; `FuelingChainIntegrationTest.kt:226-270` (7-line chain, 0 `stage=db`); no-id case is prompt-level (see FR-09) | PASS (offline); no-id/wording live-checked in SM-05/SM-06 |
| FR-05 search `fuelings` (parent) + archive + drop; every match labelled | `JdbcStageFuelingRepository.kt:43-48, 72-78` (`FuelingSource.entries`, `FROM ${source.tableName}`); `FuelingReport.kt:44-54, 68-71` | `FindFuelingToolTest.kt:97-117`; `FuelingReportTest.kt:179-196`; real-DB read deferred to T-17 as planned | PASS (offline); live part deferred |
| FR-06 related rows by `fueling_id`; empty is normal | `JdbcStageFuelingRepository.kt:80-98` (related only when a match exists); DTOs `StageFuelingRepository.kt:44-91` | `FuelingTestFixtures.kt:66-98` (2 events/1 token/0 feedback); `FuelingReportTest.kt:56-96` | PASS |
| FR-07 full row incl. jsonb, all epoch fields converted | `JdbcStageFuelingRepository.kt:11-15, 100-160`; `FuelingReport.kt:73-137` (`yyyy-MM-dd HH:mm:ss UTC`, plausibility window, `-`, jsonb cap) | `FuelingReportTest.kt:21-53, 99-155` (all 22 columns, no `\d{13}` leak, raw/null cases) | PASS (offline); real-row cross-check deferred to T-17 |
| FR-08 tool result fed back; second round-trip is the summary | `FuelingAgent.kt:44-74`; `FindFuelingTool.kt:80` returns rendered text | `FuelingChainIntegrationTest.kt:186-221` (2 request/response pairs, tool message); `KoogFuelingAgentTest.kt:152-168` | PASS (offline); model wording live |
| FR-09 system prompt forces the tool call, user language, no tool names/JSON | `FuelingAgent.kt:17-27` (verbatim match with the design) | `KoogFuelingAgentTest.kt:170-188` (system message present verbatim); live behavior in SM-05/SM-06 | PASS |
| FR-10 SELECT-only stage access | `JdbcStageFuelingRepository.kt` (prepared statements only, enum table names, bind parameter); `StageDbConfig.kt`/DTOs have no SQL | `StageReadOnlyGuardTest.kt` (4 tests: write/DDL scan, 4 bound predicates, no interpolation, weather repo out of scope) | PASS |
| FR-11 stage settings configurable; password env/`.env` only; no weather reuse | `StageDbConfig.kt:17-58` (no default password); `application.conf:46-63` (empty password, `${?STAGE_DB_*}`); `db.*` byte-identical | `StageDbConfigTest.kt` (8 tests incl. precedence); `FuelingModulesTest.kt:96-103` (JDBC repo bound separately) | PASS |
| FR-12 graceful degradation; app starts without the stage DB | connection per call `JdbcStageFuelingRepository.kt:43-49`, 10 s timeouts 58-70, `SQLException` → `DatabaseUnavailableException` 50-55; `FindFuelingTool.kt:54-60` | `FindFuelingToolTest.kt:181-198`; `FuelingChainIntegrationTest.kt:273-297`; `FuelingModulesTest.kt:55-69`; blank key 503 `FuelingChainIntegrationTest.kt:339-376` | PASS (offline); timeout budget measured in SM-08 |
| FR-13 default model `deepseek-flash`, `DEEPSEEK_MODEL` override | `Application.kt:63`; `application.conf:23-24` | `DeepseekConfigTest.kt:32-40`; `FuelingChainIntegrationTest.kt:194-198` (`model=deepseek-flash` on both round-trips) | PASS (offline); live lines SM-07 |
| FR-14 one correlation id, full chain, no secrets | `TraceLog.kt:98-128` (three `stage=db` variants, `toolDb` untouched); `FuelingAgent.kt:54-63`; `FindFuelingTool.kt:55-77` | `FuelingChainIntegrationTest.kt:154-223` (8 stages in order, one id, bounded lines); `TraceLogTest.kt:157-237` | PASS (offline); order `db`→`tool` is the design-documented deviation (spec.md:643-646, R-5) |
| FR-15 docs + Postman example + input/output contract | `README.md:51, 92-121, 198-235, 309-346, 480-505, 588-595`; `.env` step at 247-250, 542-556 | README review against the shipped behavior; live verbatim use deferred to T-17 | PASS (review); see Finding 1 |
| FR-16 existing behavior preserved; README consistent | no edits to weather sources (mtime `2026-09-16`); `Routing.kt` one added line; `Application.kt` `appModules` untouched | full baseline green in the re-run (all 20 baseline classes at their previous counts) | PASS |
| FR-17 pipeline artifacts | `01-requirements.md`, `02-design.md`, `03-plan.md`, `spec.md`, `04-test-report.md` present and consistent | process check | PASS |
| NFR-01 observability 8-stage chain | same as FR-14 | `FuelingChainIntegrationTest.kt:168-217` | PASS (offline) |
| NFR-02 secrets only env/`.env`; 0 in tracked files/logs/responses | `StageDbConfig.kt:39-42, 57-58`; repository uses the password in `Properties` only (`JdbcStageFuelingRepository.kt:58-66`); `TraceLog` takes no config | structural checks + `FuelingChainIntegrationTest.kt:136-139` (weak, Finding 2); live grep in SM-10 | PASS (structural) |
| NFR-03 data safety | SELECT-only sources | `StageReadOnlyGuardTest.kt`; row-count check is SM-09 | PASS (offline) |
| NFR-04 timeouts 10 s / endpoint < 30 s | `StageDbConfig.kt:32, 44-45`; connection properties + `queryTimeout` (`JdbcStageFuelingRepository.kt:58-70`) | code review only; measured in SM-08 | PASS (review) |
| NFR-05 offline suite, 123 baseline green | all new tests use fakes/`LogCapture` (see `FuelingTestFixtures.kt`) | reviewer re-run: 203 tests / 0 failures / 0 skipped; baseline counts preserved (DeepseekConfigTest +2, ToolSpecTest +3, TraceLogTest +3 additive; all others identical) | PASS; one deviation, Finding 3 |
| NFR-06 no new dependency; stack unchanged | `git diff --stat -- build.gradle.kts` empty; `AppModulesTest.kt` + `TraceChainIntegrationTest.kt` unmodified (mtime `2026-09-16`, no fueling references) and green | 203-test run | PASS |
| NFR-07 cheapest model everywhere, no fallback | single `LLModel` binding inherited by all three LLM paths; no fallback in code | `DeepseekConfigTest.kt`; chain test model lines | PASS (offline) |
| NFR-08 robustness | caps in `FuelingReport.kt:29-36, 147-154`; distinct error paths in `FindFuelingTool.kt:47-60`; StatusPages 503/500 unchanged | `FuelingReportTest.kt`, `FuelingRoutesTest.kt`, `FuelingChainIntegrationTest.kt` | PASS |
| NFR-09 process/docs | README updated (API table, fueling section, Postman, trace, config, tests, docs links) | README review | PASS |

### SQL, DTO and wiring spot-checks (reviewer's own)

- Six SELECT executions: `fuelings` → `fuelings_archive` → `fuelings_drop` (enum order,
  `JdbcStageFuelingRepository.kt:45-46`), then `partner_fueling_events` → `fueling_feedback` →
  `belka_tokens` (`:80-84`), related only when a match exists (`:47`). All four `WHERE fueling_id = ?`
  predicates bind the GUID; table names come only from `FuelingSource`; the 22 `FUELING_COLUMNS`
  (`:11-15`) and every related column match the spec's stage schema facts exactly.
- Timeouts: `loginTimeout`/`connectTimeout`/`socketTimeout` connection properties plus
  `statement.queryTimeout`, all from `stageDb.timeoutSeconds`; `ApplicationName=ai-turbo` set
  (`:58-66, 69-70`). `SQLException` → `DatabaseUnavailableException` with cause (`:50-55`). No
  logging, no password rendering.
- Koin: `fuelingModule` is additive; named qualifiers keep the unnamed weather `ToolRegistry`
  untouched; the fueling `ToolSpec` is `createdAtStart = true` (fail fast); no cycle — the fueling
  agent resolves the shared `PromptExecutor`/`LLModel` from `appModules`; `Application.module`
  passes one `DeepseekConfig` instance to both modules and leaves `overrideModules` untouched.
- Agent loop is the weather loop duplicated verbatim (same `maxToolRounds = 3`, `temperature 0.0`,
  `maxIterations 10`, `TraceLog.tool` per call), with `id=fueling-agent` and the design prompt.
- Frozen-test rule: `AppModulesTest.kt` (4 tests) and `TraceChainIntegrationTest.kt` (6 tests) carry
  no fueling references, mtime `2026-09-16`, and pass; `build.gradle.kts` is unchanged; all weather
  sources/resources are mtime `2026-09-16`.
- Scope: the only files touched on `2026-09-23` are exactly the plan's create/modify list plus the
  pipeline docs and the planned test classes.

### Developer deviations (all assessed as acceptable)

1. **`Application.module` extracts `val deepseek` and reuses it for both modules** — required by the
   design's composition snippet; `appModules` signature/bindings and the `overrideModules` branch
   are untouched (`Application.kt:189-211`). Acceptable.
2. **`FuelingChainIntegrationTest` builds a production-shaped in-test module with fakes instead of
   overriding the JDBC binding inside the real `fuelingModule`** — matches plan T-14 ("production
   shaped graph with fakes", the frozen chain test's precedent); the real composition is still
   exercised by `FuelingModulesTest` and the composed-graph blank-key test. Acceptable, with the
   known coverage gap that the JDBC SQL runs only in T-17.
3. **`FuelingRoutesTest` decodes responses with kotlinx.serialization** — test-only, asserts the
   typed contract. Acceptable.
4. **`DeepseekConfig.from` probes `.env` through the pre-existing `loadDotenv()`** — the added tests
   assert only the model, do not depend on file contents, and stay green without `.env`.
   Acceptable.
5. **`.env` was not created/modified (`STAGE_DB_PASSWORD` still absent)** — expected: it is the
   operator step (R-6/R-7) documented in the README; the app starts and degrades gracefully.
   Acceptable; blocks only T-17/M-05.

## Findings

### Blocker

None.

### Major

None.

### Minor

1. **README duplicated paragraph** — `README.md:358-359` repeats "The weather tool records every
   request into a PostgreSQL `users` table. The local development setup is a Docker container:"
   (first copy at 355-356). `git diff README.md` confirms the duplicate was added by this feature
   together with the new `### Local weather database ('db.*')` heading; it is cosmetic and does not
   contradict shipped behavior, so T-15 still passes. *Fix:* delete the second copy, keeping the
   heading, one paragraph and the docker snippet.

2. **`FuelingChainIntegrationTest.assertNoSecrets` is a tripwire that cannot fail**
   (`FuelingChainIntegrationTest.kt:136-139`, used at 184, 258, 291, 372). Neither fixture can reach
   a component that logs: the API-key fixture is used only for the `apiKey.isBlank()` branch, and
   the password fixture only in the composed-graph test where the throwing agent runs before the
   repository; the JDBC repository never logs and `TraceLog` takes no config. The real guarantees
   are structural plus the live SM-10 grep. *Fix (optional):* broaden the offline assertion — e.g.
   also assert that no captured line contains `password=`/`apiKey=` shapes and keep SM-10 as the
   authoritative check — or drop the assertion and document the structural guarantee.

3. **`StageDbConfigTest` resolves `StageDbConfig.from`, which reads `.env` through `loadDotenv()`**
   (`StageDbConfigTest.kt:19, 54, 65`) — the design's NFR-05 wording says no test resolves
   `StageDbConfig.from` or reads `.env` (spec.md:959; plan ground rule spec.md:1006). Harmless in
   practice: the three tests assert host/port/name/user/timeout, the password assertions call
   `resolveStageDbPassword` directly, and `loadDotenv()` uses `ignoreIfMissing = true`, so the suite
   is deterministic with `.env` present or absent (verified hypothesis: no assertion depends on file
   contents). *Fix:* either record the relaxed wording in the design or inject the dotenv value so
   the `from` tests stay free of the working-tree `.env`.

### Nit

1. **README weather-chain wording** (`README.md:150-151`): "A full weather request therefore shows
   the five chain stages plus this one extra `stage=db` line" reads confusingly next to the new
   "writes the same eight lines" wording for fueling (`:154`) — the weather chain also has eight
   lines. *Suggestion:* rephrase to "…shows the five stage markers plus this extra `stage=db` line
   (eight lines in total)".

## Scope check

- Diff vs plan: exactly the planned files. New: `db/StageDbConfig.kt`, `db/StageFuelingRepository.kt`,
  `db/JdbcStageFuelingRepository.kt`, `fueling/{FuelingId,FindFuelingTool,FuelingReport,FuelingAgent}.kt`,
  `plugins/FuelingRouting.kt`, `resources/tools/find-fueling-tool.json`, the nine new test classes and
  `FuelingTestFixtures.kt`. Modified: `Application.kt` (model default, qualifiers, `fuelingModule`,
  composition), `plugins/Routing.kt` (one line), `log/TraceLog.kt` (three functions added, `toolDb`
  untouched), `tools/ToolSpec.kt` (one constant), `application.conf` (model default + `stageDb` block),
  `README.md`, and the additive `DeepseekConfigTest`/`ToolSpecTest`/`TraceLogTest` cases.
- Unrelated changes in the feature's file set: none. `docs/features/fueling-order-tool/*` are the
  pipeline artifacts (FR-17).
- Process note: the whole weather slice is also uncommitted/untracked relative to HEAD, so
  `git diff` alone cannot isolate this feature; the mtime scan (`2026-09-23`) and content review
  were used instead, and the weather files carry no feature-day edits.

## Checks run

| Check | Command / evidence | Result |
|---|---|---|
| Full offline suite (reviewer re-run) | `./gradlew clean test --offline` | `BUILD SUCCESSFUL in 7s`; 29 result XMLs: **203 tests, 0 failures, 0 errors, 0 skipped** (matches `04-test-report.md`) |
| Baseline preservation | per-class counts in the fresh XMLs | all 20 baseline classes at their pre-feature counts; +2/+3/+3 additive in DeepseekConfigTest/ToolSpecTest/TraceLogTest |
| Static read-only guard | `StageReadOnlyGuardTest` + manual read of the SQL constants | green; four bound `WHERE fueling_id = ?`; no write/DDL keywords in the stage sources |
| Frozen files | `git diff --stat -- build.gradle.kts`; mtimes; content | build file empty diff; `AppModulesTest.kt`/`TraceChainIntegrationTest.kt` and all weather sources mtime `2026-09-16`, no fueling references, green |
| Secrets | grep over `src/`, `README.md`, `application.conf`, `.gitignore`; `git check-ignore -v .env` | no password literal anywhere (only `${?STAGE_DB_PASSWORD}`, env lookups and clearly fake test fixtures); `.env` ignored via `.gitignore:17`, untracked, contains only `DEEPSEEK_API_KEY` |
| Linters/static analysis | `build.gradle.kts` plugins | none configured (no ktlint/detekt/CI task); the repo's own guard tests are the static checks, all green |

## Out of scope

- Pre-existing (weather feature, not this diff): `TraceChainIntegrationTest.kt:125` binds
  `deepseekModel("deepseek-chat")` in its test-local module — intentional for the frozen test, no
  assertion depends on the model id.
- Repo process: HEAD predates two features, so everything is uncommitted and there is no per-feature
  git history; a commit would make future frozen-test checks diff-based instead of mtime-based.
- Live verification not performed here by design: T-17/M-05 (SM-00…SM-10) remains for the
  orchestrator/operator once `.env` has `STAGE_DB_PASSWORD`; it is the only gate on R-1
  (`deepseek-flash` tool calling), R-3 (lowercase `fueling_id`), R-4 (database name `fueling`),
  the timeout budget and the real-row rendering cross-check.

Verdict: **APPROVED** — 0 blockers, 0 majors, 3 minors, 1 nit.
