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
