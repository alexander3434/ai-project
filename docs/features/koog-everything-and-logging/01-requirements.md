# Koog-Everything and Logging — Requirements

This feature makes Koog the single path for every DeepSeek call (today only the `POST /weather`
agent uses Koog; the `/time` LLM time-zone resolver still calls DeepSeek over raw Ktor HTTP) and
makes the whole request chain visible in the logs: Postman → backend → DeepSeek request → DeepSeek
response → tool call → response sent to Postman. It adds a JSON resource file describing the
`get_weather` tool, guarantees that outbound DeepSeek requests never carry an empty `tools` array
and always carry the `get_weather` definition when tools are used, and requires the work to be
specified through the feature-design pipeline (requirements → design → plan). It also provides a
ready-to-use Postman recipe that writes a row into the PostgreSQL `users` table and shows how to
read the records back. The current behavior of all endpoints, error codes, and the offline test
suite must be preserved.

## Context and goals

**Why.** The user (the developer/operator running the service locally) needs two things:
one uniform LLM integration path for all DeepSeek traffic, and full traceability of every request
so that a single glance at the console log shows what came in, what was sent to DeepSeek, what came
back, what tool was called, and what was returned to the client. They also want evidence that the
`tools` field is actually populated (not `[]`), a visible JSON description of the tool in the
resources folder, and a copy-paste Postman example that records a row in the database.

**Current state (grounded in the codebase):**

- `POST /weather` already runs a Koog `AIAgent` with a DeepSeek executor (Koog OpenAI-compatible
  client) and the `get_weather` tool; the tool fetches Open-Meteo weather, computes local time, and
  inserts a row into the PostgreSQL `users` table (`id`, `data` unique, `time`, `created_at`).
- `GET /time?location=` resolves through `DirectZoneResolver` → `BuiltinTimeZoneResolver` (offline
  city map) → `CachingTimeZoneResolver(LlmTimeZoneResolver)`. `LlmTimeZoneResolver` is the **only
  remaining raw DeepSeek caller**: it POSTs to `{baseUrl}/chat/completions` with a Bearer key.
- `GET /weather/history?limit=` (1–100, default 20) returns recent `users` rows.
- Logging: Ktor `CallLogging` installed at INFO (request line only, no bodies); logback writes to
  the console (STDOUT) at INFO with a plain pattern.
- `src/main/resources/` exists and contains `application.conf` and `logback.xml`.
- Routes and error contract: `GET /` (200), `GET /time` (200/400/404), `POST /weather` (200/400/503),
  `GET /weather/history` (200/400); DB unavailable → 503, unhandled → 500.
- Tests: 10 test classes (52 tests) run without a real database or a live LLM, using fakes,
  `MockEngine` and a frozen clock.

**Goals (what success looks like).**

1. One requirement is trivially checkable after this feature: no direct DeepSeek HTTP call exists
   anywhere in production code — all DeepSeek traffic goes through Koog.
2. Sending one weather question from Postman produces, in the console log, the five-stage chain
   (inbound request → DeepSeek request → DeepSeek response → tool call → outgoing response) with the
   request, response, tool name/arguments and final answer recognizable, and with no secrets.
3. The outbound request to DeepSeek visibly contains a non-empty `tools` array including
   `get_weather`; an empty `"tools": []` is never sent.
4. A JSON file in the resources folder describes the `get_weather` tool.
5. The feature is specified through the feature-design pipeline, and its documentation contains a
   verbatim Postman recipe for writing to the database and viewing history.

**Who it is for:** the developer running `./gradlew run` locally and reading the console, and anyone
picking up the repo who needs a repeatable manual smoke test.

## Functional requirements

