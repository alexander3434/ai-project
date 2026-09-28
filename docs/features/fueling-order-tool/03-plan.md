# Fueling Order Lookup Tool — Implementation Plan

Inputs: `01-requirements.md` (FR-01…FR-17, NFR-01…NFR-09) and `02-design.md`. Where this plan is
silent, the design is binding. Execution: `/feature-implementation` (developer → tester → reviewer).
Every criterion is checkable offline unless marked "live (M-05)".

**Ground rules (non-negotiable):**

- **All automated tests offline** — no socket, no stage DB, no `.env`, no live LLM. Use the existing
  `LogCapture` (Logback `ListAppender`), fakes and a scripted `PromptExecutor` (patterns:
  `TraceChainIntegrationTest`, `AppModulesTest`).
- **The 123 pre-existing `@Test` methods stay green and unmodified**; the only production change that
  touches them is the model default, and no existing test asserts it. `TraceChainIntegrationTest`
  (frozen: `tools_count=1`, `"name":"get_weather"`) passes unchanged — R-7.
- **`appModules(...)` keeps its exact signature and bindings** (frozen `AppModulesTest`); the feature
  is additive: `fuelingModule(stageDb, apiKeyConfigured)` with Koin **named qualifiers**
  `FUELING_TOOL_SPEC` / `FUELING_TOOL_REGISTRY`. The unnamed weather registry stays
  `get_weather`-only; `/time` keeps its descriptors.
- **No new Gradle dependency** (NFR-06); `build.gradle.kts` byte-identical at the end.
- **`.env` must gain `STAGE_DB_PASSWORD` during implementation** — user-provided, manual, git-ignored
  operator step (documented in the README by T-15). Without it the app still starts and the tool
  degrades gracefully (FR-11/FR-12); the value never appears in a tracked file, log or response
  (NFR-02).
- **Stage access is read-only:** `SELECT` + prepared statements only, table names from the
  `FuelingSource` enum, no interpolation (FR-10, NFR-03).
- **Frozen-test rule:** if a change would require editing an existing test, fix the wiring instead.

**Size legend:** S = a few hours · M = half a developer-day · L = a full developer-day (the ceiling).
**Milestones:** M-01 foundations → M-02 offline core → M-03 tool/agent/route → M-04 full offline
chain + docs → M-05 live stage smoke (the only milestone touching the stage DB or the live LLM).

## Task list

