# Specification: Fueling Order Lookup Tool

- **Slug:** fueling-order-tool
- **Date:** 2026-09-23
- **Team:** feature-design (analyst → architect → planner agents)
- **Status:** ready for implementation

---

# Fueling Order Lookup Tool — Requirements

This feature adds a backend-only Koog tool that lets the DeepSeek agent answer fueling questions from
Postman: a natural-language question containing a fueling order id in GUID format (for example
`5e12bef2-2f78-48f0-aab5-ccb6bfeb8469`) makes the model call the new tool, which looks the order up in
the **stage** PostgreSQL database (read-only, the `fueling` database only). The tool searches
`fuelings` (all monthly partitions), `fuelings_archive` and `fuelings_drop`, collects the related rows
from `partner_fueling_events`, `fueling_feedback` and `belka_tokens`, converts the epoch-millisecond
timestamps into human-readable date/time, and returns the collected data to DeepSeek, which produces
the "самери" (summary) returned as a plain-language line in the HTTP response. The agent's default
model becomes the cheapest DeepSeek model, `deepseek-flash` (DeepSeek-V4.1-Flash), still overridable
with the `DEEPSEEK_MODEL` environment variable. Hard constraints: no UI; read-only access to the stage
database; the stage password lives only in the git-ignored `.env`; all tests stay offline (the 123
existing tests stay green) and the existing trace-log chain keeps working.

## Context and goals

**Why.** The user (the developer/operator running the service locally and sending requests from
Postman) needs to answer the operational question "what does the stage data say about fueling order
`<GUID>`?" in one request: ask the backend in natural language, let DeepSeek route the question to a
backend tool, have the tool read the real stage data, and get back one understandable summary line
instead of raw tables. Today the app only knows weather; there is no path to the stage fueling data.

**Who it is for.** The developer running `./gradlew run` locally, sending requests from Postman, and
reading the console/log chain to verify each step.

**Current state (grounded in the codebase — verified):**

- Kotlin 2.3.10 / Ktor 3.3.3 / Koin 4.1.0 / Koog 1.2.0 / Gradle 8.14.1 / JVM 17; group
  `com.aiturbo`.
- One Koog agent (`weather/KoogWeatherAgent.kt`) with the `get_weather` tool; all DeepSeek traffic
  goes through Koog and the single choke point `log/LoggingPromptExecutor.kt`.
- `log/TraceLog.kt` emits the request chain with one correlation id: `stage=inbound`,
  `deepseek-request`, `deepseek-response`, `tool`, `db`, `outbound` (logger `com.aiturbo.trace`).
- Tool name/description are loaded from a JSON resource (`tools/ToolSpec.kt`,
  `src/main/resources/tools/get-weather-tool.json`); a missing/invalid resource fails fast at startup.
- `db/JdbcWeatherRecordRepository.kt` is the plain-JDBC pattern (connection per call) for the local
  PostgreSQL; `db/DbConfig.kt` resolves the local DB password as config → `DB_PASSWORD` env → `.env`.
- Routes: `POST /weather` (`{"message"}` → `{"message","answer"}`), `GET /weather/history`,
  `GET /time`, `GET /`; error contract 400/404/500/503 via StatusPages.
- Secrets convention: `.env` is git-ignored (`.gitignore` lists it) and already holds
  `DEEPSEEK_API_KEY`; `application.conf` keeps `deepseek.apiKey` empty on purpose.
- Tests: 123 `@Test` methods in 20 test classes, all offline (fakes, `MockEngine`, frozen clock); no
  real database and no live LLM required.
- Model config today: `application.conf` `deepseek.model = "deepseek-chat"`,
  `DeepSeekConfig.DEFAULT_MODEL = "deepseek-chat"`, override via `DEEPSEEK_MODEL`.

**Stage database (verified facts; the only external data source of this feature):**

- PostgreSQL 13.21, user `fueling`; the connection target (host, port, user) was supplied by the
  user — the password was supplied too but must live only in the git-ignored `.env` as
  `STAGE_DB_PASSWORD` (never in tracked config or the docs).
- `fuelings` — partitioned by `created_at`, 24 monthly partitions, ~47K rows; reading through the
  parent table works. `fuelings_archive` ~124K rows, `fuelings_drop` ~87K rows. All three share the
  columns: `fueling_id` text (GUID — the search key, e.g. `924025db-5eab-4110-856e-93f580441d02`),
  `vendor_fueling_order_id`, `user_id`, `status`, `amount` numeric, `fuel_type`, `gas_station_id`,
  `gas_pump_id`, `refueling_gun_id`, `fuel_reservation_key`, `created_at`/`updated_at` numeric
  (epoch milliseconds), `actual_amount`, `vendor_transaction_date`, `failed_reason`,
  `vendor_fuel_price`, `fueled_orders` jsonb, `discount_fuel_price`, `fueling_type`,
  `fueling_payment_type`, `finished_at` numeric, `extra` jsonb.
- `SELECT ... FROM fuelings WHERE fueling_id = '<GUID>'` was verified to work.
- Related tables linked by `fueling_id`: `fueling_feedback` (`fueling_feedback_id`, `user_id`,
  `fueling_id`, `reason_id`, `reason_message`, `requested_at`; currently 0 rows),
  `partner_fueling_events` (`event_id`, `fueling_id`, `partner_id`, `event_name`,
  `delivery_status`, `data` jsonb, `created_at`, `updated_at`; ~67K rows), `belka_tokens`
  (`token`, `fueling_id`, `created_at`, `error_type`; 57 rows).

**Goals (what success looks like).**

1. One Postman request with a natural-language question containing a GUID returns HTTP 200 and a
   single plain-language summary line about that fueling order, built only from the stage data.
2. The lookup covers `fuelings` (all partitions), `fuelings_archive` and `fuelings_drop`, and
   includes the related rows; a record found only in archive/drop is still reported.
3. The whole path is visible in the log as one correlation-id chain:
   inbound → DeepSeek request/response → tool call → database lookup → DeepSeek request/response →
   outbound, with no secrets.
4. The tool's input and output (the collected data the model receives) are documented, with a
   ready-to-use Postman example.
5. The feature is specified and implemented through the `/feature-design` and `/feature-implementation`
   pipeline; the default model everywhere is the cheapest DeepSeek model (`deepseek-flash`).

## Functional requirements

| ID | Requirement | Priority | Acceptance criterion (sketch) |
|---|---|---|---|
| FR-01 | A user question containing a fueling order GUID, sent from Postman as JSON, shall be answered by the Koog agent: request body `{"message": "..."}` and response body `{"message": "...", "answer": "..."}`, mirroring the `POST /weather` contract. The exact route/agent arrangement is the design's choice (default assumption: `POST /fueling`, see ASM-01). | Must | A `POST` with a non-empty `message` returns 200 and a JSON body with both `message` and `answer`; an empty `message` returns 400, like `POST /weather`; the response is JSON in all cases (no 500 on malformed user text). |
| FR-02 | The agent shall have a Koog tool that looks up one fueling order by its GUID, registered in the tool registry with the agent. The tool's name and description shall come from a JSON resource under `src/main/resources/tools/` following the existing `ToolSpec` convention (file is the source of truth; missing/invalid resource fails fast at startup). | Must | With `DEEPSEEK_MODEL` unset, the logged outbound `deepseek-request` shows the tool definition (`tools_count` includes it, definition contains the resource's name/description); for the example question the log shows a `stage=tool` line for this tool with the extracted GUID as its argument. |
| FR-03 | Tool input contract: exactly one parameter — the order id as a string whose canonical GUID/UUID form `8-4-4-4-12` hex digits (e.g. `5e12bef2-2f78-48f0-aab5-ccb6bfeb8469`). The value is trimmed; hex case is ignored. The model extracts the GUID from the natural-language question; other id formats (braces, `urn:uuid:`, 32-hex compact) are not required (see ASM-03). | Must | The example GUID and its uppercase equivalent both reach the lookup; the tool log line shows the argument; the documented input table (below) matches the resource parameter schema. |
| FR-04 | Malformed input shall not reach the database: if the extracted value is not a canonical GUID, the tool shall perform **no** lookup and return a clear invalid-id result (`is_error=true` in the tool log), and the agent shall answer in plain language that the id is not a valid GUID (no 500, no DB `stage=db` line). If the question contains no GUID, the tool is not called and the answer asks for the order id. | Must | Sending `{"message":"Найди заказ 12345"}` returns 200 with a plain-language "invalid id" answer; the log has no `stage=db` lookup entry for it. Sending a question without any id returns 200 and asks for the GUID. |
| FR-05 | The lookup shall search for `fueling_id` equal to the GUID across `fuelings` (all 24 monthly partitions — reading through the parent is verified), `fuelings_archive` and `fuelings_drop`; a record may live in any of the three, and the result shall state which table it came from. If the same GUID matches in more than one table, all matches are included, each labelled (ASM-04). | Must | A GUID verified present in each of `fuelings`, `fuelings_archive` and `fuelings_drop` is found and the answer/log names the correct source table each time. |
| FR-06 | For every matched fueling record, the related rows shall be collected by `fueling_id`: all `partner_fueling_events` rows, all `fueling_feedback` rows, all `belka_tokens` rows; absence of related rows is a normal empty result, not an error. | Must | With the offline fake repository seeded with 2 partner events, 1 belka token and 0 feedback rows, the tool result and the answer reflect exactly those counts; a record with no related rows still returns a complete answer. |
| FR-07 | Tool output content: the full matched row (all columns listed for the three fueling tables, including the jsonb fields `fueled_orders` and `extra`) plus the related rows, with every numeric epoch-millisecond timestamp field (`created_at`, `updated_at`, `finished_at`, `vendor_transaction_date`, partner-event `created_at`/`updated_at`, feedback `requested_at`, belka `created_at`) converted to a human-readable date/time (no raw 13-digit epoch values). The exact serialization is the design's choice; no required field may be dropped. | Must | The tool result (and the answer for the facts it summarises) contains readable dates; a manual lookup of a real stage row shows the field values matching a read-only SQL check of the same row; no listed column is missing. |
| FR-08 | The tool result shall be returned to DeepSeek, which shall produce the final plain-language summary ("самери") — found/not found, source table, status, amounts, fuel type, station/pump/gun, human-readable times, and notable related events/tokens/feedback — returned as the `answer` line. The answer must not contain raw JSON or tool names, and must not invent data beyond the tool result. | Must | For the example request the log shows a second `deepseek-request` containing the tool result and a second `deepseek-response` with the summary text; the final `answer` is one understandable line consistent with the tool data. |
| FR-09 | The agent's instructions (system prompt) shall be updated so that for questions about a fueling/order id the model always calls the lookup tool, never answers from its own memory, and phrases the answer in the user's language without tool names or JSON. | Must | The logged outbound `deepseek-request` messages show these instructions; a fueling question produces at least one tool call in the log rather than a memory answer. |
| FR-10 | The stage lookup shall be read-only: only `SELECT` statements against the stage database; no `INSERT`/`UPDATE`/`DELETE`/DDL, and no writes to any other database. | Must | Code review plus a text search of production sources finds only read statements for the stage connection; a manual lookup leaves the verified row counts unchanged. |
| FR-11 | The stage connection settings (host, port, user, database name; host/port/user supplied by the user) shall be configurable with the password resolved as `STAGE_DB_PASSWORD` from the environment or the git-ignored `.env`, with the tracked config value left empty; the local weather database configuration must not be reused or repurposed for the stage connection. | Must | With `STAGE_DB_PASSWORD` in `.env` a local lookup succeeds; with the password absent the app still starts and the tool degrades (FR-12); no tracked file contains the password value. |
| FR-12 | Stage database unavailability (host unreachable, auth failure, query timeout) shall degrade gracefully: no crash, no hang, the tool returns a clear failure result, and the endpoint answers in plain language that the stage data is temporarily unavailable. The application must start and all existing endpoints must work without any stage-DB connection. | Must | Pointing the config at an unreachable host and sending the example request returns 200 with a plain-language unavailability answer within the timeout budget; the server stays up and `GET /time` keeps working. |
| FR-13 | The default DeepSeek model for all agent tasks shall be the cheapest model, DeepSeek-V4.1-Flash (`deepseek-flash`); the current default `deepseek-chat` shall be replaced in `application.conf` and in the code default. `DEEPSEEK_MODEL` shall remain an environment override. | Must | With `DEEPSEEK_MODEL` unset, every logged `deepseek-request`/`deepseek-response` shows `model=deepseek-flash`; with `DEEPSEEK_MODEL=deepseek-chat` the logs show the override; no production default drops back to a more expensive model. |
| FR-14 | The trace chain shall keep working for the new flow with one correlation id per request: `inbound` → `deepseek-request`/`deepseek-response` (tool call requested) → `tool` (name, GUID argument, result, `is_error`) → a `db`-stage lookup entry with the outcome (found/not found, source table, related-row counts) → the follow-up DeepSeek round-trip(s) with the summary → `outbound` with status and body. No secret (stage password, API key) may appear in any line. | Must | One Postman request produces the chain above, in order, all lines carrying the same `req=` id; a secrets search over the captured log finds 0 occurrences of the stage password. |
| FR-15 | The feature documentation shall contain the tool's input and output description and a ready-to-use Postman example: exact method, URL (`http://localhost:8080/...`), headers, body with a natural-language question containing the example GUID, expected response shape, and how the tool result flows back as a DeepSeek summary. | Must | The example in this document, used verbatim against a running local server with the stage DB reachable, returns 200 with a plain-language `answer`; the documented input/output matches the shipped behavior. |
| FR-16 | Existing behavior shall be preserved: `GET /`, `GET /time`, `POST /weather`, `GET /weather/history` keep their contracts and status codes, the local weather database (`users` table) is untouched by the fueling feature, and the README is updated so it does not contradict the new flow (new tool, new route, model default). | Should | Regression run of the existing endpoints shows the same responses as before the change; a README review finds no statement contradicted by the new behavior. |
| FR-17 | The feature shall be produced through the pipeline: `/feature-design` (requirements → design → plan under `docs/features/fueling-order-tool/`) and implemented via `/feature-implementation` following the plan. | Should | `01-requirements.md`, `02-design.md`, `03-plan.md` exist, are consistent, and the implementation record follows the plan. |