| ID | Requirement | Priority | Acceptance criterion (sketch) |
|---|---|---|---|
| FR-01 | Every outbound request to the DeepSeek API shall be issued through Koog. No production component may call DeepSeek over direct/raw HTTP. This covers both the `/weather` agent and the `/time` LLM time-zone resolution. | Must | Static check: no production code path performs a direct HTTP call to a DeepSeek endpoint (the raw `chat/completions` invocation disappears outside Koog-backed components). Behavior check: `GET /time?location=<place not in the built-in map>` still resolves via the LLM with the same outcomes as today (200 with a time zone; 404 when unresolved; no call when the key is blank), and the call appears in the logs per FR-04/FR-05. |
| FR-02 | An outbound DeepSeek request shall never contain an empty `tools` array; requests from flows that use tools shall include the full non-empty tool definitions, including `get_weather`. | Must | During one weather question, the logged outbound request contains a `tools` array with at least one entry whose function name is `get_weather`. Across a full run, zero logged outbound request bodies contain an empty tools array. A time-resolution request contains no `tools` field at all (see ASM-04). |
| FR-03 | Every inbound HTTP request shall be logged, including method, path, query parameters, client address, and the request body when one is present. | Must | A `POST /weather` sent from Postman produces a log entry containing the received JSON body (`{"message": "..."}`); `GET /time` and `GET /weather/history` are logged too, including 4xx/5xx outcomes (ASM-07). |
| FR-04 | Every outbound DeepSeek request made through Koog shall be logged, including the endpoint/model, the messages, and the `tools` array when tools are used. | Must | For one weather `POST` the log contains an entry showing the model (`deepseek-chat` or the configured value), the messages sent, and the non-empty `tools` array containing `get_weather`. For a time-resolution call, the request messages are logged (no tools). |
| FR-05 | Every DeepSeek response shall be logged, including status and content, including tool calls the model requested. | Must | Within the same request, the log shows the DeepSeek response containing the `get_weather` tool call. Failures (HTTP error, malformed reply, timeout) are logged with the status/error, and the existing graceful degradation (null zone → 404; weather agent unavailable → 503) still applies. |
| FR-06 | Every tool invocation shall be logged with the tool name, its arguments, and its result summary (including whether the database insert succeeded). | Must | The log shows `get_weather` invoked with its `location` argument and the summary it returned. When the database is down, the answer is still produced and the logging shows the insert failure (existing behavior preserved). |
| FR-07 | Every HTTP response sent to the client shall be logged with status and body. | Must | The log contains the final JSON answer returned to Postman for `POST /weather`; error responses (400/404/500/503) are logged with their status and body as well. |
| FR-08 | A JSON file describing the `get_weather` tool (tool name, human-readable description, and its input parameters) shall exist under `src/main/resources/`; if the resources folder does not exist, it shall be created. | Must | A JSON file exists under `src/main/resources/` (the folder exists today), parses as valid JSON, and contains the tool name `get_weather`, a non-empty description, and the `location` parameter with its description. |
| FR-09 | The tool description exposed to DeepSeek shall match the JSON resource file (the file is the single source of truth for the tool description). | Should | The description sent in the outbound `tools` entry for `get_weather` is identical to the description field in the resource file; editing only the file changes what the model receives. (Risky assumption — see ASM-02.) |
| FR-10 | The feature documentation shall include a ready-to-use Postman example: exact method, URL (`http://localhost:8080/...`), headers and body that triggers a database write, plus how to view the stored records. | Must | The example in this document (and mirrored in the README) used verbatim against a running local server with the database up returns 200, inserts a new `users` row, and that row is returned by `GET /weather/history`. See “Postman examples” below. |
| FR-11 | The README shall be updated to describe the Koog-only DeepSeek path, the log chain, and the Postman recipe; stale statements (e.g. “DeepSeek LLM over HTTP”) shall be removed. | Should | Review of README against the running behavior finds no contradiction; the documented flow matches the shipped code and the three feature-design documents. |

### Postman examples (manual verification)

Prerequisites (one-time): the server is running on `http://localhost:8080` (`./gradlew run`), a
`DEEPSEEK_API_KEY` is available (environment variable or git-ignored `.env`), and the local
PostgreSQL container from the README (`TimeAndData`, host port 5439, database `mydb2`) has the
`users` table and `users_id_seq` created. If the database is down, the answer is still returned but
no row is written; if the key is missing, the endpoint returns 503.

**Request 1 — write a row to the database**

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `http://localhost:8080/weather` |
| Headers | `Content-Type: application/json` |
| Body | raw JSON: `{"message": "Какая сейчас погода в Москве?"}` |

Steps: create the request in Postman, choose **Body → raw → JSON**, paste the body, press **Send**.
Use a weather question (the agent is instructed to always call `get_weather` for weather
questions); a non-empty `message` is required, otherwise the server answers 400.

Expected response: `200 OK`

```json
{
    "message": "Какая сейчас погода в Москве?",
    "answer": "Сейчас в Москве +15.4°C, облачно, влажность 66%, часовой пояс Europe/Moscow, местное время 2026-09-14 19:45:03 (Monday)"
}
```

Side effect: one new row in `users` — `id` incremented by the sequence, `data` = local date and time
in the requested region (unique), `time` = local time, `created_at` = the moment of the request.

Optional SQL check (same machine as the Docker container):

```bash
docker exec -it TimeAndData psql -U myuser -d mydb2 -c 'SELECT * FROM users ORDER BY id DESC LIMIT 5;'
```

**Request 2 — view the stored records**

| Field | Value |
|---|---|
| Method | `GET` |
| URL | `http://localhost:8080/weather/history?limit=5` |
| Headers | none |
| Body | none |