| ID | Title | Size | Depends on | Files to touch | Description | FRs / design refs |
|---|---|---|---|---|---|---|
| T-01 | Default model → `deepseek-flash` | S | — | `src/main/resources/application.conf`; `src/main/kotlin/com/aiturbo/Application.kt`; `src/test/kotlin/com/aiturbo/DeepseekConfigTest.kt` | `deepseek.model` default and `DeepseekConfig.DEFAULT_MODEL` become `deepseek-flash`; `${?DEEPSEEK_MODEL}` stays the only override; extend `DeepseekConfigTest` | FR-13, NFR-07; D-14, §Configuration |
| T-02 | `StageDbConfig` + password resolution + `stageDb` block | M | — | new `src/main/kotlin/com/aiturbo/db/StageDbConfig.kt`; `src/main/resources/application.conf`; new `src/test/kotlin/com/aiturbo/StageDbConfigTest.kt` | Stage settings resolved config → `STAGE_DB_*` env → `.env`, **no default password**; tracked `stageDb { … }` with empty password | FR-11, NFR-02; §StageDbConfig, §Configuration |
| T-03 | `FuelingId` GUID contract + exceptions | S | — | new `src/main/kotlin/com/aiturbo/fueling/FuelingId.kt`; new `src/test/kotlin/com/aiturbo/FuelingIdTest.kt` | Canonicalize trimmed 8-4-4-4-12 to lowercase; `InvalidFuelingIdException`, `StageDatabaseUnavailableException`; no `UUID.fromString` | FR-03, FR-04; §FuelingId, D-09 |
| T-04 | `TraceLog` fueling lookup lines | S | — | `src/main/kotlin/com/aiturbo/log/TraceLog.kt`; `src/test/kotlin/com/aiturbo/TraceLogTest.kt` | `fuelingLookupFound/NotFound/Unavailable`, each one `stage=db` line; `toolDb` untouched | FR-14, NFR-01; §TraceLog additions, D-15 |
| T-05 | Stage read port + DTOs | S | — | new `src/main/kotlin/com/aiturbo/db/StageFuelingRepository.kt` | `FuelingSource`, `FuelingMatch`, `FuelingRecord`, `RelatedRows`, `PartnerFuelingEvent`, `FuelingFeedback`, `BelkaToken`, `FuelingLookupResult`, `StageFuelingRepository` per the design data model | FR-05, FR-06, FR-07; §StageFuelingRepository, §Data model |
| T-06 | `FuelingReport` renderer + test | M | T-05 | new `src/main/kotlin/com/aiturbo/fueling/FuelingReport.kt`; new `src/test/kotlin/com/aiturbo/FuelingReportTest.kt` | Pure renderer: found/not-found text, all columns, `yyyy-MM-dd HH:mm:ss UTC`, plausibility window, jsonb cap, 50-row render cap with real totals | FR-06, FR-07; §FuelingReport, D-04/05/06 |
| T-07 | `JdbcStageFuelingRepository` + guard test | L | T-02, T-05 | new `src/main/kotlin/com/aiturbo/db/JdbcStageFuelingRepository.kt`; new `src/test/kotlin/com/aiturbo/StageReadOnlyGuardTest.kt` | Six `SELECT`s in the fixed order, connection per call, prepared statements, 10 s connect/socket/login + query timeout, `ApplicationName=ai-turbo`, `SQLException` → `DatabaseUnavailableException`; static read-only guard | FR-05, FR-06, FR-10, FR-12, NFR-03, NFR-04; §JdbcStageFuelingRepository, D-08/10/12 |
| T-08 | Tool resource + loader constant | S | — | new `src/main/resources/tools/find-fueling-tool.json`; `src/main/kotlin/com/aiturbo/tools/ToolSpec.kt`; `src/test/kotlin/com/aiturbo/ToolSpecTest.kt` | Ship the `find_fueling` name/description/parameter doc from the design; add `ToolSpecLoader.FUELING_RESOURCE_PATH`; extend `ToolSpecTest` | FR-02; §tools/ToolSpec + resource |
| T-09 | Offline fixtures (`FakeStageFuelingRepository`) | S | T-05 | new `src/test/kotlin/com/aiturbo/FuelingTestFixtures.kt` | Seedable fake repo (multi-match, empty related, not-found, unavailable mode, call counter) + sample rows (2 events / 1 token / 0 feedback; in/out-of-window epochs) | FR-06, NFR-05; §New tests |
| T-10 | `FindFuelingTool` + test | M | T-03, T-04, T-06, T-08, T-09 | new `src/main/kotlin/com/aiturbo/fueling/FindFuelingTool.kt`; new `src/test/kotlin/com/aiturbo/FindFuelingToolTest.kt` | Invalid id → no repo call, no `stage=db`; else repo in `Dispatchers.IO`; one `stage=db` line per outcome; render via `FuelingReport`; SQL failure → `StageDatabaseUnavailableException` | FR-02, FR-04, FR-05, FR-06, FR-12; §FindFuelingTool, D-03, D-15 |
| T-11 | `FuelingAgent` + `KoogFuelingAgent` + test | M | T-09, T-10 | new `src/main/kotlin/com/aiturbo/fueling/FuelingAgent.kt`; new `src/test/kotlin/com/aiturbo/KoogFuelingAgentTest.kt` | Design's system prompt verbatim, `find_fueling`-only registry, `maxToolRounds=3`, temperature 0.0, `maxIterations=10`, id `fueling-agent`, `TraceLog.tool` per call | FR-08, FR-09; §FuelingAgent, D-13, F-1 |
| T-12 | `POST /fueling` + registration + test | M | T-11 | new `src/main/kotlin/com/aiturbo/plugins/FuelingRouting.kt`; `src/main/kotlin/com/aiturbo/plugins/Routing.kt`; new `src/test/kotlin/com/aiturbo/FuelingRoutesTest.kt` | Route mirroring `/weather`: 200 `{message,answer}`, blank 400, invalid JSON 400, blank key 503, agent in `withContext(trace)` | FR-01, FR-16; §FuelingRouting, §API design |
| T-13 | Koin wiring + modules test | M | T-02, T-07, T-08, T-10, T-11 | `src/main/kotlin/com/aiturbo/Application.kt`; new `src/test/kotlin/com/aiturbo/FuelingModulesTest.kt` | Additive `fuelingModule(...)` with named qualifiers, composed in `Application.module` default branch only; `appModules` untouched | FR-02, FR-12, FR-16, NFR-05, NFR-06; §Koin wiring, D-02 |
| T-14 | `FuelingChainIntegrationTest` | L | T-04, T-07, T-10, T-11, T-12, T-13 | new `src/test/kotlin/com/aiturbo/FuelingChainIntegrationTest.kt` | Production-shaped graph with fakes: 8-stage order, one `req=` id, `tools_count=1` + `find_fueling` definition, `stage=db` counts, invalid-id without `db` line, truncation bound, no secrets, composed-graph blank-key 503 | FR-08, FR-14, NFR-01, NFR-02; §New tests, NFR coverage |
| T-15 | README update | S | T-12, T-13 | `README.md` | API table + fueling section (tool input/output, Postman example), `deepseek-flash` trace examples, `stageDb.*`/`STAGE_DB_*` table, `.env` step, test list, docs link | FR-15, FR-16, NFR-09; §Files to modify, design R-12 |
| T-16 | Regression + guard sweep | S | T-14, T-15 | none (verification) | Clean-build suite offline, secrets search, read-only scan, dependency check, matrix sweep, implementation record | FR-10, FR-16, FR-17, NFR-02, NFR-03, NFR-05, NFR-06, NFR-09 |
| T-17 | Live stage smoke (M-05) | S | T-16 + operator `.env` | none (manual; record log excerpts) | Execute the M-05 checklist against the real stage DB and the real `deepseek-flash`; resolve R-1/R-3/R-4 | FR-05, FR-07, FR-08, FR-12, FR-13, FR-14; NFR-01, NFR-02, NFR-04, NFR-07, NFR-08 |