### Tool input and output (behavioral contract)

**Input — what the tool receives (extracted by DeepSeek from the user's question):**

| Element | Value |
|---|---|
| Parameter | The fueling order id; a single value (one GUID per tool call; see OQ-05) |
| Type | string |
| Format | Canonical GUID/UUID: 8-4-4-4-12 hex digits, case-insensitive, surrounding whitespace ignored (ASM-03) |
| Example | `5e12bef2-2f78-48f0-aab5-ccb6bfeb8469` |
| Invalid input | Performs no lookup; returns an invalid-id result the model turns into a plain-language answer (FR-04) |
| Missing input | Tool not called; the agent asks the user for the order id (FR-04) |

**Output — what the tool returns to DeepSeek (the summary source; exact serialization is the design's choice):**

| Block | Content |
|---|---|
| Match result | found / not found, and the source table (`fuelings` / `fuelings_archive` / `fuelings_drop`) for each match |
| Fueling record | The full matched row: `fueling_id`, `vendor_fueling_order_id`, `user_id`, `status`, `amount`, `actual_amount`, `discount_fuel_price`, `vendor_fuel_price`, `fuel_type`, `gas_station_id`, `gas_pump_id`, `refueling_gun_id`, `fuel_reservation_key`, `fueling_type`, `fueling_payment_type`, `failed_reason`, `fueled_orders` jsonb, `extra` jsonb, `created_at`, `updated_at`, `finished_at`, `vendor_transaction_date` |
| Timestamps | Every epoch-millisecond field converted to human-readable date/time (default ISO-8601, UTC — ASM-08) |
| Related rows | All `partner_fueling_events` (event name, partner, delivery status, data jsonb, converted times), all `fueling_feedback` rows, all `belka_tokens` rows for the GUID |
| Counts | Number of rows returned per related table (also shown in the `db` trace line) |
| Errors | Invalid id, not found, and stage-DB unavailable are distinct, clearly worded results — never a crash or a 500 |

### Postman examples (manual verification)

Prerequisites (one-time): the server runs on `http://localhost:8080` (`./gradlew run`); the git-ignored
`.env` contains `DEEPSEEK_API_KEY` and `STAGE_DB_PASSWORD`; the stage database is reachable from the
machine; `DEEPSEEK_MODEL` is unset so the default `deepseek-flash` is used. The route below is the
default assumption ASM-01.

**Request — look up a fueling order by GUID**

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `http://localhost:8080/fueling` |
| Headers | `Content-Type: application/json` |
| Body | raw JSON: `{"message": "Найди данные по проливу для заказа 5e12bef2-2f78-48f0-aab5-ccb6bfeb8469"}` |

Steps: create the request in Postman, choose **Body → raw → JSON**, paste the body, press **Send**.

Expected response: `200 OK` (illustrative wording; real values come from the stage row)

```json
{
    "message": "Найди данные по проливу для заказа 5e12bef2-2f78-48f0-aab5-ccb6bfeb8469",
    "answer": "Заказ 5e12bef2-2f78-48f0-aab5-ccb6bfeb8469 найден в архиве (fuelings_archive): статус SUCCESS, сумма 45.00, фактическая 45.00, топливо AI-95, колонка 12, пистолет 3, заправка 21-09-2026 14:32 (UTC). По заказу 2 партнёрских события (последнее: DELIVERED) и 1 токен belka; обращений в поддержку нет."
}
```

**Other cases to try (same request, different body):**

| Case | Body | Expected |
|---|---|---|
| Found in main table | GUID of a recent fueling (matched row in `fuelings`) | 200; answer names the source table `fuelings` |
| Not found | `{"message": "Найди заказ 00000000-0000-4000-8000-000000000000"}` | 200; answer states no data was found for the id in the stage database |
| Invalid id | `{"message": "Найди заказ 12345"}` | 200; answer says the id is not a valid GUID; no DB lookup in the log |
| No id at all | `{"message": "Найди данные по проливу"}` | 200; answer asks for the order id/GUID |

**How the flow works (what happens behind the request)** — one correlation-id chain in the console
(logger `com.aiturbo.trace`), illustrative format (the design chooses the exact wording; every
element must be present, per FR-14):

```
req=xxxxxxxx stage=inbound method=POST path=/fueling ... body={"message":"Найди данные по проливу для заказа 5e12bef2-…"}
req=xxxxxxxx stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-flash tools_count=… tools=[…fueling tool definition…] messages=[system: "…", user: "Найди данные…"]
req=xxxxxxxx stage=deepseek-response model=deepseek-flash text="" tool_calls=[{"name":"<fueling tool>","args":"{\"orderId\":\"5e12bef2-…\"}"}]
req=xxxxxxxx stage=tool tool=<fueling tool> args={"orderId":"5e12bef2-…"} is_error=false result="найдено в fuelings_archive: status=…, amount=…, …"
req=xxxxxxxx stage=db tool=<fueling tool> lookup=found table=fuelings_archive events=2 tokens=1 feedback=0
req=xxxxxxxx stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-flash messages=[…, tool: "…"]
req=xxxxxxxx stage=deepseek-response model=deepseek-flash text="Заказ 5e12bef2-… найден в архиве…" tool_calls=[]
req=xxxxxxxx stage=outbound status=200 body={"message":"…","answer":"Заказ 5e12bef2-… найден…"}
```

### End-to-end acceptance criteria (walkthrough)

1. Send the Postman request with the example GUID (a GUID verified to exist on the stage): HTTP 200,
   one plain-language summary line; a read-only SQL check of the same row confirms the summarized
   facts.
2. Repeat with a GUID known to be only in `fuelings_archive` and one only in `fuelings_drop`: both
   found, and the answer names the correct source table.
3. A well-formed but unknown GUID: 200 with a plain-language "not found" answer (no error status).
4. A malformed/absent id: 200 with a plain-language clarification; the log shows no database lookup.
5. Stage DB unreachable or timed out: 200 with a plain-language "temporarily unavailable" answer; the
   request does not hang; `/time` and `/weather` still work.
6. `./gradlew test`: all tests green, fully offline (no stage DB, no live LLM), including the 123
   pre-existing tests.
7. A search of the repository and of the captured log output finds zero occurrences of the stage
   password.

## Non-functional requirements

| ID | Category | Measurable target |
|---|---|---|
| NFR-01 | Observability | For one fueling question, the log (shipped logback config, logger `com.aiturbo.trace`) contains, in order, one `stage=inbound`, the `stage=deepseek-request`/`stage=deepseek-response` round-trip that requests the tool, one `stage=tool` per invocation with the GUID argument and result, one `stage=db` lookup entry with hit/miss, source table and related-row counts, the follow-up DeepSeek round-trip(s) carrying the summary, and one `stage=outbound` with status 200 — all carrying the same `req=` correlation id. |
| NFR-02 | Security | After a full manual run, the `STAGE_DB_PASSWORD` value appears 0 times in tracked files, in any log line, and in any HTTP response body; the stage credentials are resolved only from the environment or the git-ignored `.env` (`.gitignore` keeps `.env` excluded). |
| NFR-03 | Data safety | The stage database receives read-only traffic: 0 `INSERT`/`UPDATE`/`DELETE`/DDL statements in the production sources of this feature; row counts of the touched tables are identical before and after a manual lookup. |
| NFR-04 | Performance / resilience | With the stage DB reachable, one tool lookup completes within 5 s (measured between its `tool` and `db` log lines on a manual run); a connect/query timeout (proposed default: 10 s, ASM-07) is enforced, so with the stage DB unreachable the endpoint still responds within 30 s and never hangs. |
| NFR-05 | Testability / offline | `./gradlew test` passes with no real database and no live LLM; the 123 existing tests remain green (adapted only where the model default changes); all new coverage uses a fake stage-lookup repository and offline fixtures — no test may require the stage DB, its password, or network access. |
| NFR-06 | Compatibility | Existing endpoints and status codes are unchanged (`GET /` 200; `GET /time` 200/400/404; `POST /weather` 200/400/503; `GET /weather/history` 200/400); the stack stays Kotlin 2.3.10 / Ktor 3.3.3 / Koin 4.1.0 / Koog 1.2.0 / JVM 17, and no new Gradle dependency is added unless strictly needed and justified. |
| NFR-07 | Cost | The default model for every LLM call is the cheapest DeepSeek tier (`deepseek-flash`); no default path selects a more expensive model; the `DEEPSEEK_MODEL` override preserves the ability to change models without a code change. |
| NFR-08 | Robustness | Malformed ids, not-found results, oversized `jsonb` payloads, stage-DB errors and timeouts never cause a 500 or a crash; request-level validation errors keep the 400 contract; the app starts and serves all existing endpoints with the stage DB absent. |
| NFR-09 | Process / documentation | The feature is produced through `/feature-design` (this document, then `02-design.md`, `03-plan.md`) and implemented via `/feature-implementation`; the tool's input/output and the Postman example are present in the documentation, and the README is updated where it would otherwise contradict the new behavior. |

## Out of scope

- **Payment and vendors databases (and any other stage database).** The user explicitly limited the
  work to the `fueling` database; payment/vendors data is not read, joined, or exposed.
- **UI / frontend implementation.** Explicitly excluded by the request ("no UI — backend only").
- **Writes to the stage database.** Read-only requirement (FR-10); no inserts, updates, deletes or
  schema changes.
- **New endpoints beyond the fueling question flow** (history/search endpoints for fuelings,
  batch lookup APIs). Not requested; one question → one answer is the scope.
- **Changes to the local weather database or weather behavior.** The `users` table, `/weather` and
  the Open-Meteo flow stay as they are (except the model default, FR-13).
- **Caching of stage lookups, scheduled syncs or exports.** Not requested; every question performs a
  live read-only lookup.
- **Authentication/authorization on the new route.** Not requested; the service is run locally as
  today.
- **Retries of DeepSeek calls or fallback to a more expensive model.** The cheapest-model choice is
  explicit (FR-13); no fallback chain is designed.
- **New Gradle dependencies** unless strictly needed and justified (NFR-06) — the JDBC driver and
  Koog/Ktor stack are already present.

## Open questions

| ID | Question | Why it matters | Default assumption (ASM-xx) |
|---|---|---|---|
| OQ-01 | Which route and agent arrangement should serve the fueling question — a new dedicated `POST /fueling`, or the existing `POST /weather` (whose agent would simply gain the new tool)? | Decides the exact Postman URL, the endpoint contract under test and where the tool is registered; changing it after the design means rewriting the Postman recipe and route tests. | ASM-01 (risky, affects FR-01/FR-15): a dedicated `POST /fueling` with the same `{"message"}` → `{"message","answer"}` contract as `POST /weather`; the agent serving it has the fueling tool (the existing agent may be extended or a sibling created — design's choice). If the design chooses another arrangement, the Postman example must be updated accordingly. |
| OQ-02 | Is the GUID the `fueling_id` column, or should the lookup also match `vendor_fueling_order_id` (or any other order identifier)? | The verified lookup works on `fueling_id`; `vendor_fueling_order_id` could theoretically also be asked by an operator and is not a GUID by contract. | ASM-02: the GUID is `fueling_id`; no other identifier is searched. The example GUID in the Postman recipe must be a real `fueling_id` (confirm against stage data before the manual run). |
| OQ-03 | Which GUID spellings are accepted, and is the UUID version checked? | Affects FR-03/FR-04 validation and what the agent rejects; the request said "GUID format (example)" and the design brief mentioned UUID v4. | ASM-03: canonical trimmed, case-insensitive `8-4-4-4-12` hex; no version/variant check (any UUID version accepted, since real stage rows may contain any); braces/`urn:uuid:`/compact forms rejected with the invalid-id answer. |
| OQ-04 | If the same GUID exists in more than one of `fuelings`/`fuelings_archive`/`fuelings_drop`, what should the answer show? | Archive/drop may retain copies of a moved order; hiding a match could mislead the operator. | ASM-04: return every match, each labelled with its source table (FR-05). |
| OQ-05 | What exactly happens for malformed input, and what about several GUIDs in one message? | Defines FR-04's observable contract; the user asked "what happens on malformed input". | ASM-05: malformed → no DB query, invalid-id tool result, plain-language answer, HTTP 200; no GUID → the agent asks for the id; several GUIDs → the model calls the tool once per GUID (the existing multi-round agent loop), batch lookup is not required. |
| OQ-06 | Should a well-formed GUID with no data be an HTTP error (404) or a normal answer? | Affects the endpoint contract and the Postman expectations; a 404 would conflict with the "plain-language answer" flow. | ASM-06: HTTP 200 with a plain-language "not found in stage data" answer; no 404. |
| OQ-07 | What are the acceptable latency/timeout budgets when the stage DB is slow or unreachable? | The stage DB is remote; without a timeout a lookup could hang the request. | ASM-07 (proposed, confirm): per-lookup connect/query timeout of 10 s; endpoint answers within 30 s worst case; no startup dependency on the stage DB (FR-12, NFR-04). |
| OQ-08 | In which timezone/format should the epoch-millisecond timestamps be rendered? | Operators compare the answer with local/office time; raw epoch millis are explicitly unwanted. | ASM-08: human-readable ISO-8601 UTC (e.g. `2026-09-21 14:32:11`); if Moscow/other timezone is preferred, only the rendering changes. Any numeric field that turns out not to be epoch millis must still be rendered human-readably. |
| OQ-09 | How should large related-row sets be bounded (e.g. many `partner_fueling_events` for one fueling), and in what order? | `partner_fueling_events` has ~67K rows; a single fueling usually has few, but a cap protects the model context and the log line. | ASM-09: include all rows for the single fueling id, event-like lists newest first, and state the counts; if a set is exceptionally large the design may cap it, but then the total count must be stated in the result. |
| OQ-10 | What is the stage database name (the user supplied host, port, user and password only)? | Needed for the JDBC connection setting; guessing wrong makes every lookup fail at runtime. | ASM-10 (unverified): database name `fueling` (same as the user). Confirm before the manual smoke run. |
| OQ-11 | In which language must the final summary line be? | The user writes in Russian and the request is Russian; the existing weather agent answers in the user's language. | ASM-11: the language of the user's question (Russian for the Postman examples). |
| OQ-12 | Is `deepseek-flash` confirmed to support tool (function) calling with the Koog client, and should all flows switch to it? | The whole flow depends on the model emitting a tool call; the Koog client requires the Tools capability; the API id was verified via `GET /models`, but tool support on this model is the load-bearing assumption. | ASM-12 (risky): `deepseek-flash` supports function calling; all LLM flows use the configured default with no fallback; if it does not, this must be raised as a blocker before implementation (the only alternative named would be keeping a more expensive model). |
| OQ-13 | Should any collected fields be masked (e.g. `belka_tokens.token`, `user_id`) in the tool result/log/answer? | The stage data may contain identifiers the operator does not need in logs; the request asked for the data but said nothing about masking. | ASM-13: no masking — all retrieved fields are available to the model and appear in tool results; only credentials (password, API key) are never logged (NFR-02). Revisit if the user wants token/user-id redaction. |

## Glossary

- **GUID / UUID** — a 128-bit identifier in the canonical `8-4-4-4-12` hex form, e.g.
  `5e12bef2-2f78-48f0-aab5-ccb6bfeb8469`; used in this feature as the fueling order id (`fueling_id`).
- **Fueling (пролив)** — one refuelling transaction of an order; stored as a row in the stage tables.
- **Stage database** — the read-only PostgreSQL 13.21 instance at the user-supplied host/port with
  user `fueling`; the only external data source of this feature. Its password lives only in the
  git-ignored `.env` as `STAGE_DB_PASSWORD`.
- **`fuelings`** — the current fueling table, partitioned by `created_at` into 24 monthly partitions;
  read through the parent table. **`fuelings_archive`** / **`fuelings_drop`** — archive and dropped
  copies; a GUID may be in any of the three.
- **Related tables** — `partner_fueling_events` (partner delivery events, jsonb `data`),
  `fueling_feedback` (support complaints, currently empty), `belka_tokens` (belka tokens per
  fueling), all linked by `fueling_id`.
- **Epoch milliseconds** — numeric timestamps (`created_at`, `updated_at`, `finished_at`, …) that
  must be converted to human-readable date/time before reaching the model.
- **Tool** — a Koog-registered function the LLM may invoke; existing: `get_weather`; this feature
  adds the fueling lookup tool (name chosen by the design, described by a JSON resource under
  `src/main/resources/tools/`).
- **Саммари (самери)** — the plain-language summary the model writes from the tool result and returns
  in the `answer` field.
- **DeepSeek** — the external LLM API (OpenAI-compatible `chat/completions`); all traffic goes
  through Koog and the `LoggingPromptExecutor` choke point.
- **`deepseek-flash`** — the cheapest DeepSeek model (DeepSeek-V4.1-Flash), the new default model id
  for all LLM calls; override via `DEEPSEEK_MODEL`.
- **Correlation id (`req=`)** — the per-request identifier tying the trace-chain entries together
  (`log/TraceLog.kt`, `plugins/RequestTracing.kt`).
- **Trace chain** — the ordered log stages `inbound`, `deepseek-request`, `deepseek-response`,
  `tool`, `db`, `outbound` on logger `com.aiturbo.trace`.
- **Offline tests** — the suite strategy using fakes, `MockEngine` and a frozen clock; no real
  database (local or stage) and no live LLM in any test.
- **Feature-design pipeline** — the `/feature-design` skill and its agents (analyst → architect →
  planner) producing `01-requirements.md`, `02-design.md`, `03-plan.md`, followed by
  `/feature-implementation`.

---

# Fueling Order Lookup Tool — System Design

## Context and goals

The service today knows one Koog agent (weather) and one external data source (Open-Meteo + the local
`users` database). This feature adds a second, independent vertical slice: a `find_fueling` Koog tool
that reads one fueling order by its GUID from the **stage** PostgreSQL database (`fueling` database:
`fuelings`, `fuelings_archive`, `fuelings_drop` plus the related tables), converts the epoch-millisecond
timestamps to human-readable date/time, and hands the collected data back to DeepSeek, which returns a
one-line plain-language summary. A new `POST /fueling` endpoint (mirroring the `POST /weather` contract)
serves the question; the default DeepSeek model becomes `deepseek-flash`. Hard constraints: no UI,
read-only stage access, password only in the git-ignored `.env`, all tests offline, existing 123 tests
green, existing trace chain intact.

## Architecture overview

The feature is a new vertical slice that reuses every existing layer (Koog tool, agent strategy,
`LoggingPromptExecutor`, `TraceLog`, JDBC-per-call, Ktor route, Koin module, JSON tool spec) and shares
the LLM plumbing with the weather slice (`PromptExecutor`, `LLModel`, `ToolJsonRenderer`, `TraceLog`).
It adds exactly two new infrastructure types: `StageDbConfig` and `JdbcStageFuelingRepository`.

```
Postman ──POST /fueling──► plugins/FuelingRouting.kt
                                  │  (beginTrace, blank-message 400, withContext(trace))
                                  ▼
                          fueling/FuelingAgent (interface)
                                  │
                          KoogFuelingAgent ── functionalStrategy loop:
                                  │             requestLLM → executeTools → sendToolResults
                                  │             (TraceLog.tool per call)
                                  ▼
                          ai.koog PromptExecutor  ──► log/LoggingPromptExecutor (stage=deepseek-request/response)
                                  │                                    │
                                  │                                    ▼
                                  │                            DeepSeek API (model deepseek-flash)
                                  ▼
                          fueling/FindFuelingTool  (SimpleTool<FindFuelingArgs>, name+description from
                                  │                  resources/tools/find-fueling-tool.json)
                          ┌───────┴─────────────────────────────┐
                          │ FuelingId.canonicalize (no DB call) │  invalid → InvalidFuelingIdException
                          ▼                                     │
                  db/StageFuelingRepository  (interface)        │
                          │                                     │
                  db/JdbcStageFuelingRepository                 │
                          │  DriverManager per call, 10 s timeouts, prepared statements, SELECT only
                          ▼                                     │
                  STAGE PostgreSQL (fueling: fuelings / archive / drop + events/feedback/belka_tokens)
                          │                                     │
                          └── FuelingLookupResult ──► fueling/FuelingReport.render(...) ──► tool result text
                                                     │
                                                     └── TraceLog.fuelingLookup*  (stage=db line)
```

Request path (one line per stage, one `req=` id): `inbound` → `deepseek-request` → `deepseek-response`
(tool call) → `db` (lookup outcome, emitted by the tool) → `tool` (args + result + `is_error`) →
`deepseek-request` → `deepseek-response` (summary) → `outbound`.

Two separate agents, two separate registries: `/weather` (and the `GET /time` LLM fallback) keep the
`get_weather`-only registry, `/fueling` gets its own `find_fueling`-only registry. Sharing one registry
would put the fueling tool into every weather/`/time` request, breaking the existing
`TraceChainIntegrationTest` assertions (`tools_count=1`, `"name":"get_weather"`) and sending pointless
tokens to the model (NFR-06, NFR-07).

## Components

### `db/StageDbConfig` (new — `src/main/kotlin/com/aiturbo/db/StageDbConfig.kt`)

Responsibility: stage connection settings, resolved the same way as `DbConfig` (config value → env →
`.env`), but **without** a hard-coded fallback password.

```kotlin
data class StageDbConfig(
    val host: String = DEFAULT_HOST,            // "postgres.stage.turboapp.ru"
    val port: Int = DEFAULT_PORT,               // 25432
    val database: String = DEFAULT_DATABASE,    // "fueling"          (ASM-10, verify in the smoke run)
    val user: String = DEFAULT_USER,            // "fueling"
    val password: String = "",                  // config → STAGE_DB_PASSWORD env → .env; never logged
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS, // 10 (ASM-07) — connect, socket and query timeout
) {
    val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$database"

    companion object {
        fun from(config: ApplicationConfig): StageDbConfig   // stageDb.host|port|name|user|password|timeoutSeconds
    }
}

/** config → env → .env; no development default (unlike the weather database). */
fun resolveStageDbPassword(configValue: String, envValue: String?, fileValue: String?): String
```

Dependencies: Ktor `ApplicationConfig`, the shared `loadDotenv()` helper. Nothing connects at
construction — the application starts with the stage database absent (FR-12).

### `db/StageFuelingRepository` + DTOs (new — `src/main/kotlin/com/aiturbo/db/StageFuelingRepository.kt`)

Responsibility: the read port for one fueling order; defines the stage schema shape the rest of the code
sees. Blocking, like `WeatherRecordRepository` (callers wrap in `withContext(Dispatchers.IO)`).

```kotlin
enum class FuelingSource(val tableName: String) {
    FUELINGS("fuelings"), ARCHIVE("fuelings_archive"), DROP("fuelings_drop")
}

data class FuelingMatch(val source: FuelingSource, val record: FuelingRecord)
data class RelatedRows(
    val events: List<PartnerFuelingEvent>,     // total = events.size
    val feedback: List<FuelingFeedback>,
    val tokens: List<BelkaToken>,
)
data class FuelingLookupResult(
    val fuelingId: String,                     // canonical lowercase GUID
    val matches: List<FuelingMatch>,           // one entry per source table that has the row (FR-05/ASM-04)
    val related: RelatedRows,                  // empty lists when nothing matched (FR-06)
)

interface StageFuelingRepository {
    /** Reads the fueling by [fuelingId] from all three tables plus all related rows. Throws DatabaseUnavailableException. */
    fun findByFuelingId(fuelingId: String): FuelingLookupResult
}
```

### `db/JdbcStageFuelingRepository` (new — `src/main/kotlin/com/aiturbo/db/JdbcStageFuelingRepository.kt`)

Responsibility: the only place that talks to the stage database. Plain JDBC, connection per call (so the
app starts fine with no stage DB), `PreparedStatement` only, `SELECT` only (FR-10).

```kotlin
class JdbcStageFuelingRepository(private val config: StageDbConfig) : StageFuelingRepository {
    override fun findByFuelingId(fuelingId: String): FuelingLookupResult
    // internals: one connection per call, opened with Properties(user, password,
    // loginTimeout/connectTimeout/socketTimeout = timeoutSeconds, ApplicationName="ai-turbo"),
    // statement.queryTimeout = timeoutSeconds, SQLException → DatabaseUnavailableException
}
```

`ApplicationName=ai-turbo` is a one-line pgjdbc connection property: it makes this read-only client
identifiable in `pg_stat_activity`, which supports the NFR-03 check ("no write traffic from this app").
The connection properties are the only place the password is used; it is never logged, never rendered.

Query order (fixed, so it is predictable in tests and logs):

1. `SELECT <columns> FROM fuelings WHERE fueling_id = ?`
2. `SELECT <columns> FROM fuelings_archive WHERE fueling_id = ?`
3. `SELECT <columns> FROM fuelings_drop WHERE fueling_id = ?`
4. only when at least one match:
   `SELECT event_id, fueling_id, partner_id, event_name, delivery_status, data, created_at, updated_at FROM partner_fueling_events WHERE fueling_id = ? ORDER BY created_at DESC NULLS LAST`
   `SELECT fueling_feedback_id, user_id, fueling_id, reason_id, reason_message, requested_at FROM fueling_feedback WHERE fueling_id = ? ORDER BY requested_at DESC NULLS LAST`
   `SELECT token, fueling_id, created_at, error_type FROM belka_tokens WHERE fueling_id = ? ORDER BY created_at DESC NULLS LAST`

Injection safety: the only user-controlled value (the GUID) is a bind parameter; table names come from
the `FuelingSource` enum (compile-time constants), never from input. `fuelings` is read through its
partitioned parent — verified to work.

### `fueling/FuelingId` (new — `src/main/kotlin/com/aiturbo/fueling/FuelingId.kt`)

Responsibility: GUID contract from FR-03 and the two domain failures (FR-04, FR-12).

```kotlin
object FuelingId {
    val PATTERN: Regex        // ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$
    /** Trimmed, canonical 8-4-4-4-12 → lowercase; null for anything else (no version/variant check, ASM-03). */
    fun canonicalize(raw: String): String?
}

/** The extracted value is not a canonical GUID — no lookup was performed (FR-04). */
class InvalidFuelingIdException(message: String) : RuntimeException(message)

/** The stage lookup could not be performed: unreachable, auth failure or timeout (FR-12). */
class StageDatabaseUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

`java.util.UUID.fromString` is deliberately **not** used for validation: it accepts short groups
(`1-1-1-1-1`), which FR-03/FR-04 reject.

### `fueling/FindFuelingTool` (new — `src/main/kotlin/com/aiturbo/fueling/FindFuelingTool.kt`)

Responsibility: the Koog tool — validate the id, read stage, log the lookup outcome, render the result.

```kotlin
@Serializable
data class FindFuelingArgs(
    @property:LLMDescription("Идентификатор заказа (GUID в формате 8-4-4-4-12), например 5e12bef2-2f78-48f0-aab5-ccb6bfeb8469")
    val orderId: String,
)

class FindFuelingTool(
    private val repository: StageFuelingRepository,
    spec: ToolSpec,                                    // name + description from the JSON resource
) : SimpleTool<FindFuelingArgs>(argsType = typeToken<FindFuelingArgs>(), name = spec.name, description = spec.description) {
    override suspend fun execute(args: FindFuelingArgs): String
}
```

`execute` behaviour (exact):

1. `FuelingId.canonicalize(args.orderId)` → `null` ⇒ throw `InvalidFuelingIdException("Идентификатор
   '<raw>' не является корректным GUID (формат 8-4-4-4-12). Запрос в базу не выполнялся.")`. No repository
   call, no `stage=db` line — the strategy's `stage=tool` line carries `is_error=true` (FR-04).
2. `withContext(Dispatchers.IO) { repository.findByFuelingId(canonical) }`.
3. `DatabaseUnavailableException` ⇒ `TraceLog.fuelingLookupUnavailable(currentId, name, cause.message)` and
   throw `StageDatabaseUnavailableException("Данные stage временно недоступны: <cause>. Повторите запрос
   позже.", cause)` ⇒ `stage=tool … is_error=true`; the model answers "temporarily unavailable" (FR-12).
4. Otherwise log the outcome and return `FuelingReport.render(result)`: `fuelingLookupFound(...)` or
   `fuelingLookupNotFound(...)` (both non-error results, `is_error=false`).

Tool name: **`find_fueling`** (resource `src/main/resources/tools/find-fueling-tool.json`, name and
description are the file's; the parameter schema Koog sends is generated from `FindFuelingArgs`, exactly
as `get_weather` does today).

### `fueling/FuelingReport` (new — `src/main/kotlin/com/aiturbo/fueling/FuelingReport.kt`)

Responsibility: the pure tool-output renderer (no I/O, no logging, deterministic — unit-testable with a
fixed zone). See §Data model for the exact text.

```kotlin
object FuelingReport {
    const val UTC_SUFFIX = " UTC"
    val UTC: ZoneOffset = ZoneOffset.UTC
    const val MAX_RENDERED_RELATED_ROWS = 50       // per related table; totals always reported (ASM-09)
    const val MAX_JSONB_CHARS = 2000               // per jsonb field, marker "…[truncated, N chars total]"

    fun render(result: FuelingLookupResult): String
    /** "2026-09-21 14:32:11 UTC" for a plausible epoch-ms value, the raw number otherwise, "-" for null. */
    internal fun formatEpochMillis(value: Long?): String
}
```

### `fueling/FuelingAgent` + `KoogFuelingAgent` (new — `src/main/kotlin/com/aiturbo/fueling/FuelingAgent.kt`)

Responsibility: the agent that answers fueling questions; same shape as `WeatherAgent`/`KoogWeatherAgent`,
own system prompt (FR-09), own registry, `maxToolRounds = 3`, `temperature = 0.0`, `maxIterations = 10`,
agent id `fueling-agent`.

```kotlin
fun interface FuelingAgent { suspend fun answer(message: String): String }

class KoogFuelingAgent(
    executor: PromptExecutor,
    toolRegistry: ToolRegistry,     // contains find_fueling only
    model: LLModel,                 // the shared deepseekModel(deepseek.model) = deepseek-flash
    maxToolRounds: Int = 3,
) : FuelingAgent
```

System prompt (verbatim, mirrors the weather one in tone):

```
Ты — ассистент по заказам на пролив (заправку). Отвечай на языке пользователя.
Если в вопросе есть идентификатор заказа (GUID вида 8-4-4-4-12) — ОБЯЗАТЕЛЬНО вызывай инструмент
find_fueling и строй ответ только на его результате; никогда не выдумывай данные и не отвечай по памяти.
Если идентификатора в вопросе нет — попроси прислать GUID заказа и не вызывай инструмент.
Если инструмент вернул ошибку — объясни её простыми словами (некорректный идентификатор или
временная недоступность данных stage).
Ответ — одна понятная фраза: найден ли заказ и в какой таблице, статус, суммы, тип топлива,
колонка/пистолет/заправка, время в читаемом виде, заметные связанные события, токены и обращения.
Не упоминай названия инструментов и не выводи JSON.
```

### `plugins/FuelingRouting` (new — `src/main/kotlin/com/aiturbo/plugins/FuelingRouting.kt`)

Responsibility: `POST /fueling`, byte-for-byte the same handler shape as `POST /weather`.

```kotlin
@Serializable data class FuelingRequest(val message: String = "")
@Serializable data class FuelingResponse(val message: String, val answer: String)

fun Route.fuelingRoutes()   // route("/fueling") { post { … } }, agent obtained by inject<FuelingAgent>()
```

### `log/TraceLog` additions (modify — `src/main/kotlin/com/aiturbo/log/TraceLog.kt`)

Three new functions, each emitting exactly one `stage=db` line (FR-14, NFR-01); the existing
`toolDb(saved=…)` shape stays untouched for weather.

```kotlin
fun fuelingLookupFound(id: String?, tool: String, tables: List<String>, events: Int, tokens: Int, feedback: Int, capped: Boolean)
fun fuelingLookupNotFound(id: String?, tool: String, tables: List<String>)
fun fuelingLookupUnavailable(id: String?, tool: String, reason: String?)
```

### Koin wiring (modify — `src/main/kotlin/com/aiturbo/Application.kt`)

The existing `appModules(deepseek, db, weather)` keeps its signature and its bindings untouched (the
frozen `AppModulesTest` asserts them). The feature adds a sibling module, composed in
`Application.module`:

```kotlin
val FUELING_TOOL_SPEC = named("fuelingToolSpec")
val FUELING_TOOL_REGISTRY = named("fuelingToolRegistry")

fun fuelingModule(stageDb: StageDbConfig, apiKeyConfigured: Boolean): Module = module {
    single(createdAtStart = true, qualifier = FUELING_TOOL_SPEC) {          // fail fast at startup
        ToolSpecLoader.load(ToolSpecLoader.FUELING_RESOURCE_PATH)
    }
    single<StageFuelingRepository> { JdbcStageFuelingRepository(stageDb) }
    single { FindFuelingTool(get(), get(FUELING_TOOL_SPEC)) }
    single(qualifier = FUELING_TOOL_REGISTRY) { ToolRegistry.builder().tool(get<FindFuelingTool>()).build() }
    single<FuelingAgent> {
        if (apiKeyConfigured) KoogFuelingAgent(get(), get(FUELING_TOOL_REGISTRY), get())
        else FuelingAgent { throw WeatherUnavailableException("DeepSeek API key is not configured") }
    }
}
```

`Application.module` (default branch only) installs
`listOf(appModules(deepseek = …, db = …, weather = …), fuelingModule(stageDb = StageDbConfig.from(environment.config), apiKeyConfigured = deepseek.apiKey.isNotBlank()))`.
The `overrideModules` path is unchanged, so every existing route test keeps working. `get<PromptExecutor>()`
and `get<LLModel>()` come from `appModules` (the single choke point and the shared model id).

### `tools/ToolSpec` + resource (modify/new)

`ToolSpecLoader` gains `const val FUELING_RESOURCE_PATH = "tools/find-fueling-tool.json"`; the new
resource follows the shipped convention (name, description, parameter documentation):

```json
{
  "name": "find_fueling",
  "description": "Находит заказ на пролив (заправку) по идентификатору (GUID) в данных stage: ищет запись в таблицах fuelings, fuelings_archive, fuelings_drop и связанные события, обращения и токены. Возвращает поля заказа с читаемыми датами.",
  "parameters": {
    "type": "object",
    "properties": {
      "orderId": { "type": "string", "description": "Идентификатор заказа (GUID в формате 8-4-4-4-12)" }
    },
    "required": ["orderId"]
  }
}
```

### Logging design — the trace chain

One correlation id, the same `req=` id as today (`CallTrace` in the coroutine context; the tool runs
inside the agent's context, which runs inside `withContext(trace)` in the route). New/changed lines:

```
req=<id> stage=inbound method=POST path=/fueling query=- client=… body={"message":"Найди данные по проливу для заказа 5e12bef2-…"}
req=<id> stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-flash tool_choice=- tools_count=1 tools=[{"type":"function","function":{"name":"find_fueling",…}}] messages=[system: "Ты — ассистент по заказам…", user: "Найди данные…"]
req=<id> stage=deepseek-response model=deepseek-flash text="" tool_calls=[{"name":"find_fueling","args":"{\"orderId\":\"5e12bef2-…\"}"}]
req=<id> stage=db tool=find_fueling lookup=found records=1 tables=fuelings_archive events=2 tokens=1 feedback=0
req=<id> stage=tool tool=find_fueling args={"orderId":"5e12bef2-…"} is_error=false result="НАЙДЕНО: 1 (fuelings_archive) …"
req=<id> stage=deepseek-request endpoint=… model=deepseek-flash tool_choice=- tools_count=1 tools=[…] messages=[…, tool: "…"]
req=<id> stage=deepseek-response model=deepseek-flash text="Заказ 5e12bef2-… найден в архиве…" tool_calls=[]
req=<id> stage=outbound status=200 body={"message":"…","answer":"…"}
```

`stage=db` variants: `lookup=not_found records=0`, `lookup=unavailable reason="…"`, plus an optional
` capped=true` when the rendered related rows were cut by `MAX_RENDERED_RELATED_ROWS`. No `stage=db` line
exists for an invalid id (FR-04). The existing truncation (`MAX_BODY_CHARS = 4096`) and the "no secret is
ever logged" rule apply unchanged: `TraceLog` receives no config object, no password, no API key — the
repository never logs and the tool only passes the failure message (FR-14, NFR-02).

Order note: the requirements' prose lists `tool` before `db`; the shipped order is `db` → `tool` because
the tool emits the lookup line while executing and the strategy emits the `tool` line after the call
returns — identical to the verified weather chain (`TraceChainIntegrationTest`). Both lines exist, in one
request, with one id.

### Configuration changes (`src/main/resources/application.conf`)

```hocon
deepseek { model = "deepseek-flash"   model = ${?DEEPSEEK_MODEL} }   # was "deepseek-chat" (FR-13)

stageDb {
    # Stage PostgreSQL (read-only; the password never lives in this file).
    # It is resolved: 1. STAGE_DB_PASSWORD env, 2. the git-ignored .env file. No default.
    host = "postgres.stage.turboapp.ru"   host = ${?STAGE_DB_HOST}
    port = "25432"                        port = ${?STAGE_DB_PORT}
    name = "fueling"                      name = ${?STAGE_DB_NAME}
    user = "fueling"                      user = ${?STAGE_DB_USER}
    password = ""                         password = ${?STAGE_DB_PASSWORD}
    timeoutSeconds = "10"                 timeoutSeconds = ${?STAGE_DB_TIMEOUT_SECONDS}
}
```

The local weather database keys (`db.*`) are untouched, and the stage settings are never resolved from
them (FR-11). Manual step for the operator: add `STAGE_DB_PASSWORD=…` to the git-ignored `.env`.

### Files to create / modify

Create:

| File | Content |
|---|---|
| `src/main/kotlin/com/aiturbo/db/StageDbConfig.kt` | `StageDbConfig`, `resolveStageDbPassword` |
| `src/main/kotlin/com/aiturbo/db/StageFuelingRepository.kt` | `StageFuelingRepository`, `FuelingSource`, `FuelingLookupResult`, `FuelingMatch`, `FuelingRecord`, `RelatedRows`, `PartnerFuelingEvent`, `FuelingFeedback`, `BelkaToken` |
| `src/main/kotlin/com/aiturbo/db/JdbcStageFuelingRepository.kt` | the JDBC implementation |
| `src/main/kotlin/com/aiturbo/fueling/FuelingId.kt` | `FuelingId`, `InvalidFuelingIdException`, `StageDatabaseUnavailableException` |
| `src/main/kotlin/com/aiturbo/fueling/FindFuelingTool.kt` | `FindFuelingArgs`, `FindFuelingTool` |
| `src/main/kotlin/com/aiturbo/fueling/FuelingReport.kt` | the tool-output renderer |
| `src/main/kotlin/com/aiturbo/fueling/FuelingAgent.kt` | `FuelingAgent`, `KoogFuelingAgent`, `SYSTEM_PROMPT` |
| `src/main/kotlin/com/aiturbo/plugins/FuelingRouting.kt` | `FuelingRequest`, `FuelingResponse`, `fuelingRoutes()` |
| `src/main/resources/tools/find-fueling-tool.json` | tool name/description/parameter doc |
| `src/test/kotlin/com/aiturbo/FuelingTestFixtures.kt` | `FakeStageFuelingRepository` + sample rows shared by the new tests |
| `src/test/kotlin/com/aiturbo/StageDbConfigTest.kt`, `FuelingIdTest.kt`, `FuelingReportTest.kt`, `FindFuelingToolTest.kt`, `FuelingRoutesTest.kt`, `KoogFuelingAgentTest.kt`, `FuelingModulesTest.kt`, `FuelingChainIntegrationTest.kt`, `StageReadOnlyGuardTest.kt` | the offline suite (see the FR/NFR tables below) |

Modify:

| File | Change |
|---|---|
| `src/main/kotlin/com/aiturbo/Application.kt` | `DEFAULT_MODEL = "deepseek-flash"`; `FUELING_TOOL_SPEC`/`FUELING_TOOL_REGISTRY` qualifiers; `fuelingModule(...)`; compose both modules in `Application.module` |
| `src/main/kotlin/com/aiturbo/plugins/Routing.kt` | register `fuelingRoutes()` inside `routing { }` |
| `src/main/kotlin/com/aiturbo/log/TraceLog.kt` | the three `fuelingLookup*` functions |
| `src/main/kotlin/com/aiturbo/tools/ToolSpec.kt` | `ToolSpecLoader.FUELING_RESOURCE_PATH` |
| `src/main/resources/application.conf` | `deepseek.model` default; `stageDb { … }` block |
| `README.md` | API table, fueling section + Postman example, trace examples (`deepseek-flash`), config table (`STAGE_DB_*`), test list, feature-docs link |
| `.env` (git-ignored, not in the repo) | add `STAGE_DB_PASSWORD=…` — operator step, documented in README |

No new Gradle dependency (NFR-06): the PostgreSQL driver, Koog, Ktor and kotlinx.serialization are
already on the classpath.

## API design

### `POST /fueling` (external)

| Aspect | Contract |
|---|---|
| Method / path | `POST http://localhost:8080/fueling` |
| Headers | `Content-Type: application/json` |
| Request body | `{"message": "<natural-language question containing the order GUID>"}` — `FuelingRequest(message: String = "")` |
| 200 body | `{"message": "<trimmed user message>", "answer": "<plain-language summary from DeepSeek>"}` — `FuelingResponse` |
| 400 | Blank/absent `message` → `{"error":"Field 'message' is required"}` (same wording as `/weather`); invalid JSON body → `{"error":"Invalid request body"}` (existing `ContentTransformationException` handler) |
| 503 | No `DEEPSEEK_API_KEY` → `{"error":"DeepSeek API key is not configured"}` (via `WeatherUnavailableException` → existing StatusPages handler) |
| 200 (degraded) | Stage DB unreachable/timeout → the tool result is an error, the model answers that the stage data is temporarily unavailable; **no 503** (FR-12, ASM-07) |
| 200 (business) | Unknown but valid GUID → "not found in stage data"; malformed GUID / no GUID → the model asks for a valid order id (FR-04, ASM-05, ASM-06) |
| 500 | Only for an unexpected failure (e.g. the DeepSeek call itself fails) — same as `/weather` |
| Auth | none (local service) |

Request-level validation happens before the agent runs: `message.trim()` empty → 400 with no agent call
and no DeepSeek line. All other outcomes are HTTP 200 with a plain-language `answer` (no 404, ASM-06).

### Koog tool (internal contract)

| Aspect | Contract |
|---|---|
| Name / description | from `tools/find-fueling-tool.json` (file is the source of truth; a missing/invalid resource fails fast at startup) |
| Parameters | exactly one: `orderId` (string, canonical GUID 8-4-4-4-12, case-insensitive, trimmed) |
| Returns | compact structured text (see §Data model) describing the matches, the fields and the related rows |
| Invalid id | throws `InvalidFuelingIdException`; the strategy logs `stage=tool … is_error=true`; no database lookup, no `stage=db` line (FR-04) |
| Stage DB unavailable | throws `StageDatabaseUnavailableException`; `stage=db lookup=unavailable` + `stage=tool … is_error=true` (FR-12) |
| Not found | returns the not-found text as a normal result (`is_error=false`) (ASM-06) |
| Concurrency | the tool is stateless; one lookup per call |
| Multiplicity | one GUID per call; several GUIDs in one question become several tool calls inside the existing 3-round loop (ASM-05) |

### Internal interfaces

```kotlin
interface StageFuelingRepository { fun findByFuelingId(fuelingId: String): FuelingLookupResult }
fun interface FuelingAgent { suspend fun answer(message: String): String }
object FuelingId { fun canonicalize(raw: String): String? }
object FuelingReport { fun render(result: FuelingLookupResult): String }
fun TraceLog.fuelingLookupFound(id: String?, tool: String, tables: List<String>, events: Int, tokens: Int, feedback: Int, capped: Boolean)
fun TraceLog.fuelingLookupNotFound(id: String?, tool: String, tables: List<String>)
fun TraceLog.fuelingLookupUnavailable(id: String?, tool: String, reason: String?)
fun appModules(deepseek: DeepseekConfig, db: DbConfig, weather: WeatherConfig): Module   // unchanged
fun fuelingModule(stageDb: StageDbConfig, apiKeyConfigured: Boolean): Module
```

## Data model

### Stage schema (read-only, `fueling` database)

| Table | Role in this feature | Key columns read |
|---|---|---|
| `fuelings` (partitioned, ~47K) | source; read through the parent (all 24 partitions) | `fueling_id` (GUID), `vendor_fueling_order_id`, `user_id`, `status`, `amount`, `actual_amount`, `discount_fuel_price`, `vendor_fuel_price`, `fuel_type`, `gas_station_id`, `gas_pump_id`, `refueling_gun_id`, `fuel_reservation_key`, `fueling_type`, `fueling_payment_type`, `failed_reason`, `fueled_orders` (jsonb), `extra` (jsonb), `created_at`, `updated_at`, `finished_at`, `vendor_transaction_date` |
| `fuelings_archive` (~124K) | second source of the same shape | same columns |
| `fuelings_drop` (~87K) | third source of the same shape | same columns |
| `partner_fueling_events` (~67K) | related by `fueling_id` | `event_id`, `fueling_id`, `partner_id`, `event_name`, `delivery_status`, `data` (jsonb), `created_at`, `updated_at` |
| `fueling_feedback` (0 rows) | related by `fueling_id` | `fueling_feedback_id`, `user_id`, `fueling_id`, `reason_id`, `reason_message`, `requested_at` |
| `belka_tokens` (57 rows) | related by `fueling_id` | `token`, `fueling_id`, `created_at`, `error_type` |

A GUID may live in any of the three main tables; every match is returned and labelled (ASM-04). Related
rows are keyed by `fueling_id` and are fetched once per lookup, shared by all matches. No writes, no
migrations, no DDL — the feature has no persisted state of its own (FR-10, NFR-03).

### Tool DTOs (`FuelingRecord` and related)

`FuelingRecord` (all `String?` unless noted): `fuelingId: String`, `vendorFuelingOrderId`, `userId`,
`status`, `amount: BigDecimal?`, `actualAmount: BigDecimal?`, `discountFuelPrice: BigDecimal?`,
`vendorFuelPrice: BigDecimal?`, `fuelType`, `gasStationId`, `gasPumpId`, `refuelingGunId`,
`fuelReservationKey`, `fuelingType`, `fuelingPaymentType`, `failedReason`, `fueledOrders` (jsonb text),
`extra` (jsonb text), `createdAt: Long?`, `updatedAt: Long?`, `finishedAt: Long?`,
`vendorTransactionDate: Long?`.

`PartnerFuelingEvent`: `eventId`, `fuelingId`, `partnerId`, `eventName`, `deliveryStatus`, `data`
(jsonb text), `createdAt: Long?`, `updatedAt: Long?`.
`FuelingFeedback`: `fuelingFeedbackId`, `userId`, `fuelingId`, `reasonId`, `reasonMessage`,
`requestedAt: Long?`.
`BelkaToken`: `token`, `fuelingId`, `createdAt: Long?`, `errorType`.

Reading rules: text/numeric id columns via `getString`; amounts and prices via `getBigDecimal` (rendered
`toPlainString()`); jsonb columns via `getString` (keeps the JSON text; truncated at render time);
epoch columns via `getLong` + `wasNull()`. A `SQLException` anywhere (including a schema surprise such as
a non-numeric timestamp column) becomes `DatabaseUnavailableException` → the graceful unavailable path.

### Timestamp rendering (ASM-08 → decision)

* Reference zone: **UTC**, rendered as `yyyy-MM-dd HH:mm:ss UTC` (e.g. `2026-09-21 14:32:11 UTC`).
  A single constant (`FuelingReport.UTC`) makes it the only place to change if Moscow time is ever
  requested.
* Plausibility window: `2000-01-01T00:00:00Z` … `2100-01-01T00:00:00Z` (946 684 800 000 … 4 102 444 800 000 ms).
  A numeric value inside the window is converted; outside it the raw number is printed (`<value> (raw)`)
  so no 13-digit value can leak into an answer and no crash can occur; `null` prints `-` (ASM-08, NFR-08).
* Fields converted: fueling `created_at`, `updated_at`, `finished_at`, `vendor_transaction_date`;
  partner-event `created_at`, `updated_at`; feedback `requested_at`; belka `created_at`. Raw JSON
  (`fueled_orders`, `extra`, event `data`) is passed through, capped at `MAX_JSONB_CHARS = 2000` per
  field with the marker `…[truncated, N chars total]`.

### Tool output text (what DeepSeek receives)

Decision: **compact structured text**, not JSON (see §Decisions D-04).

Found (one block per match, in the query order `fuelings`, `fuelings_archive`, `fuelings_drop`):

```
НАЙДЕНО: 2 (fuelings, fuelings_archive)
record[1] table=fuelings
  fueling_id=5e12bef2-2f78-48f0-aab5-ccb6bfeb8469
  vendor_fueling_order_id=…
  user_id=…
  status=SUCCESS
  amount=45.00
  actual_amount=45.00
  discount_fuel_price=1.50
  vendor_fuel_price=44.90
  fuel_type=AI-95
  gas_station_id=21
  gas_pump_id=12
  refueling_gun_id=3
  fuel_reservation_key=…
  fueling_type=…
  fueling_payment_type=…
  failed_reason=-
  fueled_orders={"orders":[…]}
  extra={…}
  created_at=2026-09-21 14:32:11 UTC
  updated_at=2026-09-21 14:32:41 UTC
  finished_at=2026-09-21 14:32:41 UTC
  vendor_transaction_date=2026-09-21 14:32:05 UTC
events: total=2 shown=2
  event[1] event_id=… partner_id=… event_name=FUELING_DELIVERED delivery_status=DELIVERED created_at=2026-09-21 14:33:00 UTC updated_at=… data={…}
  event[2] …
feedback: total=0 shown=0
belka_tokens: total=1 shown=1
  token[1] token=… error_type=- created_at=2026-09-21 14:32:12 UTC
```

Not found:

```
НЕ НАЙДЕНО: заказ 00000000-0000-4000-8000-000000000000 отсутствует в fuelings, fuelings_archive, fuelings_drop (stage, база fueling).
```

Related rows are ordered newest-first (`ORDER BY <time> DESC NULLS LAST`), the first
`MAX_RENDERED_RELATED_ROWS = 50` per table are rendered, and `total=`/`shown=` always carry the real
counts (ASM-09). Every documented column is always present (a missing value prints `-`), so no field can
be silently dropped (FR-07). No masking (ASM-13).

## Key flows

### F-1 Success — the order is in `fuelings_archive`

1. Postman sends `POST /fueling` with the example question; the route calls `beginTrace(body)` →
   `stage=inbound` with the same `req=` id.
2. `message.trim()` is non-empty; `withContext(trace) { agent.answer(message) }` runs the Koog agent.
3. Round 1: `LoggingPromptExecutor` logs `stage=deepseek-request` (`model=deepseek-flash`,
   `tools_count=1`, the `find_fueling` definition from the JSON resource) and the model answers with a
   tool call `{"orderId":"5e12bef2-…"}` → `stage=deepseek-response`.
4. The strategy executes the tool: `FuelingId.canonicalize` trims and lowercases the GUID; the repository
   opens one connection (timeouts + prepared statements) and runs the three main `SELECT`s, finds one row
   in `fuelings_archive`, then the three related `SELECT`s; the tool logs
   `stage=db tool=find_fueling lookup=found records=1 tables=fuelings_archive events=2 tokens=1
   feedback=0` and returns the rendered text.
5. The strategy logs `stage=tool … is_error=false result="НАЙДЕНО: 1 (fuelings_archive) …"` and sends the
   result back; round 2 produces the Russian one-line summary (`stage=deepseek-request/response` again,
   the second request carrying the tool message).
6. The route responds 200 `{"message":…,"answer":"…"}` and logs `stage=outbound status=200`.

Edge cases in the same flow: the same GUID in two tables → two `record[n]` blocks and
`tables=fuelings,fuelings_archive`; a record with no related rows → `total=0 shown=0` blocks; a huge
`extra`/`fueled_orders` → capped with the truncation marker; the `stage=tool` log line itself is capped
at 4096 chars as today.

### F-2 Not found

Steps 1–4 as above; all three tables return no row, related queries are skipped, the tool logs
`stage=db tool=find_fueling lookup=not_found records=0` and returns the not-found text as a **normal**
result. The model answers "no data for this id in the stage database" (HTTP 200, no 404 — ASM-06).

### F-3 Malformed / absent id

`{"message":"Найди заказ 12345"}` → the model calls the tool with `orderId="12345"` (it cannot know the
format) → `FuelingId.canonicalize` returns null → `InvalidFuelingIdException`, **no** repository call and
**no** `stage=db` line → `stage=tool … is_error=true result="…не является корректным GUID…"` → the model
answers in plain language that the id is not a valid GUID. If the question contains no id at all
(`{"message":"Найди данные по проливу"}`) the system prompt keeps the model from calling the tool and it
asks for the GUID. In both cases HTTP 200; a blank `message` is rejected earlier with 400 and never
reaches the agent.

### F-4 Stage database down (unreachable host, auth failure, timeout)

The very first `DriverManager.getConnection` blocks at most `stageDb.timeoutSeconds` (default 10 s) and
throws `SQLException` → `DatabaseUnavailableException`. The tool logs
`stage=db tool=find_fueling lookup=unavailable reason="…"` and throws
`StageDatabaseUnavailableException` → `stage=tool … is_error=true` → the model answers "данные stage
временно недоступны, повторите позже". The route still returns 200 within the 30 s budget (NFR-04); the
server stays up and `GET /time`, `POST /weather` are unaffected. If the exception ever escaped the tool,
the existing StatusPages mapping of `DatabaseUnavailableException` still answers 503 instead of 500
(safety net).

### F-5 No DeepSeek API key

`apiKeyConfigured = false` → the `FuelingAgent` binding is the lambda that throws
`WeatherUnavailableException("DeepSeek API key is not configured")` → the existing StatusPages handler
answers 503 with that message; no `stage=deepseek-*`, no `stage=tool`, no `stage=db` lines. The app
starts normally (FR-11/FR-12), and the weather behaviour is identical.

## Decisions and alternatives

| # | Decision | Alternatives considered | Rationale |
|---|---|---|---|
| D-01 | **Dedicated `FuelingAgent` + `find_fueling`-only registry; `POST /fueling` (ASM-01)** | (a) add the tool to the existing weather registry and keep one agent; (b) one agent with two routes and a prompt switch | (a) breaks frozen tests (`TraceChainIntegrationTest` asserts `tools_count=1` and the `get_weather` definition for `/weather` and `/time`) and sends the fueling tool into every weather/`/time` request (NFR-06/NFR-07); (b) mixes two domains in one prompt (FR-09 wants unambiguous routing). A sibling agent mirrors the existing pattern 1:1 and keeps the weather slice untouched (FR-16) |
| D-02 | **Separate Koin module `fuelingModule(stageDb, apiKeyConfigured)`, composed in `Application.module`; named qualifiers for the fueling spec/registry** | (a) add a 4th parameter to `appModules`; (b) unnamed second `ToolSpec`/`ToolRegistry` bindings | (a) either breaks the frozen `AppModulesTest` call site or forces that test to be edited (NFR-05 allows edits only for the model default), and an eager second spec load would break its "loaded once" assertion; (b) duplicate unnamed bindings of the same type make `get<ToolSpec>()`/`get<ToolRegistry>()` ambiguous and would feed the wrong descriptors to `/time`. Named qualifiers are standard Koin and leave every existing binding untouched |
| D-03 | **Invalid id → throw `InvalidFuelingIdException`; DB down → throw `StageDatabaseUnavailableException`; not found → normal string result** | (a) return a plain string for all three (no error flag); (b) use Koog's `ToolException.ValidationFailure`; (c) extend `Tool<TArgs, ToolResult>` | FR-04 explicitly requires `is_error=true` for an invalid id; the repo's own test (`KoogWeatherAgentTest`) verifies that a throwing `SimpleTool` becomes an error tool result and that the loop still answers. (a) contradicts FR-04; (b) depends on a Koog API whose 1.2.0 availability and error propagation are unverified and cannot carry a cause for the DB case; (c) deviates from the shipped `SimpleTool` pattern for no gain |
| D-04 | **Tool result = compact structured text with English field labels and Russian prose lines**, not JSON | (a) JSON via kotlinx.serialization; (b) prose only | (a) is machine-friendly but token-heavy, double-escapes jsonb inside JSON and invites the model to echo JSON (FR-08 forbids it); (b) loses the field/table structure the model must map. Text with `key=value` lines keeps the DB vocabulary, is cheap, readable and testable |
| D-05 | **Timestamps in UTC, `yyyy-MM-dd HH:mm:ss UTC` (ASM-08)** | (a) `Europe/Moscow`; (b) machine-local zone; (c) configurable zone | UTC is the analyst's assumption, is machine-independent and unambiguous with the explicit suffix; (a) hard-codes a business zone, (b) makes results non-reproducible across machines, (c) is scope not asked for. One constant to change if the user asks |
| D-06 | **Plausibility window instead of unconditional conversion** | convert every numeric value to a date | ASM-08 requires that "any numeric field that turns out not to be epoch millis must still be rendered human-readably"; a range check turns a schema surprise into readable output instead of a wrong date |
| D-07 | **No SQL `LIMIT` on related queries; cap only what is rendered (50 rows/table), always report real totals** | (a) SQL `LIMIT 50` + `count(*) OVER ()`; (b) no cap at all | (b) risks a 50 000-row tool message (context blow-up); (a) saves transfer but adds window-function SQL that no offline test can exercise. Related rows per single fueling are few; the render cap satisfies ASM-09 ("the total count must be stated") with the simplest auditable SQL. Revisit if real data shows large event sets |
| D-08 | **Read-only by construction: `SELECT`-only repository + a static guard test** | `connection.isReadOnly = true` as defence in depth | `setReadOnly` is a client-side flag in pgjdbc (no guarantee the server enforces it) and cannot be verified by the offline suite; FR-10's acceptance criterion is exactly a text search over production sources, which the guard test performs (NFR-03) |
| D-09 | **`WHERE fueling_id = ?` with the canonical lowercase GUID (index-friendly)** | `WHERE lower(fueling_id) = ?` (case-insensitive on both sides) | The analyst verified exactly this predicate against the stage; all observed `fueling_id` values are lowercase canonical. The alternative defeats a possible index on `fueling_id` and removes the signal that a wrongly-cased stored value exists. Recorded as an assumption with a smoke check and a one-line fallback (see R-3) |
| D-10 | **Timeouts: `stageDb.timeoutSeconds` (default 10, ASM-07) applied as connect/socket/login timeout and `statement.queryTimeout`** | (a) `DriverManager.setLoginTimeout` (JVM-global); (b) no timeout | (a) would also affect the local weather database connection; (b) risks a hanging request (NFR-04). Per-connection properties are local to the stage connection |
| D-11 | **Reuse `DatabaseUnavailableException` and `WeatherUnavailableException`; no new StatusPages handler** | new `StageUnavailableException` + `FuelingUnavailableException` + two handlers | The types already mean "database unavailable → 503" and "LLM unavailable → 503"; duplicating them adds handlers with no behavioural difference. `WeatherUnavailableException`'s name is weather-flavoured but its contract is app-wide ("the LLM agent is unavailable"); renaming it would touch frozen tests |
| D-12 | **`FuelingReport` holds all rendering; the JDBC layer returns raw DTOs (epoch values as `Long?`)** | format timestamps inside the repository (reusing `db/DATA_FORMAT`) | Keeps conversion pure, clock-free and unit-testable offline (`FuelingReportTest`), and keeps JDBC code free of presentation concerns; the existing `DATA_FORMAT` is package-internal to the weather repository |
| D-13 | **Duplicate the ~25-line strategy loop in `KoogFuelingAgent`** | extract a shared strategy helper used by both agents | Extraction touches the frozen `KoogWeatherAgent`, whose test asserts the non-convergence warning comes from the `com.aiturbo.weather.KoogWeatherAgent` logger; the duplication is small, local, and mirrors the repo's per-slice structure (FR-16, NFR-05). If the planner prefers extraction, the logger name must be preserved |
| D-14 | **Model default `deepseek-flash` in `application.conf` and `DeepseekConfig.DEFAULT_MODEL`; every LLM path inherits it (weather agent, `/time` resolver, fueling agent)** | per-agent model overrides | FR-13/NFR-07 demand the cheapest tier everywhere as the default with a single env override (`DEEPSEEK_MODEL`); no test asserts the old default, so nothing else changes. A per-agent model would add knobs nobody asked for |
| D-15 | **`stage=db` line emitted by the tool, not the repository** | repository-side logging | The tool owns the tool name and the outcome vocabulary; the repository has no trace context (like the weather repository, which never logs). Keeps `TraceLog` calls in one place per flow and the correlation id intact |

## Non-functional coverage

### FR traceability

| FR | Design element |
|---|---|
| FR-01 | §API `POST /fueling`; `plugins/FuelingRouting.kt` (`FuelingRequest`/`FuelingResponse`, 400 on blank `message`, JSON always) → `FuelingRoutesTest` |
| FR-02 | `FindFuelingTool` + `tools/find-fueling-tool.json` + `ToolSpecLoader.FUELING_RESOURCE_PATH` + `FUELING_TOOL_REGISTRY`; chain test asserts `tools_count=1`, `"name":"find_fueling"`, the resource's description, and one `stage=tool` line with the GUID |
| FR-03 | `FindFuelingArgs.orderId` + `FuelingId.canonicalize` + the resource's `parameters` block → `FuelingIdTest` (case, whitespace, rejects) |
| FR-04 | D-03 invalid path: no repository call, no `stage=db`, `is_error=true`, plain-language answer; system prompt handles "no id" → `FindFuelingToolTest`, `FuelingChainIntegrationTest` |
| FR-05 | `JdbcStageFuelingRepository` queries all three tables through the parent; `FuelingMatch.source` labels each match → `FindFuelingToolTest`, `FuelingReportTest` |
| FR-06 | the three related queries by `fueling_id`; empty lists are normal → `FakeStageFuelingRepository` fixtures, `FindFuelingToolTest` |
| FR-07 | `FuelingRecord` + related DTOs carrying every documented column; `FuelingReport` renders all of them with converted timestamps → `FuelingReportTest` |
| FR-08 | tool returns text → the strategy's second round-trip; `FuelingChainIntegrationTest` asserts the second `deepseek-request` carries the tool result and the second `deepseek-response` is the summary |
| FR-09 | `SYSTEM_PROMPT` in `fueling/FuelingAgent.kt`; agent test asserts the system message is present in the first prompt |
| FR-10 | `SELECT`-only repository + `StageReadOnlyGuardTest` (D-08) |
| FR-11 | `StageDbConfig` + `resolveStageDbPassword` (no default), empty `stageDb.password` in tracked config, `.env` git-ignored → `StageDbConfigTest` |
| FR-12 | per-call connection, 10 s timeouts, `DatabaseUnavailableException` → `StageDatabaseUnavailableException` → plain-language answer; nothing connects at startup → `FindFuelingToolTest`, `FuelingModulesTest`, F-4 |
| FR-13 | `DeepseekConfig.DEFAULT_MODEL` + `application.conf` (D-14) → `DeepseekConfigTest` addition |
| FR-14 | the three `TraceLog.fuelingLookup*` functions + the unchanged `stage=tool` line → `FuelingChainIntegrationTest` (order, one id, no secrets) |
| FR-15 | README section "Fueling order lookup" with the input/output contract and the Postman example (mirrors `01-requirements.md`) |
| FR-16 | no change to weather bindings, routes, DTOs or prompts; only the model default (D-14) → existing suite green |
| FR-17 | this document + `03-plan.md`; the implementation record follows the plan |

### NFR coverage

| NFR | How it is met |
|---|---|
| NFR-01 Observability | The 8-line chain of §Logging design: `inbound` → 2 × `deepseek-*` → `db` (lookup, tables, counts) → `tool` (args/result/is_error) → 2 × `deepseek-*` → `outbound`, one `req=` id; asserted by `FuelingChainIntegrationTest` |
| NFR-02 Security | Password only via `STAGE_DB_PASSWORD` (env/`.env`); `StageDbConfig` is never logged, the repository never logs, `TraceLog` receives only the failure message; the chain test seeds a password fixture and asserts 0 occurrences in all lines; `.env` stays in `.gitignore` |
| NFR-03 Data safety | `SELECT`-only repository (D-08), prepared statements, table names from an enum; `StageReadOnlyGuardTest` scans the stage sources for write keywords; the smoke checklist checks row counts before/after a manual lookup |
| NFR-04 Performance / resilience | One connection per lookup, at most 6 short queries, 10 s connect/socket/login + query timeout (D-10), no retries; success budget 5 s and the unreachable-DB budget 30 s are checked in the manual smoke run (timings between the `tool`/`db` lines and the request end) |
| NFR-05 Testability / offline | Every new test uses `FakeStageFuelingRepository`, a scripted `PromptExecutor` and `LogCapture`; no test resolves `StageDbConfig.from`, opens a socket or reads `.env`; the 123 existing tests are untouched (only the model default changes in production config, and no test asserted it) |
| NFR-06 Compatibility | No new dependency; stack unchanged; `fuelingModule` is additive; existing routes/DTOs/status codes untouched (`GET /` 200, `GET /time` 200/400/404, `POST /weather` 200/400/503, `GET /weather/history` 200/400) |
| NFR-07 Cost | `deepseek-flash` is the single default for the shared `LLModel` binding (all three LLM paths); no fallback model anywhere; `DEEPSEEK_MODEL` remains the only override |
| NFR-08 Robustness | Types cover malformed ids, not-found, unavailable/timeouts; render caps bound the oversized-jsonb/large-event cases; a blank `message` stays a 400; StatusPages keeps 503/500 as the last resort; nothing connects at startup, so `./gradlew run` works with the stage DB absent |
| NFR-09 Process / documentation | `01-requirements.md` → `02-design.md` (this file) → `03-plan.md`; README updated (API table, fueling section + Postman example, trace examples, config keys, test list, feature-docs link) so nothing contradicts the new behaviour |

### New tests (all offline)

| Test class | Coverage |
|---|---|
| `StageDbConfigTest` | defaults (host/port/name/user/timeout), `stageDb.*` values, `STAGE_DB_*` env precedence, `.env` password, blank password when nothing is configured, `jdbcUrl` |
| `FuelingIdTest` | canonical lower/upper accepted and lowercased, whitespace trimmed, rejects compact 32-hex, braces, `urn:uuid:`, empty, wrong group lengths, non-hex |
| `FuelingReportTest` | found/not-found rendering, all columns present, epoch → `… UTC`, out-of-range value → `<n> (raw)`, null → `-`, jsonb cap marker, related-row cap with real totals, multiple matches naming their tables |
| `FindFuelingToolTest` | with `FakeStageFuelingRepository`: success result + one `stage=db lookup=found …` line; archive/drop source naming; not found (no related queries, `lookup=not_found`); invalid id → `InvalidFuelingIdException`, repository not called, **no** `stage=db` line; DB down → `StageDatabaseUnavailableException`, `lookup=unavailable` line; descriptor name/description come from the shipped resource |
| `FuelingRoutesTest` | 200 + `message`/`answer` + inbound/outbound with one id + agent runs in `withContext(trace)`; blank `message` → 400 with no agent call; malformed body → 400 without an inbound body; blank key → 503 with no `stage=deepseek` lines |
| `KoogFuelingAgentTest` | scripted executor: tool call → text, one `stage=tool` line (`tool=find_fueling`, GUID argument, `is_error=false`); correlation id from the context; failing tool → `is_error=true`; non-convergence warning; the registry descriptors are always non-empty; the system prompt is present |
| `FuelingModulesTest` | `appModules(...) + fuelingModule(...)` composes exactly like production: graph resolves offline, `FuelingAgent` is a `KoogFuelingAgent`, the fueling spec is loaded once from the new resource, blank key → the unavailable lambda, `ToolRegistry` (weather, unnamed) still contains only `get_weather` |
| `FuelingChainIntegrationTest` | the full 8-line chain through the production-shaped graph with fakes at the edges (scripted executor + `LoggingPromptExecutor` + fake repository + fake LLM-free agent path): stage order, one `req=` id, `tools_count=1` with the `find_fueling` definition, `stage=db` counts, invalid-id flow without a `db` line, truncation bounded, no secrets (API-key and stage-password fixtures) |
| `StageReadOnlyGuardTest` | static scan of `src/main/kotlin` for the stage sources: no `INSERT`/`UPDATE`/`DELETE`/`DROP`/`ALTER`/`CREATE`/`TRUNCATE`, SQL constants use `?` placeholders and no string interpolation (FR-10, NFR-03) |

## Open questions and risks for the planner

| # | Question / risk | Default assumption taken here | Verification / fallback |
|---|---|---|---|
| R-1 | **Does `deepseek-flash` support tool calling through the Koog OpenAI client?** (ASM-12, the load-bearing assumption of the whole flow) | Yes | First smoke step after wiring: one `POST /fueling` must show a `tool_calls=[{"name":"find_fueling",…}]` line. If not, this is a blocker: report it (the only named alternative is keeping a more expensive model); the fallback needs no code change (`DEEPSEEK_MODEL=deepseek-chat`) |
| R-2 | **What exactly does Koog 1.2.0 put into the tool result when `execute` throws?** FR-04's answer quality depends on the model seeing a readable message | The exception message reaches the model (the repo's test proves `is_error=true` and that the loop continues) | Inspect the second `deepseek-request` of the manual run; if the text is opaque, keep the invalid-id throw (FR-04 requires the flag) but return the *unavailable* case as a normal string instead of throwing (one-line change in `FindFuelingTool`) |
| R-3 | **Are all stage `fueling_id` values lowercase canonical GUIDs?** An uppercase stored value would make the exact-match lookup report "not found" | Yes (verified examples are lowercase); `WHERE fueling_id = ?` with the lowercase canonical form (D-09) | Smoke SQL check `SELECT count(*) FROM fuelings WHERE fueling_id <> lower(fueling_id)` (also for archive/drop); fallback `WHERE lower(fueling_id) = ?` |
| R-4 | **Stage database name `fueling`** (ASM-10, unverified) | `fueling` (same as the user) | Confirm in the smoke run; `STAGE_DB_NAME` is the one-key override if it differs |
| R-5 | **Log order `db` → `tool`** while the requirements' prose lists `tool` → `db` | Keep the shipped order (identical to the verified weather chain); both lines are present with one id | If the requirements owner insists on the literal order, the `stage=tool` line would have to be emitted by the tool itself (it does not know `is_error`/`result` as the strategy renders them) — raise before implementing |
| R-6 | **Timeout value 10 s** (ASM-07 "proposed, confirm") | 10 s via `stageDb.timeoutSeconds` | The key makes it tunable without a code change; the smoke run measures the unreachable-DB response (< 30 s) |
| R-7 | **`.env` must gain `STAGE_DB_PASSWORD`** — a manual step the implementation cannot do (git-ignored secret) | Documented in the README; without it the tool degrades gracefully (FR-11/FR-12 as designed) | First manual run: a successful lookup proves it |
| R-8 | `vendor_transaction_date` / other numeric columns may not really be epoch millis | Range-check fallback prints the raw number with `(raw)` (D-06) | Manual lookup against a real row: every timestamp must be readable and plausible |
| R-9 | A question with more than 3 GUIDs exceeds `maxToolRounds` | 3 rounds (mirrors the weather agent); ASM-05 only requires "one call per GUID" | Non-convergence already logs a warning and the agent answers with what it has; raise if the operator needs more |
| R-10 | Related-row volumes for a single GUID are unknown (67K events overall; per-GUID density unverified) | All related rows are fetched, the first 50 per table are rendered, totals always stated (D-07) | Manual lookup of a busy order; if needed, add a SQL `LIMIT` + `count(*) OVER ()` later |
| R-11 | `Application.module`'s composed graph (both modules) is not exercised by any existing test | `FuelingModulesTest` composes `appModules(...) + fuelingModule(...)` exactly like production and asserts the graph resolves offline and the fueling spec loads once | If the planner wants route-level proof, add a composed-graph test with a blank key asserting 503 for `POST /fueling` (offline) |
| R-12 | FR-15 asks for the Postman example in the documentation; the README already carries a "Testing with Postman" section | Add a fueling subsection there (method, URL, headers, body, expected response, other cases) rather than a separate document | Reviewed against `01-requirements.md`'s table for verbatim consistency |

---

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