Expected response: `200 OK`

```json
{
    "records": [
        {
            "id": 7,
            "data": "2026-09-14 19:45:03",
            "time": "19:45:03",
            "createdAt": "2026-09-14 19:45:03"
        }
    ]
}
```

`limit` must be an integer between 1 and 100 (default 20); anything else returns 400.

**Request 3 (context) — time endpoint**: `GET http://localhost:8080/time?location=Moscow`, no
headers or body, returns the live date/time JSON for the location.

**What the log should show when Request 1 is sent** (illustrative wording/format only — the design
chooses the exact format, but every element below must be present, in this order, linked by one
correlation identifier; see NFR-01):

```
<id> inbound        : POST /weather from <client> body={"message":"Какая сейчас погода в Москве?"}
<id> -> deepseek req: model=deepseek-chat tools=[get_weather, ...] messages=[...]
<id> <- deepseek res: tool_call get_weather {"location":"Москва"}
<id> tool get_weather: {"location":"Москва"} -> "Москва: +15.4°C, ... (запись сохранена, id=7)"
<id> outbound       : 200 {"message":"Какая сейчас погода в Москве?","answer":"..."}
```

### End-to-end acceptance criteria (walkthrough)

1. Send Request 1 from Postman: HTTP 200, a natural-language answer, and exactly one new `users`
   row; Request 2 returns that row.
2. The console log contains the five-stage chain for that single request, in order, with the
   outbound DeepSeek request showing a non-empty `tools` array containing `get_weather`, and no
   secret values anywhere in the output.
3. `GET /time?location=<place not in the built-in map>` returns a correct time zone and its
   DeepSeek traffic appears in the logs through the Koog path; an unresolvable location still
   returns 404.
4. `./gradlew test` is green with no real database and no live LLM.

## Non-functional requirements

| ID | Category | Measurable target |
|---|---|---|
| NFR-01 | Observability | For one `POST /weather`, the console output (shipped logback configuration, INFO, STDOUT) contains exactly one entry per chain stage — inbound request, outbound DeepSeek request, DeepSeek response, tool call, outbound response — in that order, and all five entries carry the same per-request correlation identifier. No configuration change is required to see the chain. |
| NFR-02 | Security | After a full weather + time run, the configured `DEEPSEEK_API_KEY` value, any `Authorization` header value, and the database password appear 0 times in the logs. |
| NFR-03 | Log hygiene | Any logged body (request, response, LLM prompt/answer) is truncated to at most 4096 characters with an explicit truncation marker; no single log entry carries more than ~4 KB of body content. |
| NFR-04 | Robustness | Logging never changes API behavior: malformed or missing bodies, binary payloads and logging/serialization failures leave status codes exactly as today (400/404/500/503) and never cause a 500 by themselves. |
| NFR-05 | Testability / compatibility | `./gradlew test` passes with no real database and no live LLM; the current 52 tests remain green (adapted where the DeepSeek integration changes, without losing coverage of parsing, failure and key-absent cases); all new coverage is offline (fakes, `MockEngine`, frozen clock). |
| NFR-06 | Backwards compatibility | All existing endpoints and status codes are unchanged: `GET /` 200; `GET /time` 200/400/404; `POST /weather` 200/400/503; `GET /weather/history` 200/400; database down → 503; unhandled → 500; response JSON shapes are unchanged. |
| NFR-07 | Process / documentation | The feature is specified through the feature-design pipeline: `docs/features/koog-everything-and-logging/01-requirements.md`, `02-design.md`, `03-plan.md` exist, are consistent with each other, and are produced in that order before implementation. |
| NFR-08 | Process / team | Each specification stage is produced by its dedicated skill/agent of the feature-design team (requirements → design → plan), not by an ad-hoc single pass; the artifacts identify their stage and implementation follows the plan document. |

## Out of scope

- **City data for “Tula” (or any city/geo JSON).** The translation note maps “tula” to the tool; no
  city dataset is requested. If OQ-01 resolves the other way, this becomes follow-up scope.
- **Routing non-DeepSeek HTTP calls (Open-Meteo) through Koog.** The requirement explicitly names
  DeepSeek requests only.
- **Detailed per-call logging of Open-Meteo HTTP traffic.** The tool-level log (FR-06) records the
  outcome; raw weather-provider traffic was not requested.
- **Database schema or query changes.** The existing `users` table (`id`, `data`, `time`,
  `created_at`) already stores what the request asks for; no new persistence is required.
- **New endpoints, new tools, UI, or authentication.** Not requested.
- **External log aggregation, log file rotation or new appenders.** Console logging is assumed
  sufficient (ASM-03).