## Work order and milestones

The dependency graph is acyclic; batches are the exact developer sequence; tasks in one batch may run
in parallel.

**Prerequisites (operator, before T-17).** `.env` in the project root (git-ignored) contains
`DEEPSEEK_API_KEY` (already) and `STAGE_DB_PASSWORD=<user-provided>`; `DEEPSEEK_MODEL` unset so the
default `deepseek-flash` is exercised; the stage DB is reachable and a SQL client is available for
SM-01. The implementation cannot create the password.

**Batch 1 — foundations (parallel: T-01, T-02, T-03, T-04, T-05).** T-01 and T-02 both edit
`application.conf`, in different non-overlapping blocks; if one developer takes both, apply sequentially.
**Milestone M-01 — "foundations compile and pass offline".** Exit: `./gradlew compileKotlin
compileTestKotlin` green; `./gradlew test` green — 123 pre-existing tests plus the `StageDbConfigTest`,
`FuelingIdTest`, `DeepseekConfigTest` and `TraceLogTest` additions; no test opens a socket.

**Batch 2 — offline core (parallel: T-06, T-07, T-08, T-09).**
**Milestone M-02 — "rendering and read-only repository verified offline".** Exit: `FuelingReportTest`,
`StageReadOnlyGuardTest`, `ToolSpecTest` green; the guard proves SELECT-only statements with `?`
placeholders in the stage sources; the whole suite is still green with no stage DB and no `.env`.

**Batch 3 — T-10. Batch 4 — T-11. Batch 5 — route and wiring (parallel: T-12, T-13).**
**Milestone M-03 — "tool, agent and route wired offline".** Exit: `FindFuelingToolTest`,
`KoogFuelingAgentTest`, `FuelingRoutesTest`, `FuelingModulesTest` green; offline `POST /fueling`
answers 200/400/503; `AppModulesTest` and `TraceChainIntegrationTest` green **and unmodified** (R-7).

**Batch 6 — chain test and docs (parallel: T-14, T-15). Batch 7 — sweep: T-16.**
**Milestone M-04 — "full offline chain and documentation complete".** Exit: `FuelingChainIntegrationTest`
green (8 stages in order, one `req=` id, `tools_count=1` with the `find_fueling` definition, no
secrets); clean-build `./gradlew test` fully green offline; repository search for the stage password →
0 hits; `git diff build.gradle.kts` empty; README consistent with shipped behaviour.

**Batch 8 — live smoke: T-17.**
**Milestone M-05 — "live stage smoke".** Prerequisites: M-04 done, `.env` carries
`STAGE_DB_PASSWORD`, stage DB reachable, `./gradlew run` started with `DEEPSEEK_MODEL` unset.
Exit: every step SM-00…SM-10 passes; R-1, R-3, R-4 confirmed or the documented fallback applied; log
excerpts kept as the implementation record.

### M-05 live smoke checklist (exact commands)

Capture the server output: `./gradlew run 2>&1 | tee /tmp/fueling-smoke.log`. Postman equivalent for
every POST: method `POST`, URL `http://localhost:8080/fueling`, Body → raw → JSON, same body.

**SM-00 preconditions (no secret printed).**
```bash
cd /Users/murkka/Work/ai-project
grep -c '^STAGE_DB_PASSWORD=.' .env      # expect 1
git check-ignore .env                    # expect ".env"
printenv DEEPSEEK_MODEL                  # expect empty
```

**SM-01 stage sanity + baseline (read-only SQL; verifies R-3, R-4, OQ-02).**
```bash
export PGPASSWORD="$(grep '^STAGE_DB_PASSWORD=' .env | cut -d= -f2-)"
PSQL="psql -h postgres.stage.turboapp.ru -p 25432 -U fueling -d fueling -X"
$PSQL -c "select current_database();"                       # R-4: expect fueling
$PSQL -c "select count(*) from fuelings         where fueling_id <> lower(fueling_id);"
$PSQL -c "select count(*) from fuelings_archive where fueling_id <> lower(fueling_id);"
$PSQL -c "select count(*) from fuelings_drop    where fueling_id <> lower(fueling_id);"   # R-3: all three 0
$PSQL -c "select (select count(*) from fuelings) f, (select count(*) from fuelings_archive) a, (select count(*) from fuelings_drop) d, (select count(*) from partner_fueling_events) e, (select count(*) from belka_tokens) t;"
$PSQL -c "select 'fuelings' t, fueling_id from fuelings where fueling_id='5e12bef2-2f78-48f0-aab5-ccb6bfeb8469' union all select 'fuelings_archive', fueling_id from fuelings_archive where fueling_id='5e12bef2-2f78-48f0-aab5-ccb6bfeb8469' union all select 'fuelings_drop', fueling_id from fuelings_drop where fueling_id='5e12bef2-2f78-48f0-aab5-ccb6bfeb8469';"
$PSQL -c "select fueling_id from fuelings         where fueling_id is not null limit 1;"
$PSQL -c "select fueling_id from fuelings_archive where fueling_id is not null limit 1;"
$PSQL -c "select fueling_id from fuelings_drop    where fueling_id is not null limit 1;"
unset PGPASSWORD
```
If the example GUID returns no row: substitute the verified GUID and update the README example (OQ-02
requires the documented example to be a real `fueling_id`). If any lowercase check is non-zero (R-3):
apply the `lower(fueling_id) = ?` fallback in `JdbcStageFuelingRepository`, re-run the offline suite.

**SM-02 success with the example GUID (FR-01, FR-07, FR-08, FR-14).**
```bash
curl -sS -i -X POST http://localhost:8080/fueling -H 'Content-Type: application/json' \
  -d '{"message":"Найди данные по проливу для заказа 5e12bef2-2f78-48f0-aab5-ccb6bfeb8469"}'
```
Expect HTTP 200, `{"message":"…","answer":"…"}` with one plain-language Russian line (source table,
status, amounts, fuel type, station/pump/gun, readable times, related counts; no JSON, no tool names).
Log shows the 8 stages in order with one `req=` id: `inbound → deepseek-request → deepseek-response
(tool_calls=[{"name":"find_fueling"…}]) → db(lookup=found records=… tables=… events=… tokens=…
feedback=…) → tool(tool=find_fueling args={"orderId":"5e12bef2-…"} is_error=false) → deepseek-request
→ deepseek-response → outbound(status=200)`. Cross-check 3-4 reported values against SM-01 SQL.

**SM-03 source-table coverage (FR-05, ASM-04).** Re-run the SM-02 curl with the GUID verified in each
of the three tables. Expected: 200 each; the answer and `tables=` name the correct table
(`fuelings` / `fuelings_archive` / `fuelings_drop`).

**SM-04 not found (ASM-06).**
```bash
curl -sS -X POST http://localhost:8080/fueling -H 'Content-Type: application/json' \
  -d '{"message":"Найди заказ 00000000-0000-4000-8000-000000000000"}'
```
Expect 200 (no 404), plain-language "no data in the stage database", log `lookup=not_found records=0`
and `is_error=false`.

**SM-05 invalid id (FR-04; probes R-2).**
```bash
curl -sS -X POST http://localhost:8080/fueling -H 'Content-Type: application/json' \
  -d '{"message":"Найди заказ 12345"}'
```
Expect 200, plain-language invalid-GUID answer, log `stage=tool … is_error=true` and **no** `stage=db`
line. Read the second `stage=deepseek-request` to see what Koog put into the error tool result; if the
answer is unusable because that text is opaque, keep the invalid-id throw and switch the *unavailable*
case to a normal string result (design R-2 one-line fallback).

**SM-06 no id, and the 400 contract (FR-01, FR-04).**
```bash
curl -sS -X POST http://localhost:8080/fueling -H 'Content-Type: application/json' \
  -d '{"message":"Найди данные по проливу"}'
curl -sS -o /dev/null -w 'status=%{http_code}\n' -X POST http://localhost:8080/fueling \
  -H 'Content-Type: application/json' -d '{"message":"   "}'
```
Expect: first → 200, answer asks for the order GUID; second → `status=400` with
`{"error":"Field 'message' is required"}` and no `stage=deepseek`/`stage=tool` lines.

**SM-07 model default + the R-1 probe (FR-13, NFR-07, ASM-12).**
```bash
grep -o 'model=[A-Za-z0-9.:-]*' /tmp/fueling-smoke.log | sort | uniq -c   # all deepseek-flash
grep 'stage=deepseek-response' /tmp/fueling-smoke.log | grep -c 'tool_calls=\[{"name":"find_fueling"'
```
Expect every request/response line `model=deepseek-flash` and ≥ 1 `find_fueling` tool call.
**R-1 stop condition:** if no tool call appears, report a blocker and rerun with
`DEEPSEEK_MODEL=deepseek-chat ./gradlew run` (env-only fallback, no code change); record the outcome.