- **Changes to prompts, model, answer format, or the resolver chain order/caching.** Behavior is to
  be preserved; no redesign was requested.
- **Deployment/Docker changes and load/performance testing.** No requirement or measurable target
  was stated.

## Open questions

| ID | Question | Why it matters | Default assumption (ASM-xx) |
|---|---|---|---|
| OQ-01 | In “a file in the resources folder with a description of tula in json”, does “tula” mean the tool (`get_weather`) or the city of Tula? | Determines the file's content; the built-in city map has no `tula` entry today, so `/time?location=Tula` currently goes to the LLM resolver. | ASM-01: “tula” means the tool (`get_weather`), per the translation note; the JSON file describes the tool. City data is not delivered unless the user confirms otherwise. |
| OQ-02 | Must the application load the JSON file at runtime (making it the source of truth for the tool description), or is the file informational only? | If loaded, the description sent to the model must mirror the file (FR-09); if not, the file is documentation and FR-09 can be dropped. | ASM-02 (risky): the file is the single source of truth — the `get_weather` description sent to DeepSeek matches the file. This assumption is flagged as risky; confirming it early is recommended. |
| OQ-03 | Which log destination and level are expected — console at INFO with the current logback config, or also a file / different level? | Affects whether the user “sees” the chain with the shipped configuration. | ASM-03: console/STDOUT at INFO using the existing `logback.xml`; no new appenders or files. |
| OQ-04 | Does the non-empty `tools` requirement apply to the `/time` time-resolution DeepSeek requests as well, or only to the weather agent? | Adding tools to a flow that uses none can confuse the model; the “never `[]`” rule applies either way. | ASM-04: the tools requirement applies to tool-using flows (weather); time-resolution requests send no `tools` field; no request ever sends an empty `"tools": []`. |
| OQ-05 | What must be redacted when logging bodies — only the API key, or more? | The outbound request log includes headers; the key must never leak, while the user wants full prompt/answer visibility. | ASM-05: full message/response bodies are logged at INFO; the API key, `Authorization` header and database password are redacted; bodies are truncated per NFR-03. |
| OQ-06 | Is it acceptable to introduce a per-request correlation identifier to tie the five log entries together? | Without a shared identifier, parallel requests make the chain hard to follow. | ASM-06: yes — one correlation id per inbound request, included in every chain entry. |
| OQ-07 | Does “all requests must be logged” cover every endpoint and error outcome (`GET /`, `/time`, `/weather/history`, 4xx/5xx), or only the weather flow? | Determines logging coverage and the size of the change. | ASM-07: yes — every inbound request and every outbound response is logged, including error responses. |

## Glossary

- **Backend (“beck”)** — this Ktor server (`ai-turbo`, `com.aiturbo`), listening on
  `http://localhost:8080` by default.
- **DeepSeek (“deepsik”)** — the external LLM API (`https://api.deepseek.com` by default,
  OpenAI-compatible `chat/completions`), used for time-zone resolution and by the weather agent.
- **Tool (“tula”)** — a Koog-registered function the LLM may invoke; today the only tool is
  `get_weather`.
- **`get_weather`** — the Koog `SimpleTool` that resolves a location to a time zone, fetches current
  weather from Open-Meteo, computes local time, inserts a row into the `users` table, and returns a
  summary the agent turns into a natural-language answer.
- **Koog** — JetBrains' JVM framework for LLM agents (`AIAgent`, `ToolRegistry`, `SimpleTool`); the
  required single access path for all DeepSeek calls.
- **Log chain** — the five ordered log entries the user wants to see: inbound request → DeepSeek
  request → DeepSeek response → tool call → outbound response.
- **DeepSeek request / response** — the outbound `chat/completions` call and the model's reply
  (content and/or tool calls).
- **`users` table** — the PostgreSQL table in database `mydb2` (local Docker container
  `TimeAndData`, host port 5439) holding weather requests: `id` (sequence), `data` (unique local
  date-time), `time` (local time), `created_at` (insert moment).
- **Postman** — the manual HTTP client used in the user's chain (“a request from Postman to the
  backend”).
- **LLM time-zone resolution** — the third step of the `/time` resolver chain
  (`DirectZoneResolver` → `BuiltinTimeZoneResolver` → `CachingTimeZoneResolver(LlmTimeZoneResolver)`);
  today the only raw DeepSeek caller.
- **Feature-design pipeline** — the `/feature-design` skill and its separate agents
  (analyst → architect → planner) producing `01-requirements.md`, `02-design.md`, `03-plan.md`.
- **Correlation id** — the per-request identifier that ties the five chain entries together
  (ASM-06).
- **Offline tests** — the test strategy using fakes, `MockEngine` and a frozen clock; no test may
  require a real database or a live LLM.