**SM-08 stage unreachable / timeout (FR-12, NFR-04, ASM-07).**
```bash
STAGE_DB_HOST=10.255.255.1 ./gradlew run          # blackhole → exercises the 10 s connect timeout
curl -sS -o /tmp/fueling-down.json -w 'status=%{http_code} time_total=%{time_total}\n' \
  -X POST http://localhost:8080/fueling -H 'Content-Type: application/json' \
  -d '{"message":"Найди данные по проливу для заказа 5e12bef2-2f78-48f0-aab5-ccb6bfeb8469"}'
curl -sS -o /dev/null -w 'time_status=%{http_code}\n' 'http://localhost:8080/time?location=Moscow'
```
Expect 200, `time_total` < 30 s (≈ 10 s for the blackhole), "temporarily unavailable" answer,
`lookup=unavailable reason="…"` + `is_error=true`, `/time` still 200, server stays up (repeat once).
Optional: `STAGE_DB_HOST=127.0.0.1 STAGE_DB_PORT=9` (immediate refusal) → same 200 wording.

**SM-09 latency, read-only proof, regression (NFR-03, NFR-04, FR-16).**
```bash
curl -sS -o /dev/null -w 'time_total=%{time_total}\n' -X POST http://localhost:8080/fueling \
  -H 'Content-Type: application/json' -d '{"message":"Найди данные по проливу для заказа 5e12bef2-2f78-48f0-aab5-ccb6bfeb8469"}'
curl -sS http://localhost:8080/ | head -c 200; echo
curl -sS -X POST http://localhost:8080/weather -H 'Content-Type: application/json' \
  -d '{"message":"Какая погода в Москве?"}' | head -c 300; echo
curl -sS -o /dev/null -w 'history_status=%{http_code}\n' 'http://localhost:8080/weather/history?limit=5'
```
Expect: a reachable lookup < 5 s between its `tool` and `db` log lines and the request < 30 s; the
SM-01 baseline counts unchanged (0 write traffic); `GET /`, `POST /weather`, `GET /weather/history`
unchanged.

**SM-10 secrets (NFR-02).**
```bash
grep -c "$(grep '^STAGE_DB_PASSWORD=' .env | cut -d= -f2-)" /tmp/fueling-smoke.log    # expect 0
grep -c "$(grep '^DEEPSEEK_API_KEY=' .env | cut -d= -f2-)"  /tmp/fueling-smoke.log    # expect 0
git -C /Users/murkka/Work/ai-project grep -I -c "$(grep '^STAGE_DB_PASSWORD=' .env | cut -d= -f2-)" || echo "0 tracked hits"
```
Expect 0 occurrences of either secret in the log, the responses and every tracked file.

## Acceptance criteria

**T-01.** `application.conf` `deepseek.model` defaults to `deepseek-flash` with `${?DEEPSEEK_MODEL}`
intact; `DeepseekConfig.DEFAULT_MODEL == "deepseek-flash"`; `DeepseekConfigTest` asserts unset →
`deepseek-flash` and an explicit value still wins; all pre-existing `DeepseekConfigTest` and the full
suite green.

**T-02.** `StageDbConfig.from(config)` reads `stageDb.host|port|name|user|password|timeoutSeconds`
with the design's defaults (port 25432, `fueling`, timeout 10); `jdbcUrl ==
jdbc:postgresql://<host>:<port>/<database>`. `resolveStageDbPassword` order config → env → file, blank
when unset, no development default. `application.conf` gains the `stageDb` block (empty password,
`${?STAGE_DB_*}` overrides) with `db.*` byte-identical. `StageDbConfigTest` covers defaults, explicit
values, precedence, `.env` fallback, blank password, `jdbcUrl`, fully offline. No tracked file contains
the password; construction performs no I/O.

**T-03.** `canonicalize` accepts lower/upper 8-4-4-4-12, trims whitespace, returns lowercase; returns
`null` for compact 32-hex, braces, `urn:uuid:`, blank, wrong group lengths (`1-1-1-1-1`), non-hex; no
version/variant check; `UUID.fromString` not used. `InvalidFuelingIdException` and
`StageDatabaseUnavailableException` exist with the design's constructors. `FuelingIdTest` enumerates
every accept/reject case and is green.

**T-04.** Each new function emits exactly one INFO line on `com.aiturbo.trace`:
`… stage=db tool=<name> lookup=found records=<n> tables=<t1,t2> events=<n> tokens=<n> feedback=<n>`
(`capped=true` only when capped), `lookup=not_found records=0 tables=<…>`, or
`lookup=unavailable reason="…"`; ids render `-` outside a trace; `toolDb`/`TraceLogTest` untouched and
green; additions green via `LogCapture`.

**T-05.** All types and fields exactly per design §StageFuelingRepository / §Data model, including
every documented `FuelingRecord` column (jsonb as text, epochs as `Long?`) and both related DTOs;
single interface method with the designed signature; no I/O or logging; compiles.

**T-06.** Found text matches the design shape: `НАЙДЕНО: n (<tables>)`, one `record[i] table=…` block
per match in query order with every documented field (missing → `-`), then events/feedback/belka
blocks with `total=`/`shown=`. Epochs in `2000-01-01`…`2100-01-01` → `yyyy-MM-dd HH:mm:ss UTC`;
outside → `<n> (raw)`; null → `-`. jsonb capped at 2000 chars with `…[truncated, N chars total]`;
related rows capped at 50 per table with real totals; not-found text per design; multiple matches
labelled. `FuelingReportTest` covers all of the above; rendering deterministic and clock-free.

**T-07.** Exactly the six `SELECT`s in the design's fixed order (parent `fuelings` table; related
queries only when at least one match); prepared statements only, GUID bound as a parameter, table
names only from `FuelingSource`, no interpolation. Connection per call with
`Properties(user, password, loginTimeout/connectTimeout/socketTimeout = timeoutSeconds,
ApplicationName="ai-turbo")` and `queryTimeout = timeoutSeconds`; `SQLException` → existing
`DatabaseUnavailableException` with cause; nothing logged from config/password. Columns read per the
design accessors. `StageReadOnlyGuardTest` green: no `INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE`
in the stage sources and SQL uses `?` (must not flag the weather repository). No socket in any test.

**T-08.** Resource matches the design (`find_fueling`, designed Russian description, required
`orderId`); loads via `ToolSpecLoader.FUELING_RESOURCE_PATH`; missing/invalid resource still fails
fast; `RESOURCE_PATH`, `get-weather-tool.json` and default `load()` unchanged; additions green.

**T-09.** Fake implements `StageFuelingRepository` with seedable results (multi-match, empty related,
not-found), an unavailable mode throwing `DatabaseUnavailableException`, and a recorded call list for
the "repository not called" assertion. Sample rows cover 2 events / 1 token / 0 feedback, a
no-related-rows record, and epochs inside and outside the plausibility window. No socket/DB; reusable
by T-10/T-11/T-14.

**T-10.** Descriptor name/description from `find-fueling-tool.json`; exactly one `orderId` string
argument. Valid id → one repository call with the canonical lowercase GUID inside `Dispatchers.IO`;
result text from `FuelingReport.render`. Found → one `stage=db lookup=found` line with real counts;
not found → `lookup=not_found records=0` and a normal result; repository failure → `lookup=unavailable
reason=…` and `StageDatabaseUnavailableException` propagates. Invalid id →
`InvalidFuelingIdException`, zero repository calls, no `stage=db` line. `FindFuelingToolTest` green
offline.

**T-11.** `KoogFuelingAgent` uses the design's `SYSTEM_PROMPT` verbatim, a `find_fueling`-only
registry, `maxToolRounds = 3`, temperature 0.0, `maxIterations = 10`, id `fueling-agent`; the strategy
mirrors the weather loop and logs `TraceLog.tool` per call with `is_error`. Test: scripted tool call →
text yields one `stage=tool tool=find_fueling args={"orderId":"<guid>"} is_error=false` under the
correlation id; failing tool → `is_error=true` and the loop still answers; the system prompt appears in
the first prompt; non-convergence warning after N rounds; descriptors non-empty; weather tests green.

**T-12.** `POST /fueling`: non-empty message → 200 `{"message":<trimmed>,"answer":<agent text>}`;
blank → 400 `{"error":"Field 'message' is required"}` with no agent call; invalid JSON → 400
`{"error":"Invalid request body"}`; `WeatherUnavailableException` (blank key) → 503. All lines of one
request share the `inbound` correlation id. `Routing.kt` registers `fuelingRoutes()`; existing
handlers unchanged; `FuelingRoutesTest` green offline.

**T-13.** `fuelingModule(stageDb, apiKeyConfigured)` provides the qualifier-bound eager `ToolSpec` and
`ToolRegistry` (find_fueling only), `StageFuelingRepository`, `FindFuelingTool` and `FuelingAgent`
(Koog when configured, else throwing lambda). `appModules` unchanged; `Application.module` composes
both modules in the default branch only; `overrideModules` path untouched. `FuelingModulesTest`: graph
resolves offline, spec loaded once, unnamed weather registry still `["get_weather"]`, blank key throws
the API-key `WeatherUnavailableException`. `AppModulesTest` and `TraceChainIntegrationTest` green
unmodified (no diff on those files).

**T-14.** One request yields exactly `inbound, deepseek-request, deepseek-response, db, tool,
deepseek-request, deepseek-response, outbound` with one `req=` id; both requests carry
`tools_count=1`, `"name":"find_fueling"` and the resource description; `stage=db` carries the fake's
counts/tables; `stage=tool` carries the GUID and `is_error=false`; invalid-id run shows `is_error=true`
and no `stage=db` while still answering 200; every line ≤ `2*TraceLog.MAX_BODY_CHARS + 1024` with
`…[truncated,` on long lines; API-key and stage-password fixtures appear 0 times; composed-graph blank
key → `POST /fueling` 503 with no `stage=deepseek/tool/db` lines (design R-11). Green offline.

**T-15.** README API table lists `POST /fueling`; the fueling section documents tool input (single
`orderId`, canonical GUID, example) and output (match/source table, all fields, readable timestamps,
related rows, counts, error kinds) matching shipped behaviour; the Postman example is usable verbatim
(method, URL, header, raw JSON with the example GUID or the verified substitute, expected 200 shape,
the other cases); trace examples show `deepseek-flash` and the 8-stage chain; config table documents
`stageDb.*`/`STAGE_DB_*` incl. the `.env` step; test list and feature-docs link updated; no statement
contradicts the new behaviour and no secret appears.

**T-16.** `./gradlew clean test` green — 123 pre-existing plus all new tests, no network/stage
DB/`.env` needed. `git diff --stat` empty for `build.gradle.kts`, `AppModulesTest.kt`,
`TraceChainIntegrationTest.kt` and the weather sources; `.env` ignored/untracked. Secrets search over
tracked files → 0 hits. Read-only scan green. Traceability re-verified: every FR/NFR has its task(s)
green or explicitly deferred to M-05; per-task outcomes recorded.

**T-17 (live).** SM-00…SM-10 all pass with the stated expectations; R-1 confirmed or escalated with
the recorded `DEEPSEEK_MODEL=deepseek-chat` result; R-3/R-4 confirmed by SM-01 or the documented
fallbacks applied with the offline suite re-run green; timings within NFR-04; row counts unchanged;
zero secret occurrences; the example GUID in the docs matches a real stage `fueling_id` (or the
substitution is recorded).

## Traceability matrix

**All FRs and NFRs are covered — no requirement is without at least one task.**

| Req | Tasks | Req | Tasks |
|---|---|---|---|
| FR-01 | T-12, T-13, T-14, T-17 | NFR-01 | T-04, T-14, T-17 |
| FR-02 | T-08, T-10, T-13, T-14 | NFR-02 | T-02, T-14, T-16, T-17 |
| FR-03 | T-03, T-08, T-10 | NFR-03 | T-07, T-16, T-17 |
| FR-04 | T-03, T-10, T-11, T-12, T-14, T-17 | NFR-04 | T-07, T-17 |
| FR-05 | T-05, T-07, T-10, T-17 | NFR-05 | T-09, T-16 (and every test task T-02…T-14) |
| FR-06 | T-05, T-07, T-09, T-10 | NFR-06 | T-01, T-13, T-16 |
| FR-07 | T-05, T-06, T-07, T-17 | NFR-07 | T-01, T-14, T-17 |
| FR-08 | T-11, T-14, T-17 | NFR-08 | T-03, T-06, T-10, T-12, T-17 |
| FR-09 | T-11 | NFR-09 | T-15, T-16 |
| FR-10 | T-07, T-16 | FR-16 | T-01, T-12, T-13, T-15, T-16 |
| FR-11 | T-02, T-16, T-17 | FR-17 | T-16, T-17 (plus this plan and the implementation record) |
| FR-12 | T-02, T-07, T-10, T-13, T-17 | | |
| FR-13 | T-01, T-14, T-17 | | |
| FR-14 | T-04, T-14, T-17 | | |
| FR-15 | T-15, T-17 | | |

Requirements walkthrough (§End-to-end acceptance criteria of `01-requirements.md`): 1 → SM-02;
2 → SM-03; 3 → SM-04; 4 → SM-05/SM-06; 5 → SM-08; 6 → M-04 (full offline suite); 7 → SM-10.

## Risk register

| ID | Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| R-1 | `deepseek-flash` may not support tool calling through the Koog/DeepSeek client (ASM-12, the load-bearing assumption) | Med | High (blocker) | First live probe SM-07 requires `tool_calls=[{"name":"find_fueling"…}]`; fallback needs **no code change** — rerun with `DEEPSEEK_MODEL=deepseek-chat`; escalate before changing code | developer (escalation: user) |
| R-2 | Koog 1.2.0 may put opaque text into the error tool result when `execute()` throws, degrading FR-04 answers | Med | Med | SM-05 inspects the second `deepseek-request` and the final answer; if opaque: keep the invalid-id throw (FR-04 needs `is_error=true`) and return the *unavailable* case as a normal string (design's one-line fallback); messages are already readable Russian | developer / tester |
| R-3 | Stage `fueling_id` values may not all be lowercase canonical GUIDs, so `WHERE fueling_id = ?` misses rows (D-09) | Low | Med | SM-01 SQL `count(*) WHERE fueling_id <> lower(fueling_id)` on all three tables; fallback `lower(fueling_id) = ?` (one line per query) keeps the offline suite green | developer (check: tester) |
| R-4 | Stage database name `fueling` unverified (ASM-10) | Low | High (every lookup fails) | SM-01 `select current_database();`; one-key override `STAGE_DB_NAME`, no code change | developer / operator |
| R-5 | Requirements prose lists `tool` before `db`; shipped chain is `db` → `tool` (design §Logging order note) | Certain (known deviation) | Low | Keep the shipped order (identical to the verified weather chain); both lines present with one `req=` id and asserted by T-14; documented in design + README; escalate only if the requirements owner insists on the literal order | reviewer (requirements text: user) |
| R-6 | `.env` lacks `STAGE_DB_PASSWORD` — manual, user-provided, git-ignored; implementation cannot create it | High (manual step) | Med (M-05 blocked; app still degrades gracefully) | README documents the step (T-15); SM-00 checks it; FR-11/FR-12 keep the app starting without it; never in a tracked file, log or chat | user/operator (verification: developer) |
| R-7 | Frozen `TraceChainIntegrationTest` (`tools_count=1`, `"name":"get_weather"`) / `AppModulesTest` could break if the fueling tool leaks into the shared registry or `appModules` changes | Med | High (123-test regression) | `get_weather`-only unnamed registry; additive `fuelingModule` with named qualifiers (D-01/D-02); T-13/T-16 require those files unmodified and green; if they fail, fix the wiring, never the test | developer (reviewer verifies the diff) |
| R-8 | 10 s timeout is a proposed value (ASM-07) | Med | Low | `stageDb.timeoutSeconds` / `STAGE_DB_TIMEOUT_SECONDS` tunable without code change; SM-08 measures the unreachable case < 30 s | developer |
| R-9 | Numeric columns (e.g. `vendor_transaction_date`) may not really be epoch millis (design R-8) | Low | Med | Plausibility window renders `<n> (raw)` (D-06); SM-02 compares a real row against SQL and requires every timestamp readable and plausible | tester |
| R-10 | More than 3 GUIDs in one question exceeds `maxToolRounds` (design R-9) | Low | Low | Mirrors the weather agent; non-convergence warning logged, agent answers with what it has; raise with the user if needed | developer |
| R-11 | Per-GUID related-row volumes unknown (67K events overall; design R-10) | Low | Med | All related rows fetched, first 50 per table rendered with real totals (D-07); SM-03 on a busy order; add SQL `LIMIT` + `count(*) OVER ()` only if measured | developer |
| R-12 | The composed graph (both modules) is not exercised by any existing test (design R-11) | Med | Med | `FuelingModulesTest` (T-13) composes exactly like production; T-14 adds the blank-key 503 route proof; M-04 requires both green | tester |
| R-13 | README/Postman documentation drifts from shipped behaviour (design R-12, FR-15) | Low | Med | T-15 acceptance compares README against §API design; SM-02…SM-06 use the documented bodies verbatim | reviewer |

## Definition of Done (feature level)

- All tasks T-01…T-17 complete; every acceptance criterion above met and recorded by the
  implementation pipeline (FR-17).
- `./gradlew clean test` green fully offline: the 123 pre-existing tests unmodified plus every new
  test; no test needs the stage DB, `.env`, the network or the live LLM (NFR-05).
- `POST /fueling` implements the documented contract: success in any of the three source tables,
  not found, invalid id, no id (all 200); blank message 400; missing key 503; stage DB unavailable 200
  (FR-01/04/05/12).
- One correlation id per request shows the full 8-stage chain; `model=deepseek-flash` everywhere
  (unless `DEEPSEEK_MODEL` overrides); no secrets in any line (FR-13/14, NFR-01/02/07).
- M-05 checklist passes live; R-1/R-3/R-4 confirmed or their documented fallbacks applied with the
  offline suite re-run green.
- Only `SELECT`s reached the stage; before/after row counts identical; the password exists only in the
  git-ignored `.env` (FR-10, NFR-02/03).
- `.env` contains `STAGE_DB_PASSWORD` (user-provided, README-documented); no tracked file contains it.
- No new Gradle dependency; `build.gradle.kts` unchanged; existing endpoints/status codes unchanged;
  weather registry still `get_weather`-only; frozen tests pass unmodified (FR-16, NFR-06).
- README consistent with shipped behaviour; `01-requirements.md`, `02-design.md`, `03-plan.md` present
  and consistent (FR-15/17, NFR-09).
