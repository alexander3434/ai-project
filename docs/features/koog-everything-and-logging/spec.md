# Specification: Koog-Everything and Logging

- **Slug:** koog-everything-and-logging
- **Date:** 2026-09-16
- **Team:** feature-design (analyst → architect → planner agents)
- **Status:** ready for implementation

---

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

---

# Koog-Everything and Logging — System Design

Stage: **design** (`/feature-design`, architect). Input: `docs/features/koog-everything-and-logging/01-requirements.md`.
Output of the next stage: `03-plan.md` (planner), which decomposes this document into tasks.

## Context and goals

Every DeepSeek call the service makes must travel through Koog, and a single `POST /weather`
from Postman must be readable in the console as a five-line chain: inbound request → DeepSeek
request → DeepSeek response → `get_weather` tool call → response to the client, all linked by one
correlation id and free of secrets. Concretely this means replacing the last raw Ktor DeepSeek
caller (`LlmTimeZoneResolver`) with a Koog `PromptExecutor` call, wrapping the shared Koog executor
in a logging decorator that is the single choke point for all LLM traffic, tracing the request
through the agent strategy for tool calls, making the `get_weather` description come from a JSON
resource file under `src/main/resources/`, and guaranteeing that every outbound DeepSeek request
carries a non-empty `tools` array. No endpoint, status code, response shape, database schema or
offline-test property changes; the Postman recipe in the requirements stays verbatim.

### Requirement traceability

| Requirement | Design element |
|---|---|
| FR-01 (all DeepSeek traffic via Koog) | §Components C4 `LoggingPromptExecutor`, C5 `LlmTimeZoneResolver` (rewrite), C9 DI wiring; §Decisions D1, D2; §Non-functional coverage: static guard test (`RawDeepSeekCallTest`) |
| FR-02 (never an empty `tools` array; tool-using flows send `get_weather`) | §Components C4 (logs `tools_count`), C5 (`toolChoice = None` + same non-empty descriptor list), C9 (both call sites receive `toolRegistry.tools.map { it.descriptor }`); §Key flows 1 and 2; §Decisions D3, D4 |
| FR-03 (every inbound request logged incl. body) | §Components C1 `RequestTracing`, C2 `TraceLog.inbound`; §Logging design stage `inbound`; §API design `beginTrace` |
| FR-04 (outbound DeepSeek request logged: model, messages, tools) | §Components C4, C6 `ToolJsonRenderer`, C2 `TraceLog.deepseekRequest`; §Logging design stage `deepseek-request` |
| FR-05 (DeepSeek response logged incl. tool calls and failures) | §Components C4 `TraceLog.deepseekResponse` / `.deepseekFailure`; §Key flows 3 |
| FR-06 (tool invocation logged: name, args, result, DB outcome) | §Components C6 (strategy loop), C7 `GetWeatherTool` (spec + DB log), C2 `TraceLog.tool` / `.toolDb`; §Logging design stages `tool`, `db` |
| FR-07 (every HTTP response logged with status and body) | §Components C1 `respondTraced`, C2 `TraceLog.outbound`; §API design `respondTraced`; §Non-functional coverage: StatusPages handlers |
| FR-08 (JSON file describing the tool under `src/main/resources/`) | §Data model: resource file `tools/get-weather-tool.json`; §Components C8 `ToolSpec`/`ToolSpecLoader` |
| FR-09 (tool description exposed to DeepSeek matches the file) | §Components C8 (spec drives `SimpleTool.name`/`description`), §Decisions D5; §Non-functional coverage: consistency test |
| FR-10 (Postman recipe) | §Key flows 5 (recipe unchanged, verbatim from requirements) |
| FR-11 (README updated, stale statements removed) | §Non-functional coverage: configuration and documentation changes |
| NFR-01 (five chain entries, in order, one correlation id, shipped config) | §Architecture overview: five-stage log chain; §Components C1, C2; §Key flows 1, 2 |
| NFR-02 (no secrets in logs) | §Components C2, C4; §Decisions D7; §Non-functional coverage: security |
| NFR-03 (bodies truncated to 4096 chars with marker) | §Components C2 `TraceLog.truncate`; §Logging design: truncation rule |
| NFR-04 (logging never changes API behavior) | §API design (error semantics), §Decisions D6, D9; §Non-functional coverage: robustness |
| NFR-05 (offline tests, 52 tests stay green, new coverage offline) | §Non-functional coverage: test strategy |
| NFR-06 (endpoints/status codes/shapes unchanged) | §API design: HTTP surface (unchanged), §Non-functional coverage: compatibility |
| NFR-07, NFR-08 (feature-design pipeline artifacts) | This document; `01-requirements.md` and `03-plan.md` are produced by their stages; §Decisions D14 |

## Architecture overview

Two LLM entry points, one Koog path, one decorator, five log stages.

```
             (a) inbound                                        (e) outbound
Postman ──────────────────► Ktor routing ─────────────────────────────────────► Postman
                              │  plugins/RequestTracing.kt
                              │  + plugins/Routing.kt StatusPages
                              │
             POST /weather    │                       GET /time?location=…
                              ▼                              ▼
                     WeatherAgent (Koog AIAgent)    TimeZoneResolver chain
                     weather/KoogWeatherAgent.kt     Direct → Builtin → Caching
                              │                              │
                              │ strategy loop                ▼
                              │  requestLLM / executeTools   time/LlmTimeZoneResolver.kt
                              │  (d) stage=tool              (Koog PromptExecutor call)
                              │                              │
                              └──────────────┬───────────────┘
                                             ▼
                            (b),(c)  log/LoggingPromptExecutor.kt   <-- PromptExecutor decorator
                                             ▼                        (single choke point)
                                   MultiLLMPromptExecutor → OpenAILLMClient (Koog)
                                             ▼
                                       DeepSeek API  /chat/completions
```

Five-stage log chain and where each line is emitted:

| Stage | Marker | Emitted by | File |
|---|---|---|---|
| (a) inbound request | `stage=inbound` | route handlers via `ApplicationCall.beginTrace(...)` | `plugins/RequestTracing.kt` |
| (b) outbound DeepSeek request | `stage=deepseek-request` | decorator, before delegating | `log/LoggingPromptExecutor.kt` |
| (c) DeepSeek response | `stage=deepseek-response` | decorator, after the delegate returns / throws | `log/LoggingPromptExecutor.kt` |
| (d) tool call | `stage=tool` | agent strategy loop (`KoogWeatherAgent`), after `executeTools` | `weather/KoogWeatherAgent.kt` |
| (e) outbound response | `stage=outbound` | `ApplicationCall.respondTraced(...)`, including StatusPages handlers | `plugins/RequestTracing.kt` |

Tool-internal detail on the same chain: `stage=db` (insert succeeded or not), `log/TraceLog.kt` called from `weather/GetWeatherTool.kt`.

Correlation id propagation: `beginTrace` creates `CallTrace(id)` (a `CoroutineContext.Element` and a
`call.attributes` entry). Handlers that reach an LLM wrap the call in
`withContext(trace) { … }`; everything downstream (agent strategy, executor decorator, tool) reads
`coroutineContext[CallTrace]` and prints `req=<id>`. This was verified against Koog 1.2.0: the
agent session runs the strategy in the **caller's** coroutine context
(`AIAgentRunSessionImpl.run` creates no new scope or dispatcher), so a custom context element
survives from the route into `requestLLM` and `executeTools`.

File inventory (all paths relative to the repository root):

| Action | Path |
|---|---|
| new | `src/main/kotlin/com/aiturbo/log/CallTrace.kt` |
| new | `src/main/kotlin/com/aiturbo/log/TraceLog.kt` |
| new | `src/main/kotlin/com/aiturbo/log/TraceFormats.kt` |
| new | `src/main/kotlin/com/aiturbo/log/LoggingPromptExecutor.kt` |
| new | `src/main/kotlin/com/aiturbo/plugins/RequestTracing.kt` |
| new | `src/main/kotlin/com/aiturbo/tools/ToolSpec.kt` |
| new | `src/main/kotlin/com/aiturbo/tools/ToolJsonRenderer.kt` |
| new | `src/main/resources/tools/get-weather-tool.json` |
| modify | `src/main/kotlin/com/aiturbo/time/LlmTimeZoneResolver.kt` (rewrite: Koog instead of raw HTTP) |
| modify | `src/main/kotlin/com/aiturbo/weather/KoogWeatherAgent.kt` (strategy logging) |
| modify | `src/main/kotlin/com/aiturbo/weather/GetWeatherTool.kt` (spec-driven, DB outcome logging) |
| modify | `src/main/kotlin/com/aiturbo/plugins/Routing.kt` (drop `CallLogging`, traced responses) |
| modify | `src/main/kotlin/com/aiturbo/plugins/WeatherRouting.kt` (traced responses) |
| modify | `src/main/kotlin/com/aiturbo/Application.kt` (Koin wiring) |
| modify | `src/main/resources/logback.xml` (explicit trace logger) |
| modify | `src/main/resources/application.conf` (comment only) |
| modify | `README.md` (FR-11) |
| new/modify | tests listed in §Non-functional coverage |

No new Gradle dependency is required. `ktor-server-call-logging` becomes unused (its installation is
removed); the dependency may stay or be dropped.

## Components

### C1. Request tracing on the Ktor side — `plugins/RequestTracing.kt` (new)

Responsibility: create the correlation id, emit stage (a) and stage (e), and expose the trace to the
StatusPages handlers. No dependency on Koog or on the DI graph, so route tests keep working
unchanged.

```kotlin
private val TraceAttribute = AttributeKey<CallTrace>("aiturbo.trace")

/** Idempotent: returns the existing trace if the call was already traced. */
fun ApplicationCall.beginTrace(body: String? = null): CallTrace

/** Trace of the current call, or null when the handler never called beginTrace. */
fun ApplicationCall.traceOrNull(): CallTrace?

/** Logs stage=outbound (status + JSON body) and then responds. */
suspend inline fun <reified T : Any> ApplicationCall.respondTraced(
    body: T,
    status: HttpStatusCode = HttpStatusCode.OK,
)
```

- `beginTrace` generates `CallTrace(newTraceId())`, stores it in `call.attributes`, logs
  `stage=inbound` with `method`, `path`, `query`, `client` (`call.request.origin.remoteHost` +
  `remotePort`) and the optional body, and is idempotent.
- `respondTraced` logs `stage=outbound` with `status` and the body serialized by a compact
  `Json { encodeDefaults = true; explicitNulls = false }`, then delegates to `call.respond(status, body)`.
  It never swallows or rewrites the body; serialization for logging is wrapped in `runCatching` so a
  logging failure cannot turn a 200 into a 500 (NFR-04).
- Every `call.respond` in `Routing.kt` and `WeatherRouting.kt` is replaced by `respondTraced`,
  including the four StatusPages handlers. In the `ContentTransformationException` /
  `BadRequestException` handlers a missing trace is created first (`call.beginTrace()`), so a
  malformed body still yields one `stage=inbound` line without a body plus one `stage=outbound` 400
  line.
- Handlers that reach an LLM are wrapped: `withContext(trace) { agent.answer(message) }` and
  `withContext(trace) { resolver.resolve(location) }`.

Alternatives considered are recorded in §Decisions D9 (Ktor `CallLogging` and `onCallRespond`
plugin hooks were rejected) and D10 (DoubleReceive was rejected).

### C2. Trace logger — `log/TraceLog.kt` and `log/CallTrace.kt` (new)

```kotlin
class CallTrace(val id: String) : AbstractCoroutineContextElement(CallTrace) {
    companion object Key : CoroutineContext.Key<CallTrace>
    override fun toString(): String = id
}

fun newTraceId(): String = UUID.randomUUID().toString().take(8)

object TraceLog {
    const val LOGGER_NAME = "com.aiturbo.trace"
    const val MAX_BODY_CHARS = 4096
    val logger: Logger = LoggerFactory.getLogger(LOGGER_NAME)

    suspend fun currentId(): String? = coroutineContext[CallTrace]?.id

    fun inbound(id: String, method: String, path: String, query: String?, client: String?, body: String?)
    fun deepseekRequest(id: String?, endpoint: String, model: String, messages: List<Message>,
                        toolsCount: Int, tools: String, toolChoice: String?)
    fun deepseekResponse(id: String?, model: String, response: Message.Assistant)
    fun deepseekFailure(id: String?, model: String, error: Throwable)
    fun tool(id: String?, name: String, args: String, result: String, isError: Boolean)
    fun toolDb(id: String?, tool: String, saved: Boolean, recordId: Int?, reason: String?)
    fun outbound(id: String, status: Int, body: String)

    internal fun truncate(text: String, max: Int = MAX_BODY_CHARS): String
    internal fun describeError(t: Throwable): String
}
```

- One logger name for the whole chain (`com.aiturbo.trace`) so the five entries are visually uniform
  and filterable; class loggers stay for component-internal warnings.
- Missing id renders as `req=-` (never `null`).
- `truncate` appends an explicit marker: `…[truncated, NNNN chars total]`.
- `describeError` walks the cause chain (`SimpleName: message`) and, for
  `ai.koog.http.client.KoogHttpClientException`, appends `status=<statusCode> body=<errorBody>`
  (truncated) — that is where Koog puts the HTTP status and the error payload. If that type is not
  public in the shipped Koog artifact, the fallback is the cause-chain walk only (see Risks R5).

### C3. Traffic formatters — `log/TraceFormats.kt` (new)

Pure, unit-testable functions (no logging side effects), all returning single-line strings and all
truncated by the caller:

```kotlin
internal fun renderMessages(messages: List<Message>): String      // [system: "...", user: "..."]
internal fun renderAssistant(response: Message.Assistant): String // text="..." tool_calls=[{"name":…,"args":…}]
internal fun renderJson(obj: JsonObject): String                  // compact JSON, one line
```

`Message.Assistant.parts` is a `List<MessagePart.ResponsePart>`; text comes from
`MessagePart.Text.text`, tool calls from `MessagePart.Tool.Call` (`id`, `tool`, `args` — `args` is
already a JSON string in Koog 1.2.0).

### C4. Logging executor decorator — `log/LoggingPromptExecutor.kt` (new)

The single choke point for every DeepSeek call in the application (FR-01, FR-04, FR-05).

```kotlin
class LoggingPromptExecutor(
    private val delegate: PromptExecutor,
    private val endpoint: String,                  // "<baseUrl>/chat/completions", not a secret
    private val toolJsonRenderer: ToolJsonRenderer,
) : PromptExecutor() {

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame>
    override fun close() { delegate.close() }
}
```

Behaviour of `execute`:

1. `val id = TraceLog.currentId()`.
2. Log `stage=deepseek-request` with `endpoint`, `model.id`, `renderMessages(prompt.messages)`,
   `tools_count=<tools.size>`, `tools=<renderTools(tools)>`, `tool_choice=<prompt.params.toolChoice ?: ->`.
   When `tools` is empty, the same line carries `tools_count=0` and a `WARN`-level duplicate is
   emitted so an accidental empty-tools call is impossible to miss (FR-02). The decorator does not
   throw on an empty list — refusing would change behaviour (§Decisions D3).
3. Call `delegate.execute(prompt, model, tools)` **unchanged** (the decorator never filters,
   reorders or adds tools).
4. On success log `stage=deepseek-response` with `model.id` and
   `renderAssistant(response)` (`text=…`, `tool_calls=[…]`).
5. On `CancellationException`: rethrow without logging. On any other exception: log
   `stage=deepseek-response … error=<describeError(e)>` and rethrow — graceful degradation stays in
   the callers (resolver → 404, agent → 503).

`executeStreaming` logs `stage=deepseek-request` (with a `streaming=true` field) and then delegates
the flow untouched; no frame-level logging is added because nothing in this application streams.
`close()` delegates to the wrapped executor; the decorator is never closed by Koin.

Rejected alternatives (agent event-handler feature, logging inside each call site): §Decisions D1.

### C5. `LlmTimeZoneResolver` rewritten on Koog — `time/LlmTimeZoneResolver.kt` (modify)

Keeps the class name and the `TimeZoneResolver` interface (`suspend fun resolve(location: String): ZoneId?`),
so `CompositeTimeZoneResolver` and `CachingTimeZoneResolver` keep working unchanged.

```kotlin
class LlmTimeZoneResolver(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val toolDescriptorsProvider: () -> List<ToolDescriptor>,
    private val apiKeyConfigured: Boolean = true,
) : TimeZoneResolver {

    override suspend fun resolve(location: String): ZoneId?
}

internal fun parseZoneContent(content: String?): ZoneId?   // moved out of the class, still unit-tested
```

`resolve` semantics (unchanged from today, only the transport changes):

1. `!apiKeyConfigured` → warn, return `null` **without any call** (preserves
   `GET /time` → 404 when `DEEPSEEK_API_KEY` is blank).
2. Build the prompt:
   `Prompt.build(id = "time-zone-resolution", params = LLMParams(temperature = 0.0, maxTokens = 64, toolChoice = LLMParams.ToolChoice.None)) { system(SYSTEM_PROMPT); user("Location: $location") }`.
   `SYSTEM_PROMPT` is the existing instruction (return only `{"timezone": "<IANA id>"}`, `null` when
   unknown); the wording stays, since it is now the only structured-output mechanism.
3. `promptExecutor.execute(prompt, model, toolDescriptorsProvider())` — the **same non-empty
   descriptor list** the weather agent uses (§Decisions D4).
4. Concatenate `MessagePart.Text` parts, then `parseZoneContent` (strip code fences, decode
   `ZoneGuess`, `ZoneId.of`, any failure → `null`). Tool calls in the answer are not executed; they
   remain visible in the `stage=deepseek-response` line and lead to `null` → 404.
5. `CancellationException` rethrown; any other exception → warn (existing message) and `null`.

The raw-HTTP artifacts (`ChatMessage`, `ChatCompletionRequest`, `ChatCompletionResponse`, `Choice`,
`ResponseFormat`, the `HttpClient`/`Authorization` usage) are deleted. `DeepseekConfig` keeps being
the source of `baseUrl`/`apiKey`/`model` for the wiring in C9.

### C6. Weather agent strategy logging — `weather/KoogWeatherAgent.kt` (modify)

The strategy loop keeps its shape (up to `maxToolRounds` rounds, `executeTools`, `sendToolResults`,
non-convergence warning) and adds exactly one `stage=tool` line per tool invocation, after the tool
has run, so a single invocation produces a single chain entry (FR-06, NFR-01):

```kotlin
val traceId = TraceLog.currentId()
var response = requestLLM(input)
while (rounds < maxToolRounds) {
    val calls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
    if (calls.isEmpty()) break
    val results = executeTools(calls)
    calls.zip(results).forEach { (call, result) ->
        TraceLog.tool(traceId, call.tool, call.args, result.output, result.toMessagePart().isError)
    }
    response = sendToolResults(results)
    rounds++
}
```

`ReceivedToolResult` (`ai.koog.agents.core.environment`) provides `tool`, `toolArgs`, `output`,
`resultKind` and `toMessagePart()`; `isError` is derived through `toMessagePart()` to avoid
depending on `ToolResultKind` internals. Logging stays inside `runCatching`-free straight-line code
but must never throw (`traceId` may be `null` in unit tests — allowed, renders `req=-`).

The agent receives the tool descriptors from the registry, which Koog passes to the executor
(`tools = toolRegistry.tools.map { it.descriptor }` in `FunctionalAIAgent.prepareContext`), so the
`stage=deepseek-request` line for the weather flow always shows a non-empty `tools` array.

### C7. `GetWeatherTool` — spec-driven description and DB outcome — `weather/GetWeatherTool.kt` (modify)

```kotlin
class GetWeatherTool(
    private val resolver: TimeZoneResolver,
    private val weatherClient: WeatherClient,
    private val timeService: TimeService,
    private val repository: WeatherRecordRepository,
    spec: ToolSpec,                                  // NEW: name + description from the JSON resource
) : SimpleTool<GetWeatherArgs>(
    argsType = typeToken<GetWeatherArgs>(),
    name = spec.name,
    description = spec.description,
)
```

- The parameter schema still comes from Koog (`GetWeatherArgs` with its `@LLMDescription`); only the
  tool *name* and *description* come from the resource file (FR-09). A consistency test keeps the
  resource's `parameters` block honest (see NFR-05).
- The returned string (what the model sees, and therefore the final answer) is **not** changed —
  no "запись сохранена" suffix is added, because prompts and answer format are explicitly out of
  scope. The database outcome is logged instead:

```kotlin
val outcome = runCatching { withContext(Dispatchers.IO) { repository.save(timeService.nowIn(zone).toLocalDateTime()) } }
outcome.onSuccess { id ->
    if (id >= 0) TraceLog.toolDb(TraceLog.currentId(), name, saved = true, recordId = id, reason = null)
    else TraceLog.toolDb(TraceLog.currentId(), name, saved = false, recordId = null, reason = "unique conflict on 'data'")
}.onFailure { TraceLog.toolDb(TraceLog.currentId(), name, saved = false, recordId = null, reason = it.message) }
```

  This is the only addition to the tool and it satisfies "log … including whether the database insert
  succeeded" (FR-06) on both the success and the failure path, without touching behaviour
  (NFR-04, NFR-06).

### C8. Tool specification resource + loader — `tools/ToolSpec.kt` (new)

```kotlin
@Serializable
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

object ToolSpecLoader {
    const val RESOURCE_PATH = "tools/get-weather-tool.json"
    fun load(resourcePath: String = RESOURCE_PATH, classLoader: ClassLoader = ToolSpec::class.java.classLoader): ToolSpec
}
```

`load` reads the classpath resource, decodes it, and throws `IllegalStateException` with the resource
path and the cause when the file is missing or invalid (fail fast at startup, §Decisions D5). It also
validates the non-empty `name`/`description`. The decoded spec is bound in Koin with
`createdAtStart = true` and passed to `GetWeatherTool`; the loader logs one INFO line on its own class
logger (`Tool spec loaded: name=get_weather …`). Tests provide a fake by calling
`ToolSpecLoader.load(testResourcePath)` or by constructing a `ToolSpec` directly — no DI needed.

### C9. Wire-format tool renderer — `tools/ToolJsonRenderer.kt` (new)

Turns the same descriptors Koog is about to send into the OpenAI wire shape, so the log shows
exactly what leaves the process (FR-02, FR-04):

```kotlin
class ToolJsonRenderer(
    private val schemaGenerator: OpenAICompatibleToolDescriptorSchemaGenerator =
        OpenAICompatibleToolDescriptorSchemaGenerator(),
) {
    fun toWireJson(tool: ToolDescriptor): JsonObject
    fun renderAll(tools: List<ToolDescriptor>): String   // "[]" when empty, else the array as one line
}
```

`toWireJson` mirrors `AbstractOpenAILLMClient.toOpenAIChatTool()`:
`{"type":"function","function":{"name": descriptor.name,"description": descriptor.description,
"parameters": schemaGenerator.generate(descriptor)}}` — the parameters block is generated by Koog's
own public `OpenAICompatibleToolDescriptorSchemaGenerator` (`ai.koog.prompt.executor.clients.openai.base`),
so the rendered schema cannot drift from the request Koog builds. The class is stateless and
unit-tested directly (`ToolJsonRendererTest`), and injected into the decorator so tests can
substitute a fake.

### C10. Dependency-injection wiring — `Application.kt` (modify)

```kotlin
fun appModules(deepseek: DeepseekConfig, db: DbConfig, weather: WeatherConfig): Module = module {
    single { Clock.systemUTC() }
    single { HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } } }

    // Tool description resource — loaded once at startup, fail fast if missing/invalid
    single(createdAtStart = true) { ToolSpecLoader.load() }
    single { ToolJsonRenderer() }

    single<WeatherClient> { OpenMeteoWeatherClient(get(), weather) }
    single<WeatherRecordRepository> { JdbcWeatherRecordRepository(db) }
    single { GetWeatherTool(get(), get(), get(), get(), get()) }        // + spec
    single { ToolRegistry.builder().tool(get<GetWeatherTool>()).build() }
    single { deepseekModel(deepseek.model) }

    // Every DeepSeek call goes through this decorator
    single<PromptExecutor> {
        LoggingPromptExecutor(
            delegate = deepseekPromptExecutor(deepseek),
            endpoint = "${deepseek.baseUrl.trimEnd('/')}/chat/completions",
            toolJsonRenderer = get(),
        )
    }

    single<TimeZoneResolver> {
        CompositeTimeZoneResolver(
            listOf(
                DirectZoneResolver(),
                BuiltinTimeZoneResolver(),
                CachingTimeZoneResolver(
                    LlmTimeZoneResolver(
                        promptExecutor = get(),
                        model = get(),
                        toolDescriptorsProvider = { get<ToolRegistry>().tools.map { it.descriptor } },
                        apiKeyConfigured = deepseek.apiKey.isNotBlank(),
                    )
                ),
            )
        )
    }
    singleOf(::TimeService)
    single<WeatherAgent> {
        if (deepseek.apiKey.isBlank()) {
            WeatherAgent { throw WeatherUnavailableException("DeepSeek API key is not configured") }
        } else {
            KoogWeatherAgent(get(), get(), get())
        }
    }
}
```

Two points matter here:

- **Cycle break.** `GetWeatherTool` needs `TimeZoneResolver`, and the time-zone resolver now needs
  the tool descriptors. Injecting `ToolRegistry` eagerly would create a Koin cycle; the lazy
  `toolDescriptorsProvider: () -> List<ToolDescriptor>` resolves the registry on first use
  (Koin's `single` definition lambda runs in a `Scope`, so `get()` inside the nested lambda resolves
  at call time). The resolver caches the list with `by lazy` — §Decisions D11.
- **Non-empty tools on both paths.** The weather agent gets them from its registry; the resolver
  gets the same list from the provider. Both therefore satisfy "never `tools: []`" (§Decisions D3, D4).

### C11. Cross-cutting: logging design (loggers, formats, samples)

**Logger names.** One chain logger shared by all five stages:

| Logger | Used for |
|---|---|
| `com.aiturbo.trace` (`TraceLog`) | `stage=inbound`, `deepseek-request`, `deepseek-response`, `tool`, `db`, `outbound` |
| class loggers (unchanged) | component warnings: `LlmTimeZoneResolver` (key missing, call failed), `KoogWeatherAgent` (tool loop did not converge), `OpenWeatherClient`, `JdbcWeatherRecordRepository`, `Routing.kt` StatusPages warnings, `ToolSpecLoader` startup line |

**Line format.** Existing logback pattern, unchanged:
`%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`, and every chain line is
`req=<id> stage=<marker> <key=value …>`. `%thread` shows the executing thread; the correlation id
travels in the message instead of MDC because MDC is thread-local and does not follow coroutine
dispatches (the agent and the executor hop dispatchers).

**Truncation.** Every body-like field (inbound body, rendered messages, rendered tools, response
text, tool args, tool result, outbound body) goes through `TraceLog.truncate(…)`: at most 4096
characters plus an explicit marker `…[truncated, NNNN chars total]` (NFR-03).

**Stage samples** (format is literal; values illustrative):

```
2026-09-16 10:15:22.481 [eventLoopGroupProxy-4-2] INFO  com.aiturbo.trace - req=8f2c1ad4 stage=inbound method=POST path=/weather query=- client=127.0.0.1:54321 body={"message":"Какая сейчас погода в Москве?"}
2026-09-16 10:15:22.905 [eventLoopGroupProxy-4-2] INFO  com.aiturbo.trace - req=8f2c1ad4 stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-chat tool_choice=- tools_count=1 tools=[{"type":"function","function":{"name":"get_weather","description":"Возвращает текущую погоду и местное время для указанного города или региона","parameters":{"type":"object","properties":{"location":{"type":"string","description":"Город или регион, например 'Москва' или 'Berlin'"}},"required":["location"]}}}] messages=[system: "Ты — ассистент по погоде…", user: "Какая сейчас погода в Москве?"]
2026-09-16 10:15:24.117 [eventLoopGroupProxy-4-2] INFO  com.aiturbo.trace - req=8f2c1ad4 stage=deepseek-response model=deepseek-chat text="" tool_calls=[{"name":"get_weather","args":"{\"location\":\"Москва\"}"}]
2026-09-16 10:15:25.331 [eventLoopGroupProxy-4-2] INFO  com.aiturbo.trace - req=8f2c1ad4 stage=tool tool=get_weather args={"location":"Москва"} is_error=false result="Москва, Россия: +15.4°C, облачно, влажность 66%, часовой пояс Europe/Moscow, местное время 2026-09-16 13:15:25 (Wednesday)"
2026-09-16 10:15:25.334 [eventLoopGroupProxy-4-2] INFO  com.aiturbo.trace - req=8f2c1ad4 stage=db tool=get_weather saved=true id=7
2026-09-16 10:15:26.002 [eventLoopGroupProxy-4-2] INFO  com.aiturbo.trace - req=8f2c1ad4 stage=outbound status=200 body={"message":"Какая сейчас погода в Москве?","answer":"Сейчас в Москве +15.4°C…"}
```

Failure variants: `stage=deepseek-response … error=LLMClientException: 401 status=401 body={"error":…}`
(FR-05), `stage=db tool=get_weather saved=false reason=Connection refused` while `stage=tool` and
`stage=outbound 200` still appear (DB-down behaviour preserved, FR-06).

No secret ever reaches these lines: the decorator receives only the endpoint label, model id, prompt,
descriptors and response — never `DeepseekConfig`, headers or the API key (§Decisions D7).

## API design

### HTTP surface — unchanged

| Method | Path | Success | Errors | Response shape |
|---|---|---|---|---|
| `GET` | `/` | 200 | — | `ServiceInfoResponse{service, usage}` |
| `GET` | `/time?location=` | 200 | 400 (missing), 404 (unresolvable) | `TimeResponse` |
| `POST` | `/weather` | 200 | 400 (blank/malformed body), 503 (no key / agent failure) | `WeatherResponse{message, answer}` |
| `GET` | `/weather/history?limit=` | 200 | 400 (limit not 1..100) | `WeatherHistoryRecords{records[]}` |
| — | any | — | 500 (unhandled) | `ErrorResponse{error}` |

No endpoint, status code or JSON shape changes (NFR-06). No new headers are added; the correlation id
stays inside the logs.

### Internal interfaces (new or changed)

| Signature | File | Notes |
|---|---|---|
| `suspend fun TimeZoneResolver.resolve(location: String): ZoneId?` | `time/TimeZoneResolver.kt` | unchanged |
| `LlmTimeZoneResolver(promptExecutor, model, toolDescriptorsProvider, apiKeyConfigured = true)` | `time/LlmTimeZoneResolver.kt` | replaces `(HttpClient, DeepseekConfig)` |
| `internal fun parseZoneContent(content: String?): ZoneId?` | `time/LlmTimeZoneResolver.kt` | extracted, unchanged logic |
| `LoggingPromptExecutor(delegate, endpoint, toolJsonRenderer) : PromptExecutor()` | `log/LoggingPromptExecutor.kt` | overrides `execute`, `executeStreaming`, `close` |
| `TraceLog.inbound/deepseekRequest/deepseekResponse/deepseekFailure/tool/toolDb/outbound/currentId/truncate/describeError` | `log/TraceLog.kt` | §Components C2 |
| `ApplicationCall.beginTrace(body: String? = null): CallTrace`, `ApplicationCall.traceOrNull()`, `suspend inline fun <reified T : Any> ApplicationCall.respondTraced(body: T, status = OK)` | `plugins/RequestTracing.kt` | idempotent |
| `ToolSpecLoader.load(resourcePath = RESOURCE_PATH, classLoader = …): ToolSpec` | `tools/ToolSpec.kt` | throws `IllegalStateException` |
| `ToolJsonRenderer.toWireJson(tool): JsonObject`, `ToolJsonRenderer.renderAll(tools): String` | `tools/ToolJsonRenderer.kt` | stateless |
| `GetWeatherTool(resolver, weatherClient, timeService, repository, spec)` | `weather/GetWeatherTool.kt` | adds `spec` |
| `WeatherAgent.answer(message: String): String` | `weather/WeatherAgent.kt` | unchanged |

Validation and error semantics are unchanged: `POST /weather` blank message → 400 before any LLM
call; `GET /time` blank location → 400 before any resolver call; blank API key → 404 for `/time` and
503 for `/weather` with no outbound traffic.

## Data model

No database change: the `users` table (`id`, `data`, `time`, `created_at`) and
`WeatherRecordRepository` stay exactly as they are; no migration.

### Resource file — `src/main/resources/tools/get-weather-tool.json`

```json
{
  "name": "get_weather",
  "description": "Возвращает текущую погоду и местное время для указанного города или региона",
  "parameters": {
    "type": "object",
    "properties": {
      "location": {
        "type": "string",
        "description": "Город или регион, например 'Москва' или 'Berlin'"
      }
    },
    "required": ["location"]
  }
}
```

| Field | Type | Role |
|---|---|---|
| `name` | string, non-blank | passed to `SimpleTool.name` → the function name on the wire and the name the model sees |
| `description` | string, non-blank | passed to `SimpleTool.description` → the description on the wire (FR-09) |
| `parameters` | JSON Schema object | documentation of the generated schema; kept honest by a consistency test |

The file is the **runtime source of truth** for the tool's name and description (§Decisions D5):
it is loaded by `ToolSpecLoader` at startup and fed into `GetWeatherTool`, so editing only the file
(plus restart) changes what DeepSeek receives. The `parameters` block cannot be authoritative without
bypassing Koog's schema generation from the args class, so it is treated as mirrored documentation and
guarded by a test that compares it with the descriptor Koog generates; `@LLMDescription` on
`GetWeatherArgs.location` stays the schema's source.

### In-memory structures

| Structure | Shape | Lifetime |
|---|---|---|
| `CallTrace` | `CoroutineContext.Element` + `call.attributes` entry holding `id: String` (8 hex chars) | one HTTP request |
| `ToolSpec` | `name: String`, `description: String`, `parameters: JsonObject` | singleton, decoded once |
| Log record | one line: `req=<id> stage=<marker> <k=v…>`, bodies truncated to 4096 chars | console only, no persistence |

## Key flows

### Flow 1 — `POST /weather` from Postman (all five stages)

1. Postman sends `{"message":"Какая сейчас погода в Москве?"}`. Ktor routes to
   `weatherRoutes()`; the handler runs `call.beginTrace()` **after** `call.receive<WeatherRequest>()`
   and logs (a) with the re-serialized body, storing `CallTrace(id)` in the call attributes.
2. Blank message → `respondTraced(ErrorResponse, 400)`: (a) and (e) only, no LLM call.
3. `withContext(trace) { agent.answer(message) }` → `KoogWeatherAgent` strategy →
   `requestLLM(input)` → `LoggingPromptExecutor.execute` logs (b) with
   `endpoint`, `model=deepseek-chat`, `messages=[system…, user…]`, `tools_count=1` and the full
   `get_weather` JSON (description taken from the resource file) → `MultiLLMPromptExecutor` →
   DeepSeek.
4. DeepSeek answers with a tool call. The decorator logs (c) `tool_calls=[{"name":"get_weather",…}]`.
5. The strategy calls `executeTools`; `GetWeatherTool` resolves the zone, fetches Open-Meteo,
   inserts the `users` row (logging `stage=db`), returns the summary. The strategy logs one
   `stage=tool` line with args, result and `is_error=false`.
6. `sendToolResults` → second LLM round → the decorator logs (b) and (c) again (the chain then has
   two DeepSeek round-trips, which is the normal agent loop), and the strategy returns the final text.
7. `respondTraced(WeatherResponse(message, answer), 200)` logs (e) with status and body; the row is
   visible in `GET /weather/history`.

Edge cases: message blank → 400 (stages a/e only). Body malformed → `ContentTransformationException`
→ StatusPages creates the missing trace, logs (a) without body and (e) 400. API key blank →
`WeatherAgent` throws `WeatherUnavailableException` before any Koog call → 503 with (a) and (e).
DeepSeek error/timeout → decorator logs (c) with `error=…` and rethrows; StatusPages returns 503;
(a) and (e) still present. Database down → `stage=db saved=false`, answer still 200 (existing
behaviour). Tool loop does not converge after `maxToolRounds` → existing warn line, (e) still 200.

### Flow 2 — `GET /time?location=Kisumu` after the migration

1. `beginTrace()` (a) → `withContext(trace) { resolver.resolve("Kisumu") }`.
2. `DirectZoneResolver` (no slash) and `BuiltinTimeZoneResolver` (not in the map) return null;
   `CachingTimeZoneResolver` delegates once.
3. `LlmTimeZoneResolver`: key configured → build the prompt (system instruction, user
   `Location: Kisumu`, `temperature=0.0`, `maxTokens=64`, `toolChoice=ToolChoice.None`) →
   `promptExecutor.execute(prompt, model, toolDescriptorsProvider())`.
4. The decorator logs (b) with `tools_count=1 tools=[…get_weather…] tool_choice=none`, so the wire
   request carries a non-empty `tools` array and is visible in the log (FR-02, FR-04).
5. DeepSeek answers with text; the decorator logs (c); the resolver concatenates text parts and
   parses `{"timezone":"Africa/Nairobi"}` → `ZoneId`; the answer is cached by
   `CachingTimeZoneResolver` (including the null case, unchanged).
6. `respondTraced(TimeResponse, 200)` logs (e).

Edge cases: unknown location → model returns `{"timezone":null}` → 404 (stages a/b/c/e, no tool call);
DeepSeek HTTP error or timeout → decorator logs (c) with the error, resolver returns null → 404;
blank key → no outbound call at all (warn on the class logger) → 404; model unexpectedly answers with
a tool call despite `toolChoice=none` → the call is logged in (c), never executed, text is empty →
null → 404. `/time` requests for built-in cities or IANA ids never reach the LLM (unchanged).

### Flow 3 — DeepSeek failure on the weather path

Decorator catches, logs `stage=deepseek-response … error=LLMClientException: … status=401 body=…`,
rethrows; `KoogWeatherAgent` propagates; StatusPages maps `WeatherUnavailableException`/
`Throwable` to 503 and logs (e) with status and `ErrorResponse` body. Exactly one (b) and one (c)
line per round-trip, and the chain stays greppable by `req=`.

### Flow 4 — startup

Koin creates `ToolSpecLoader.load()` eagerly (`createdAtStart = true`): the resource is read from the
classpath, validated, and logged (`Tool spec loaded: name=get_weather description="…"`). A missing or
invalid file aborts startup with an `IllegalStateException` naming the resource path — a packaging
error surfaces immediately instead of at the first request.

### Flow 5 — Postman manual verification (unchanged)

The recipe in `01-requirements.md` ("Postman examples") is used verbatim: `POST http://localhost:8080/weather`
with `Content-Type: application/json` and body `{"message": "Какая сейчас погода в Москве?"}`; expect
`200` with `{message, answer}`, one new `users` row (checked with the `docker exec … psql` command or
`GET http://localhost:8080/weather/history?limit=5`), and the five-stage chain in the console as in
Flow 1. Request 3 (`GET /time?location=Moscow`) stays a built-in-map resolution with (a)/(e) lines
only. The README (FR-11) mirrors this recipe, describes the Koog-only DeepSeek path and the log chain,
and drops the stale "DeepSeek LLM over HTTP" wording and the old "over HTTP" comment in
`application.conf`.

## Decisions and alternatives

| # | Decision | Alternatives considered | Rationale |
|---|---|---|---|
| D1 | One `PromptExecutor` decorator (`LoggingPromptExecutor`) wrapped around the shared executor; both call sites get the decorated bean | (a) Koog `agents-features-event-handler` / trace feature; (b) logging inside each call site; (c) a second Koog agent for time-zone resolution | (a) only covers the agent path and pulls in a feature module; (b) duplicates logic and would diverge; (c) adds an agent, a strategy and a tool loop for a single JSON answer. One decorator is the minimum code that guarantees "every Koog LLM call is logged" and stays testable with a fake delegate |
| D2 | Time-zone resolution uses `PromptExecutor.execute` with a fixed two-message prompt | Keep raw Ktor HTTP (rejected by FR-01); second agent (see D1c) | Simplest mechanism that preserves the current semantics (one call, JSON-ish answer) and reuses the same executor factory, model and logging |
| D3 | Empty `tools` list is never passed: both call sites pass the shared non-empty descriptor list; the decorator warns (does not throw) if it ever sees an empty list | (a) throw on empty tools; (b) `require` at startup | Koog omits the `tools` field entirely when the list is empty (`tools.takeIf { it.isNotEmpty() }`), so the wire never shows `"tools": []`; throwing in the decorator could turn a working call into a 500/503 and violate NFR-04. The log line plus `tools_count` makes the invariant observable, and DI guarantees the inputs |
| D4 | `/time` requests send the same non-empty `tools` array, with `toolChoice = None` | ASM-04 default (no `tools` field on `/time`) | The user's hard instruction wins over the analyst's default: no backend→DeepSeek request may lack tools. `ToolChoice.None` keeps the model from calling `get_weather` (which would insert database rows), preserving behaviour. Conflict recorded in R1 |
| D5 | `tools/get-weather-tool.json` is the runtime source of truth for name + description (OQ-02 / ASM-02); load fail-fast at startup | (a) documentation-only file (FR-09 dropped); (b) load at runtime with fallback to hardcoded defaults | FR-09 requires the description sent to be the file's; a fallback default would silently diverge from the file. The `parameters` block stays generated from the args class (Koog owns schema generation) and is guarded by a consistency test |
| D6 | Tool-call logging happens in the agent strategy (one `stage=tool` line after `executeTools`); the DB outcome is a separate `stage=db` line from the tool | (a) two lines per invocation (call, then result); (b) log from inside the tool only; (c) put the insert outcome into the returned string | (a) doubles stage-(d) entries against NFR-01; (b) loses the arguments the model asked for; (c) changes the prompt/answer content, which is out of scope. One strategy line + one tool-internal line satisfies FR-06 with the fewest entries |
| D7 | Secrets: the decorator receives only an endpoint label and never the config object; no headers are logged; truncation applies everywhere | Log the full outgoing HTTP request incl. headers | The API key travels in the `Authorization` header, and `DeepseekConfig`/`DbConfig` are data classes whose `toString()` would expose secrets — they are never logged (NFR-02) |
| D8 | Drop wire-level `response_format: json_object`; rely on the system instruction plus the existing lenient parser | `LLMParams(schema = Schema.JSON.Basic(...))` (Koog's only structured-output parameter) | Koog 1.2.0 exposes JSON **schema** response format, which maps to `response_format: {"type":"json_schema", strict=true}`. DeepSeek rejects `json_schema` with HTTP 400 ("This response_format type is unavailable now"); using it would turn `/time` into a permanent 404. Observable behaviour (a zone or null) is preserved because `parseZoneContent` already tolerates fences and malformed answers. Risk R4 |
| D9 | Emit (a) and (e) from explicit helpers called by the handlers (and by StatusPages), not from Ktor plugin hooks | (a) keep `CallLogging` for the request line and add a response plugin (`onCallRespond`); (b) `intercept(ApplicationCallPipeline.Setup)` + `withContext`; (c) `ResponseBodyReadyForSend` hook | `CallLogging` writes its line after the handler completes, so it would appear *after* the DeepSeek and tool lines and break NFR-01's order, and it would add a sixth entry. `onCallRespond` sees only the pre-serialized object and its behaviour for StatusPages-produced responses is not guaranteed. Explicit helpers give a deterministic order, exactly one entry per stage, and are trivially asserted in route tests |
| D10 | Log the request body where it is parsed (route), re-serialized from the DTO | `DoubleReceive` plugin + `call.receiveText()` in a plugin; `onCallReceive { transformBody { … } }` channel read-and-replace | Both alternatives read the body channel ourselves (or add a dependency and an experimental API) before the route receives it, which risks breaking `POST /weather` (NFR-04). Re-serializing the already-parsed body yields exactly the JSON the acceptance criterion expects, with no new dependency |
| D11 | Break the descriptor/registry cycle with a lazy `toolDescriptorsProvider: () -> List<ToolDescriptor>` injected into the resolver (cached with `by lazy`) | (a) eager `ToolRegistry` injection (Koin cycle: resolver → registry → tool → resolver); (b) hand-build a `ToolDescriptor` via internal `getToolDescriptor`; (c) pass `ToolRegistry` into the tool and the resolver and resolve the tool lazily | (a) fails at runtime with a circular dependency; (b) uses `@InternalAgentToolsApi` and duplicates Koog's schema generation; (c) is the same idea with more coupling. A provider lambda is plain Kotlin, keeps the cycle broken, and is easy to fake in tests |
| D12 | One chain logger name (`com.aiturbo.trace`) with `req=`/`stage=` markers; no MDC, existing pattern | Per-class loggers + MDC `reqId` in the logback pattern | MDC is thread-local and does not survive coroutine dispatcher hops (the agent, the executor and the tool all hop dispatchers), so MDC would produce lines without the id; the message marker works everywhere and needs no pattern change (NFR-01) |
| D13 | Correlation id as a `CoroutineContext.Element` (`CallTrace`) set by `withContext(trace)` around LLM-reaching work | `call.attributes` only (invisible to the agent); request-scoped Koin scope; thread-local | Verified: `AIAgentRunSessionImpl.run` executes the strategy in the caller's context, so the element reaches the strategy, the decorator and the tool. `call.attributes` cannot reach Koog internals |
| D14 | Keep every endpoint, status code, DTO and the database schema untouched; no new Gradle dependency | Refactors of routes/DTOs, `ktor-server-double-receive` | NFR-05/NFR-06; the feature is about the LLM path and observability only. `03-plan.md` and this document are the pipeline artifacts for NFR-07/FR-08 |

## Non-functional coverage

**NFR-01 (five ordered entries, one id, shipped config).** Order is structural: (a) is logged before
the handler runs any work, (b)/(c) come from the decorator around the LLM call, (d) from the strategy
before it returns, (e) from `respondTraced` last. One entry per stage by construction (D6, D9). The
shipped `logback.xml` (root INFO, STDOUT) prints everything; the only change is an explicit
`<logger name="com.aiturbo.trace" level="INFO"/>` entry for discoverability, which does not change
what is printed.

**NFR-02 (no secrets).** The decorator's inputs are the endpoint string, the model id, the prompt,
the descriptors and the response (D7). No headers, no `Authorization`, no `DeepseekConfig`, no
`DbConfig` and no JDBC URL are logged; the API key only ever exists inside Koog's OpenAI client.
Truncation does not affect this. A test asserts that a run's captured trace output contains neither
the configured key nor the database password.

**NFR-03 (truncation).** `TraceLog.truncate` caps every body-like field at 4096 characters and adds
the marker `…[truncated, NNNN chars total]`; unit-tested at the boundary (4095/4096/4097).

**NFR-04 (logging cannot change behaviour).** Logging never consumes the body (D10), never mutates
the prompt/tools/response (the decorator passes `tools` through untouched), and every logging call in
the request path is wrapped so that a serialization or appender failure cannot raise a new status
code: `beginTrace`/`respondTraced` catch `Throwable` around logging and fall back to plain
`call.respond`. Status codes stay 400/404/500/503 exactly as today, asserted by the existing route
tests plus new 400/404/503 log assertions.

**NFR-05 (offline tests, 52 green).** All new behaviour is tested without a database or a live LLM:

| Test | Coverage |
|---|---|
| `TraceLogTest` (new) | truncation boundary + marker; `describeError` on a `KoogHttpClientException` clone/cause chain; stage line shape captured through a logback `ListAppender` on `com.aiturbo.trace` |
| `TraceFormatsTest` (new) | message rendering, assistant rendering (text-only, tool-call-only, mixed), tool JSON rendering |
| `ToolJsonRendererTest` (new) | wire shape `type/function/name/description/parameters`, `required=["location"]`, description equals the spec's |
| `ToolSpecTest` (new) | valid file parses; name/description/parameters as in the file; missing/malformed resource (test resource) → `IllegalStateException`; consistency: `GetWeatherTool(..., spec).descriptor.description == spec.description` and `location` is a required parameter |
| `LoggingPromptExecutorTest` (new) | fake `PromptExecutor` delegate; (b) contains `tools_count=1` and the `get_weather` JSON; (c) contains the tool call; the delegate receives the identical `tools` list; empty list → `tools_count=0` warning and no throw; failure → (c) with `error=` and the exception still propagates |
| `LlmTimeZoneResolverTest` (rewrite) | fake executor returning `Message.Assistant(parts = listOf(MessagePart.Text("""{"timezone":"Europe/Paris"}""")))`; fences; `{"timezone":null}`; malformed; executor throws → null; key not configured → executor never called; captured call args: non-empty tools containing `get_weather`, `toolChoice == None` |
| `KoogWeatherAgentTest` (new) | real `AIAgent` + fake executor (tool call, then text) + fake `SimpleTool`: tool executed once, answer returned, exactly one `stage=tool` line with args/result, executor received the registry's non-empty descriptors |
| `WeatherRoutesTest` (modify) | existing 200/400/503 cases plus: (a) line contains the posted body, (e) line contains status 200 and the answer, 400/503 are logged; no status-code regression |
| `ApplicationTest` (modify) | 200/400/404 for `/time` and `/` plus (a)/(e) lines for each, including the 404 outcome |
| `GetWeatherToolTest` (modify) | existing cases with the new `spec` constructor argument; success logs `stage=db saved=true`, `throwOnSave` logs `saved=false` and still answers |
| `RawDeepSeekCallTest` (new) | static guard for FR-01: no file under `src/main/kotlin` contains a manual `"Bearer "` header, and `chat/completions` appears only in `weather/DeepSeekKoogLlm.kt` (Koog factory) and `Application.kt` (log label) |

Existing tests keep their fakes, `MockEngine` usage and frozen clock; only the two constructors that
changed (`LlmTimeZoneResolver`, `GetWeatherTool`) and the route tests' new assertions need edits.

**NFR-06 (backwards compatibility).** See the HTTP table in §API design: identical endpoints, status
codes and DTOs; `users` schema untouched; the answer string for the weather tool is unchanged,
because the DB outcome is logged rather than appended. Route tests pin every documented outcome.

**NFR-07 / NFR-08 (process).** Requirements, design (this document) and plan live in
`docs/features/koog-everything-and-logging/` in that order, each produced by its own stage.

### Configuration changes

| File | Change |
|---|---|
| `src/main/resources/logback.xml` | add `<logger name="com.aiturbo.trace" level="INFO"/>` above `<root>`; pattern and STDOUT appender unchanged |
| `src/main/resources/application.conf` | comment fix only (the DeepSeek block now describes the Koog-only path); no new keys |
| `src/main/kotlin/com/aiturbo/plugins/Routing.kt` | remove `install(CallLogging)` and its imports (replaced by the trace helpers) |
| `build.gradle.kts` | none (no new dependency; `ktor-server-call-logging` becomes unused and may be dropped later) |
| `README.md` | document the Koog-only DeepSeek path, the five-stage log chain, the tool JSON resource and the Postman recipe; remove "DeepSeek LLM over HTTP" wording |

### Compatibility and regression notes

- `TimeZoneResolver` interface, resolver chain order and caching are untouched; `/time` for IANA ids
  and built-in cities never reaches the LLM, exactly as before.
- The only behaviour-visible change on `/time` is the loss of the wire-level JSON mode (D8), which is
  compensated by the unchanged system instruction and the existing lenient parser (R4).
- The `POST /weather` answer text, response shape and 503/400 semantics are unchanged; the tool's
  return value is deliberately not modified (D6).
- `KoogWeatherAgent.answer` signature is unchanged, so `WeatherRoutesTest`'s fake agent and the
  Postman flow keep working.
- Koin's override path (`Application.module(overrideModules)`) is untouched, so tests that build
  their own modules keep working; the new beans (`ToolSpec`, `ToolJsonRenderer`, `LoggingPromptExecutor`)
  live only in `appModules`.
- Removing `CallLogging` removes the old `INFO ... 200 OK: POST /weather` line; the trace lines
  replace it with strictly more information (method, path, query, client, body, status, body).

## Open questions and risks for the planner

| # | Item | Default assumption / mitigation |
|---|---|---|
| R1 | **`tools` on `/time` (FR-02 vs ASM-04/FR-05 acceptance text).** The requirements' FR-02 acceptance criterion says "A time-resolution request contains no `tools` field at all (see ASM-04)" and FR-05 says "for a time-resolution call, the request messages are logged (no tools)". The user's hard instruction says the opposite: every backend→DeepSeek request must carry the non-empty registry, never `[]` | **Design follows the user instruction:** `/time` sends the non-empty array with `toolChoice = None` (D4). The acceptance sentence about `/time` should be read as superseded; if the user prefers no `tools` field on `/time`, the change is a one-line `emptyList()` in the resolver — flag for confirmation during planning |
| R2 | DeepSeek must accept `tool_choice: "none"` together with a non-empty `tools` array, otherwise `/time` fails with 400 → 404 for every LLM-resolved location | OpenAI-compatible providers accept it; if a provider rejects it, drop `toolChoice` from `LLMParams` (behaviour then relies on the prompt alone; a tool-call answer is logged and treated as unresolvable). Verify in the manual smoke test of Flow 2 |
| R3 | `KoogHttpClientException` visibility/type for extracting `status=`/`body=` in `describeError` | Public in `ai.koog.http.client` in 1.2.0; if it is not accessible, fall back to the cause-chain `SimpleName: message` walk (the status still appears when the message carries it). Keep `describeError` unit-tested with a locally built cause chain |
| R4 | Dropping `response_format: json_object` (D8) could make the model answer with prose or fences more often, producing 404s for locations that used to resolve | Mitigated by the unchanged "return ONLY a JSON object" instruction plus fence stripping and tolerant parsing; validate with 2–3 unusual locations (`Kisumu`, `Tula`, `Atlantis`) during the manual run. If the rate degrades, the follow-up is a provider-side structured-output option (DeepSeek `strict` function calling or a beta endpoint), which would be a new requirement |
| R5 | NFR-01's "exactly one entry per chain stage" versus the extra `stage=db` line emitted by the tool | Interpreted as: the five chain stages have exactly one entry each; `stage=db` is a tool-internal detail required by FR-06. If the strictest reading is wanted, the alternative is to fold the DB outcome into the `stage=tool` line, which would require changing the tool's returned string (out of scope) or a logging side channel (over-engineering) — flag for confirmation |
| R6 | Body logging for requests whose body cannot be parsed (malformed JSON) shows no body | `beginTrace()` is called from the StatusPages handler in that case, so the request still gets one (a) line without body plus the (e) 400 line; the raw bytes are deliberately not re-read (D10) |
| R7 | Requests that never reach the routing table (unmatched path, wrong method) produce Ktor's default 404 without trace lines | Out of the documented API surface (ASM-07 targets the app's endpoints). Optional hardening in the plan: a tiny `onCall` fallback that logs an untraced request, if the user wants full coverage |
| R8 | Test capture mechanism for log assertions (logback `ListAppender`) couples tests to logback-classic | Acceptable: logback-classic is already a compile dependency and is what the app ships; the helper lives in one test utility and the trace logger name is a constant in `TraceLog` |
| R9 | `LoggingPromptExecutor.executeStreaming` logs the request but not the frames | Nothing in the application streams; documented limitation, revisit only if a streaming path is added |
| R10 | Startup now fails when `tools/get-weather-tool.json` is missing or invalid | Intentional fail-fast (D5); the resource ships in the jar, and CI/tests cover the loader. If the user prefers a degraded start, fall back to the previous hardcoded description — confirm during planning |

---

# Koog-Everything and Logging — Implementation Plan

Stage: **plan** (`/feature-design`, planner). Inputs: `01-requirements.md`, `02-design.md`.
Output of the next stage: implementation, executed task by task in the order below.

Scope rules for everyone executing this plan:

- Implement exactly what `02-design.md` specifies; do not re-design, add endpoints, or change response shapes.
- Keep all **52 existing tests green**; adapt them only where a constructor or route changes, never dropping a covered case.
- All new tests must be **offline**: fakes, `MockEngine`, frozen clock — no real PostgreSQL, no live DeepSeek.
- Size legend: S ≤ 2 h, M ≤ 4 h, L ≤ 1 developer-day. No task exceeds one developer-day.
- File-path corrections against the design inventory (verified in the repo): the design names
  `weather/KoogWeatherAgent.kt`, but the class `KoogWeatherAgent` lives in
  `src/main/kotlin/com/aiturbo/weather/WeatherAgent.kt` — use the real path. `src/test/resources/`
  does not exist yet; the first task that needs it creates it.

## Task list

| ID | Title | Depends on | Size | Description (design ref) | Files to touch |
|---|---|---|---|---|---|
| T-01 | Correlation id + trace logger foundation | — | M | `CallTrace` (`CoroutineContext.Element`, 8-char id, `newTraceId()`) and the `TraceLog` object on logger `com.aiturbo.trace`: `currentId()`, stage methods `inbound/deepseekRequest/deepseekResponse/deepseekFailure/tool/toolDb/outbound`, `truncate` (4096 chars + `…[truncated, NNNN chars total]`), `describeError` (cause chain, Koog HTTP status/body when accessible). Also the shared logback `ListAppender` test helper reused by every log-asserting test. (C2; NFR-01/NFR-02/NFR-03) | new `src/main/kotlin/com/aiturbo/log/CallTrace.kt`, `src/main/kotlin/com/aiturbo/log/TraceLog.kt`; new `src/test/kotlin/com/aiturbo/TraceLogTest.kt`; new test helper (e.g. `src/test/kotlin/com/aiturbo/LogCapture.kt`) |
| T-02 | Traffic formatters | — | S | Pure single-line renderers `renderMessages`, `renderAssistant`, `renderJson` for the log line, no logging side effects. (C3; FR-04/NFR-03) | new `src/main/kotlin/com/aiturbo/log/TraceFormats.kt`; new `src/test/kotlin/com/aiturbo/TraceFormatsTest.kt` |
| T-03 | Wire-format tool renderer | — | S | `ToolJsonRenderer.toWireJson` / `renderAll` producing the OpenAI wire shape with parameters from Koog's own `OpenAICompatibleToolDescriptorSchemaGenerator`, so the logged schema cannot drift from the request Koog builds. (C9; FR-02/FR-04) | new `src/main/kotlin/com/aiturbo/tools/ToolJsonRenderer.kt`; new `src/test/kotlin/com/aiturbo/ToolJsonRendererTest.kt` |
| T-04 | Logging executor decorator | T-01, T-02, T-03 | M | `LoggingPromptExecutor(delegate, endpoint, toolJsonRenderer) : PromptExecutor()` — the single choke point. Logs `stage=deepseek-request` (endpoint, model, messages, `tools_count`, tools, `tool_choice`) before delegating untouched; `stage=deepseek-response` (text, tool calls) or `…error=` on failure, rethrowing; `CancellationException` rethrown silently; WARN duplicate when tools are empty, never throws; `executeStreaming` logs the request only; `close()` delegates. (C4; FR-01/FR-02/FR-04/FR-05) | new `src/main/kotlin/com/aiturbo/log/LoggingPromptExecutor.kt`; new `src/test/kotlin/com/aiturbo/LoggingPromptExecutorTest.kt` |
| T-05 | `LlmTimeZoneResolver` rewritten on Koog | T-04 | M | Remove the last raw DeepSeek caller: constructor becomes `(promptExecutor, model, toolDescriptorsProvider, apiKeyConfigured = true)`; prompt with `temperature=0.0`, `maxTokens=64`, `toolChoice = None`, unchanged system instruction; call `promptExecutor.execute(prompt, model, toolDescriptorsProvider())`; text parts concatenated then `parseZoneContent` (extracted `internal`); exceptions → null, `CancellationException` rethrown; blank key → no call. Raw DTOs and Ktor client usage deleted. (C5; FR-01) | modify `src/main/kotlin/com/aiturbo/time/LlmTimeZoneResolver.kt`; rewrite `src/test/kotlin/com/aiturbo/LlmTimeZoneResolverTest.kt` |
| T-06 | Tool spec resource + loader | — | S | `tools/get-weather-tool.json` under `src/main/resources/` (exact content from design §Data model) plus `ToolSpec` and `ToolSpecLoader.load()`: reads the classpath resource, validates non-blank name/description, throws `IllegalStateException` naming the path on missing/invalid, logs one INFO line. (C8; FR-08) | new `src/main/resources/tools/get-weather-tool.json`; new `src/main/kotlin/com/aiturbo/tools/ToolSpec.kt`; new `src/test/kotlin/com/aiturbo/ToolSpecTest.kt`; new fixture `src/test/resources/tools/invalid-tool.json` (creates `src/test/resources/`) |
| T-07 | `GetWeatherTool` spec-driven + DB outcome log | T-01, T-06 | M | Constructor gains `spec: ToolSpec`; `SimpleTool.name`/`description` come from the file (FR-09); the parameter schema still comes from `GetWeatherArgs`. Log exactly one `stage=db` line per insert attempt (`saved=true id=<n>` / `saved=false reason=…`); returned string unchanged. (C7; FR-06/FR-09) | modify `src/main/kotlin/com/aiturbo/weather/GetWeatherTool.kt`; modify `src/test/kotlin/com/aiturbo/GetWeatherToolTest.kt` |
| T-08 | Koin wiring | T-04, T-05, T-07 | M | Apply the design's `appModules` graph: `ToolSpec` bean (`createdAtStart = true`), `ToolJsonRenderer`, `LoggingPromptExecutor` as the only `PromptExecutor`, `GetWeatherTool(..., spec)`, resolver with the lazy `toolDescriptorsProvider = { get<ToolRegistry>().tools.map { it.descriptor } }` and `apiKeyConfigured = deepseek.apiKey.isNotBlank()`; blank key still yields the 503 `WeatherAgent`. (C10; FR-01/FR-02/FR-09) | modify `src/main/kotlin/com/aiturbo/Application.kt`; add wiring assertions in `src/test/kotlin/com/aiturbo/ApplicationTest.kt` or a new `AppModulesTest.kt` |
| T-09 | Weather agent `stage=tool` logging | T-01 | M | Add exactly one `stage=tool` line per tool invocation in the strategy, after `executeTools`, with `tool=`, `args=`, `result=`, `is_error=`; `req=` id from the coroutine context (`req=-` when absent). Strategy shape (max rounds, non-convergence warn, final text join) unchanged. (C6; FR-06) | modify `src/main/kotlin/com/aiturbo/weather/WeatherAgent.kt` (`KoogWeatherAgent`); new `src/test/kotlin/com/aiturbo/KoogWeatherAgentTest.kt` |
| T-10 | `RequestTracing` on the Ktor side | T-01 | M | `ApplicationCall.beginTrace(body)`, `traceOrNull()`, `respondTraced(body, status)` (idempotent inbound line with method/path/query/client/body; outbound line with status + body; logging wrapped so it can never change the response). Trace stored in `call.attributes`; id in the coroutine context. (C1; FR-03/FR-07/NFR-04) | new `src/main/kotlin/com/aiturbo/plugins/RequestTracing.kt`; new `src/test/kotlin/com/aiturbo/RequestTracingTest.kt` |
| T-11 | Route rewiring | T-08, T-09, T-10 | M | Remove `install(CallLogging)`; replace every `call.respond` (routes and the four StatusPages handlers) with `respondTraced`; StatusPages creates a missing trace first; `beginTrace` after `receive<WeatherRequest>()` with the re-serialized body; wrap `agent.answer` and `resolver.resolve` in `withContext(trace)`. Status codes unchanged. (C1; FR-03/FR-07/NFR-04/NFR-06) | modify `src/main/kotlin/com/aiturbo/plugins/Routing.kt`, `src/main/kotlin/com/aiturbo/plugins/WeatherRouting.kt`; modify `src/test/kotlin/com/aiturbo/WeatherRoutesTest.kt`, `src/test/kotlin/com/aiturbo/ApplicationTest.kt` |
| T-12 | Offline five-stage chain integration test | T-11 (and all above) | L | New end-to-end offline test: boot the app with test modules (fake `PromptExecutor` wrapped by `LoggingPromptExecutor`, fake DB/weather clients, real `ToolRegistry`/`GetWeatherTool`/resolver chain) and assert the whole chain contract: order, one id, non-empty tools on both paths, secrets absent, truncation, statuses unchanged. (NFR-01/NFR-02/NFR-03/NFR-04/NFR-06) | new `src/test/kotlin/com/aiturbo/TraceChainIntegrationTest.kt` |
| T-13 | Static guard test for the Koog-only rule | T-05 | S | `RawDeepSeekCallTest`: scan `src/main/kotlin/**` — no `"Bearer "` anywhere; `chat/completions` only in `weather/DeepSeekKoogLlm.kt` and `Application.kt`; failure output names the offending file. (FR-01) | new `src/test/kotlin/com/aiturbo/RawDeepSeekCallTest.kt` |
| T-14 | Full-suite regression pass | T-12, T-13 (all) | S | Run the whole suite and account for it: `./gradlew test` green offline; the 52 baseline tests all still present (adapted ones retain their original cases); all new test classes listed; no test touches DB/network; `./gradlew build` succeeds. (NFR-05/NFR-06) | no production change expected; test fixes only if a failure is found |
| T-15 | Shipped configuration | — | S | Add `<logger name="com.aiturbo.trace" level="INFO"/>` to `logback.xml` (pattern and STDOUT untouched); fix the stale DeepSeek comment in `application.conf` to describe the Koog-only path (no new keys). (NFR-01, FR-11) | modify `src/main/resources/logback.xml`, `src/main/resources/application.conf` |
| T-16 | README | T-15, T-11 | M | Document the Koog-only DeepSeek path, the five-stage log chain (with a sample and the `stage=db` explanation), the tool JSON resource as the description source, and the Postman recipe verbatim from `01-requirements.md` (both requests + SQL/history check); remove "DeepSeek LLM over HTTP" wording; link the three pipeline documents. (FR-10/FR-11/NFR-07/NFR-08) | modify `README.md` |

## Work order and milestones

Execution batches (tasks inside a batch touch disjoint files and can run in parallel; the developer
follows the batches top to bottom):

| Batch | Tasks (parallel) | Notes |
|---|---|---|
| 1 | T-01, T-02, T-03 | All new files; no production wiring touched. T-02/T-03 are independent of T-01. |
| 2 | T-04 | Needs the logger, formatters and renderer from batch 1. |
| 3 | T-05, T-06 | Resolver rewrite (the only raw DeepSeek caller disappears) and the tool JSON resource + loader. T-06 has no dependencies and may be pulled earlier. |
| 4 | T-07 | Needs `ToolSpec` (T-06) and `TraceLog.toolDb` (T-01). |
| 5 | T-08, T-09, T-10 | Wiring, agent logging and request tracing are independent of each other (T-08 needs T-04/T-05/T-07). |
| 6 | T-11 | Routes are the last production file set; needs the helpers and the wired graph. |
| 7 | T-12, T-13 | The two new guard/integration suites; T-13 can run as soon as T-05 is done. |
| 8 | T-14, T-15 | Regression pass and shipped configuration; independent. |
| 9 | T-16 | README last, against the frozen behavior. |

Milestones (checkpoints, each with a verifiable exit):

- **M-01 — Foundations ready** (after batch 2). `CallTrace`/`TraceLog`/`TraceFormats`/`ToolJsonRenderer`/`LoggingPromptExecutor` exist with their unit tests green; no production behavior changed yet; the 52 existing tests still pass.
- **M-02 — Koog-only LLM path in code** (after batch 3). `LlmTimeZoneResolver` no longer performs raw HTTP; the decorator is the single executor path; the tool JSON resource loads; `RawDeepSeekCallTest` (T-13) passes.
- **M-03 — Feature wired end-to-end** (after batch 6). `appModules` resolves without cycles; routes emit `stage=inbound`/`stage=outbound`; the agent emits `stage=tool`; the tool emits `stage=db`; `./gradlew build` green.
- **M-04 — Verified offline** (after batch 8). T-12, T-13, T-14 green: chain order, single correlation id, non-empty tools on both paths, no secrets, statuses unchanged; the 52 baseline tests accounted for; the whole suite runs without DB/LLM.
- **M-05 — Documentation and manual smoke** (after batch 9). T-15/T-16 done. Then the user/developer runs the manual checklist on a live server (key + DB present): Postman Request 1 → 200, one new `users` row, `GET /weather/history?limit=5` returns it; console shows the five-stage chain in order with `tools_count=1` and the `get_weather` definition and no secrets; `GET /time?location=<not in the built-in map>` (try `Kisumu`, `Tula`, `Atlantis`) resolves via Koog (`tool_choice=none`) or returns 404; grep of the log for the key/password → 0 hits.

## Acceptance criteria

### T-01 — Correlation id + trace logger foundation

- `newTraceId()` returns an 8-character id; `CallTrace` works as a coroutine context element: `TraceLog.currentId()` returns the id inside `withContext(CallTrace(id))` and `null` outside.
- `truncate`: 4095- and 4096-character inputs are unchanged; 4097 characters produce exactly 4096 characters plus the marker `…[truncated, 4097 chars total]`; the marker format is asserted literally.
- `describeError` renders the cause chain as `SimpleName: message` (tested with a locally built nested chain); when the shipped Koog exception type is used, `status=`/`body=` (truncated) are appended — if that type is not accessible, the cause-chain-only fallback ships and the report says so (risk R-06).
- Each stage method emits exactly one line shaped `req=<id> stage=<marker> <key=value …>` on logger `com.aiturbo.trace`; a `null` id renders `req=-`, never `null`; captured via the shared `ListAppender` helper.
- No call path passes a `DeepseekConfig`, `DbConfig`, header map or raw key to `TraceLog` (reviewer check of the signatures).
- `./gradlew test` green (52 baseline tests unaffected).

### T-02 — Traffic formatters

- `renderMessages` renders a two-message prompt as one line `[system: "…", user: "…"]` (no newlines).
- `renderAssistant` covers three cases: text-only (`text="…"`), tool-call-only (`tool_calls=[{"name":…,"args":…}]`), and mixed; `args` is the JSON string from `MessagePart.Tool.Call`.
- `renderJson` returns compact one-line JSON for a `JsonObject`.
- All formatters are pure (no logger access) and all outputs are single-line for multi-line inputs.

### T-03 — Wire-format tool renderer

- `toWireJson` yields exactly `{"type":"function","function":{"name":…,"description":…,"parameters":…}}`; `parameters` comes from `OpenAICompatibleToolDescriptorSchemaGenerator`.
- For a descriptor with a required string `location` property, the rendered schema contains `"required":["location"]` and a `location` property.
- `renderAll(emptyList())` returns `"[]"`; `renderAll(list)` is one line; rendering never mutates or reorders the input list.
- The test builds the descriptor from a real tool (registry or anonymous `SimpleTool`) and asserts name/description round-trip.

### T-04 — Logging executor decorator

- For a delegated `execute`, exactly one `stage=deepseek-request` line is emitted **before** the delegate is called, containing `endpoint=`, `model=<id>`, the rendered messages, `tools_count=<n>`, the rendered tools and `tool_choice=<value|->`.
- The delegate receives the identical `tools` list (same contents and order; the test's fake records the argument and asserts equality) — the decorator never adds, filters or reorders.
- Empty tools: `tools_count=0` on the line plus one WARN-level duplicate; the call proceeds, no exception (NFR-04, D3).
- Success: exactly one `stage=deepseek-response` line with `text=` and `tool_calls=[…]` (empty list for a text-only reply).
- Failure: one `stage=deepseek-response … error=<describeError>` line and the original exception rethrown; `CancellationException` rethrown with no response line.
- `executeStreaming` logs one request line carrying `streaming=true` and forwards the frames unchanged; `close()` delegates to the wrapped executor.
- All body-like fields are truncated per T-01. Tests use a fake delegate only — no network.

### T-05 — `LlmTimeZoneResolver` rewritten on Koog

- The file contains no Ktor client usage, no `Authorization`/`Bearer`, no `chat/completions`, and the raw DTOs (`ChatMessage`, `ChatCompletionRequest`, `ChatCompletionResponse`, `Choice`, `ResponseFormat`) are deleted.
- Constructor is `(promptExecutor, model, toolDescriptorsProvider, apiKeyConfigured = true)`; `TimeZoneResolver` interface and `resolve` semantics unchanged.
- `apiKeyConfigured = false` → warn on the class logger, return `null`, executor call count is 0.
- The fake executor records the prompt: `temperature=0.0`, `maxTokens=64`, `toolChoice == LLMParams.ToolChoice.None`, the unchanged system instruction, user message `Location: <location>`.
- The executor receives the non-empty descriptor list from `toolDescriptorsProvider()` (the fake asserts a `get_weather` descriptor is present); the provider is invoked lazily and its result reused (`by lazy`).
- `parseZoneContent` (now `internal`) is unit-tested directly: plain JSON, ```` ```json ```` fences, `{"timezone":null}`, malformed text, invalid IANA id → `null`; text parts are concatenated before parsing.
- An answer containing only a tool call is not executed → `null`; a non-cancellation exception → warn + `null`; `CancellationException` rethrown.
- Every case of the old `LlmTimeZoneResolverTest` still exists, adapted to the fake executor.

### T-06 — Tool spec resource + loader

- `src/main/resources/tools/get-weather-tool.json` parses as JSON with `name = "get_weather"`, a non-blank `description`, `parameters.properties.location` present and `required = ["location"]` (content per design §Data model).
- `ToolSpecLoader.load()` decodes the shipped resource and returns the same values.
- The malformed fixture (`src/test/resources/tools/invalid-tool.json`) and a nonexistent path both throw `IllegalStateException` whose message names the resource path and carries the cause; blank name/description are rejected the same way.
- Loading logs exactly one INFO line on the loader's own class logger.

### T-07 — `GetWeatherTool` spec-driven + DB outcome log

- Constructor accepts `spec: ToolSpec`; `descriptor.name == spec.name` and `descriptor.description == spec.description` (FR-09), while the parameter schema is still generated from `GetWeatherArgs` (`location` required, `@LLMDescription` unchanged).
- The returned string is byte-for-byte identical to the current implementation for the same inputs (no suffix added).
- Successful save with id ≥ 0 → exactly one `stage=db tool=get_weather saved=true id=<n>` line; negative id (unique conflict) → `saved=false` with a reason; repository throwing → `saved=false reason=<message>` and the answer is still produced.
- Existing `GetWeatherToolTest` cases are all retained (only the constructor argument is added); new assertions cover the two `stage=db` outcomes.
- Consistency check: the shipped resource's `description` equals the descriptor description and `location` appears as a required parameter in the generated schema.

### T-08 — Koin wiring

- `appModules` binds `ToolSpec` via `ToolSpecLoader.load()` with `createdAtStart = true`, `ToolJsonRenderer`, `LoggingPromptExecutor` as the only `PromptExecutor` (delegate = `deepseekPromptExecutor(deepseek)`, endpoint = `<baseUrl>/chat/completions`), `GetWeatherTool` with the spec, and the resolver with `toolDescriptorsProvider = { get<ToolRegistry>().tools.map { it.descriptor } }` and `apiKeyConfigured = deepseek.apiKey.isNotBlank()`.
- Building the graph offline with test configs and resolving `PromptExecutor`, `TimeZoneResolver`, `ToolRegistry`, `GetWeatherTool`, `ToolSpec` and `WeatherAgent` (blank key) raises no circular-dependency exception and performs zero network calls; the resolved `PromptExecutor` is a `LoggingPromptExecutor`.
- Blank key still yields the 503-throwing `WeatherAgent` lambda; `Application.module(overrideModules = …)` behavior is untouched and the existing `ApplicationTest` stays green.
- The descriptor provider is lazy: no `ToolRegistry` resolution happens while the resolver bean is constructed (the cycle break of D11) — asserted by resolving the resolver alone in the wiring test.

### T-09 — Weather agent `stage=tool` logging

- `KoogWeatherAgentTest` (real `AIAgent`, fake `PromptExecutor` returning a tool call then text, fake `SimpleTool`): the tool executes exactly once, the answer is returned, and exactly one `stage=tool` line with `tool=`, `args=`, `result=`, `is_error=false` is captured.
- An error result yields `is_error=true`; args and result are truncated per T-01.
- Run inside `withContext(CallTrace(id))` the line carries `req=<id>`; run without it, `req=-` — no exception in either case.
- The fake executor records a non-empty descriptor list containing the registry's tool (the agent path can never send `tools: []`).
- The non-convergence warning and final-text join are unchanged (existing behavior assertions kept).

### T-10 — `RequestTracing` plugin

- `beginTrace(body)` logs exactly one `stage=inbound` line with `method=`, `path=`, `query=`, `client=` (`remoteHost:remotePort`) and `body=` when provided, stores the trace in `call.attributes`, and is idempotent (a second call adds no line and returns the same trace).
- `traceOrNull()` is `null` before `beginTrace` and non-null after.
- `respondTraced(body, status)` logs exactly one `stage=outbound` line with `status=` and the serialized body, then responds with the identical object and status (test with `testApplication`).
- A failure while serializing for the log does not change the response: the client still receives the intended status and body (NFR-04); the response object is never mutated.
- Body fields are truncated; the id on both lines is the same.

### T-11 — Route rewiring

- `install(CallLogging)` and its import are gone; captured test output contains no `… 200 OK: POST /weather` style line from the old plugin.
- Every `call.respond` in `Routing.kt`, `WeatherRouting.kt` and the four StatusPages handlers is replaced by `respondTraced`; StatusPages handlers call `beginTrace()` when the trace is missing.
- `POST /weather` logs the inbound body as `{"message":"…"}` re-serialized from the DTO; blank message → 400 with only `stage=inbound` and `stage=outbound`, zero agent calls.
- `agent.answer` and `resolver.resolve` run inside `withContext(trace)` (the test's fake records the context element if needed).
- `WeatherRoutesTest`/`ApplicationTest`: every documented status unchanged (200/400/503 for `/weather`; 200/400/404 for `/time`; 200 for `/`; 200/400 for `/weather/history`); a 200 response logs `status=200` and the body; 400/404/503 responses are logged with their `ErrorResponse` body; malformed JSON body → inbound line without body + outbound 400; all lines of one request share one `req=`.
- Tests stay offline (fake agent/repository, frozen clock).

### T-12 — Offline five-stage chain integration test

- One `POST /weather` (weather question) against test modules produces exactly one `stage=inbound`, one `stage=deepseek-request` per LLM round-trip (two for the normal tool loop), one matching `stage=deepseek-response` per round-trip, one `stage=tool`, at most one `stage=db`, and one `stage=outbound` — in that order, all sharing one `req=` id.
- The `stage=deepseek-request` line for the weather flow has `tools_count >= 1` and a `get_weather` function entry whose `description` equals the shipped resource file's description.
- `GET /time?location=<not in the built-in map>` through the real resolver chain produces `inbound`, `deepseek-request` (`tool_choice=none`, `tools_count >= 1`), `deepseek-response`, `outbound`; a `{"timezone":null}` reply → 404; no `stage=tool` line appears.
- No captured line contains `"tools":[]` or `tools_count=0`.
- Blank API key: `/time` → 404 and `/weather` → 503 with no DeepSeek request/response lines.
- No captured line contains the test's API-key or database-password fixture values; every body-like field is ≤ 4096 characters plus the truncation marker.
- The test runs with fakes only (no DB, no network) and passes under `./gradlew test`.

### T-13 — Static guard test for the Koog-only rule

- Scanning `src/main/kotlin/**`: zero occurrences of `"Bearer "`; `chat/completions` appears only in `weather/DeepSeekKoogLlm.kt` and `Application.kt`.
- On violation the test fails and prints the offending file path(s) and line numbers.

### T-14 — Full-suite regression pass

- `./gradlew test` exits 0 with no database and no API key configured; `./gradlew build` succeeds.
- The run report accounts for all 52 baseline tests: each either unchanged or adapted with its original scenario still asserted; no case deleted without an equivalent replacement.
- All new test classes (T-01…T-13) are present and green; a spot check confirms none opens a socket or a JDBC connection.

### T-15 — Shipped configuration

- `logback.xml` contains `<logger name="com.aiturbo.trace" level="INFO"/>` above `<root>`; the pattern, appender and root level are unchanged.
- `application.conf` DeepSeek comment describes the Koog-only path; the stale "over HTTP" phrasing is gone; no new configuration keys.
- `./gradlew test` unaffected; the manual smoke (M-05) shows the chain with the shipped configuration and requires no logback edit.

### T-16 — README

- README documents: the Koog-only DeepSeek path (no production raw HTTP caller), the five-stage chain with a sample line set (noting the extra `stage=db` tool-internal line), the tool JSON resource and that it drives the name/description sent to DeepSeek, and the Postman recipe verbatim from `01-requirements.md` including the `psql`/history check.
- No stale statement contradicting the shipped behavior (review of README vs the three pipeline docs vs the code finds none); the pipeline documents are linked.

## Traceability matrix

Every FR and NFR from `01-requirements.md` maps to at least one task. **All FRs/NFRs covered.**

| Requirement | Covered by |
|---|---|
| FR-01 (all DeepSeek traffic via Koog) | T-04, T-05, T-08, T-13 |
| FR-02 (never an empty `tools` array; tool users send `get_weather`) | T-03, T-04, T-05, T-07, T-09, T-12 |
| FR-03 (every inbound request logged incl. body) | T-01, T-10, T-11, T-12 |
| FR-04 (outbound DeepSeek request logged: model, messages, tools) | T-02, T-03, T-04, T-05, T-12 |
| FR-05 (DeepSeek response logged incl. tool calls and failures) | T-04, T-05, T-11, T-12 |
| FR-06 (tool invocation + DB outcome logged) | T-01, T-07, T-09, T-12 |
| FR-07 (every HTTP response logged with status and body) | T-10, T-11, T-12 |
| FR-08 (JSON file describing the tool under `src/main/resources/`) | T-06, T-08, T-12 |
| FR-09 (description sent to DeepSeek matches the file) | T-06, T-07, T-08, T-12 |
| FR-10 (Postman recipe) | T-16 (mapped verbatim); verified live at M-05 |
| FR-11 (README updated, stale statements removed) | T-15, T-16 |
| NFR-01 (five ordered entries, one id, shipped config) | T-01, T-04, T-09, T-10, T-11, T-12, T-15 |
| NFR-02 (no secrets in logs) | T-04 (inputs exclude secrets), T-12 (absence assertion), T-16 (documented) |
| NFR-03 (bodies ≤ 4096 chars + marker) | T-01 (mechanism), T-04/T-09/T-10/T-11 (callers), T-12 (assertion) |
| NFR-04 (logging never changes API behavior) | T-05 (null/404 preserved), T-10 (wrapped logging), T-11 (statuses pinned), T-12, T-14 |
| NFR-05 (offline tests, 52 green, new coverage offline) | T-14 (accounting) + every task's test acceptance criteria |
| NFR-06 (endpoints/status codes/shapes unchanged) | T-04, T-05, T-11 (route tests), T-12, T-14 |
| NFR-07 (pipeline artifacts exist in order) | produced by the running pipeline; T-16 (consistency check); feature DoD |
| NFR-08 (dedicated stage per artifact) | `01`/`02`/`03` each identify their stage; T-16 references them; feature DoD |

## Risk register

| ID | Source | Risk | Likelihood | Impact | Mitigation | Owner / verifier |
|---|---|---|---|---|---|---|
| R-01 | architect R1: FR-02/FR-05 text says `/time` sends no `tools` field, conflicting with the user's non-empty-tools rule | `/time` requests send `tools` + `toolChoice=none` (design D4); the requirements sentence is superseded | Med | Med | Implement D4 exactly; T-12 pins `tool_choice=none` and `tools_count>=1` on the `/time` line; document the supersession in README (T-16); if the user confirms the opposite at M-05, the revert is one line (`emptyList()` in T-05) plus test updates | Developer implements; reviewer checks against D4; user confirms before M-04 closes |
| R-02 | architect R2: DeepSeek may reject `tool_choice: "none"` with a non-empty `tools` array | Every LLM-resolved `/time` would fail with HTTP 400 → 404 | Low | High | Offline tests cannot see the provider, so the gate is the M-05 manual smoke (`Kisumu`, `Tula`); documented fallback if rejected: drop `toolChoice` from `LLMParams` and rely on the system instruction (a tool-call answer is logged and treated as unresolvable → 404, behavior preserved) | User/developer at M-05; tester verifies the parameter is sent as specified offline |
| R-03 | architect R4: dropping `response_format: json_object` (design D8) | Model answers with prose/fences more often → 404s for locations that used to resolve | Med | Med | Unchanged "return ONLY the JSON" instruction; fence stripping and tolerant parsing kept and unit-tested (T-05); M-05 smoke over three unusual locations; if the resolve rate degrades, a provider structured-output option is a new requirement (out of scope here) | User at M-05; tester pins parser cases |
| R-04 | architect R5: strict "exactly one entry per stage" reading vs the extra `stage=db` line | NFR-01 read literally would be violated by the tool-internal DB line | High (interpretation) | Low | Interpretation applied: exactly one entry per each of the five chain stages; `stage=db` is the FR-06 tool-internal detail (design D6). T-12 asserts one line per chain marker and allows exactly one `stage=db`; README explains it. If the user demands literally five lines: fold the DB outcome into `stage=tool` (requires changing the tool output — out of scope) or drop `stage=db` (loses FR-06's DB outcome) — needs an explicit user decision | Reviewer verifies T-12 assertions; user confirms at M-05 |
| R-05 | architect R10: startup fails fast when the tool JSON is missing/invalid | A packaging mistake prevents the service from starting | Low | High | Intentional (design D5); the resource ships in the jar; `ToolSpecTest` covers malformed/missing and the error names the path (T-06); a degraded-start fallback is a one-line change but must not be implemented without user confirmation | Developer; user confirms fail-fast is acceptable at M-05 |
| R-06 | architect R3: `KoogHttpClientException` visibility for `status=`/`body=` extraction | Status/body missing from failure lines (FR-05 quality) | Med | Low | Use the public type if it compiles; otherwise ship the cause-chain-only fallback (`SimpleName: message`); `TraceLogTest` covers whichever branch ships and the report says which | Developer; tester |
| R-07 | integration: Koin cycle or `createdAtStart` ordering breaks only the production graph (tests override modules) | 500/startup failure not caught by the existing suite | Med | High | Lazy `toolDescriptorsProvider` is the designed cycle break (D11); T-08 includes an offline graph-resolution test; T-12 boots modules mirroring the production shape; `createdAtStart` failure surfaces as a clear `IllegalStateException` (T-06) | Developer; tester |
| R-08 | unknown-unknown: design's verified assumption that Koog runs the strategy in the caller's coroutine context could fail on the shipped artifact | Chain lines lose `req=` linkage (NFR-01) | Low | High | T-09 asserts the id reaches the strategy; T-12 asserts all five lines share one id. If it fails, escalate to the architect (the design would need an explicit id handoff) — do not improvise | Tester; escalation to architect |
| R-09 | test gap: log-capture helper (ListAppender) flakiness or cross-test ordering assumptions | Flaky, order-dependent tests | Med | Low | One shared helper (T-01); synchronous appender; filter captured lines by `req=` id; assert order only within one request's lines, never globally | Tester |
| R-10 | test gap: the real Postman + DB + live LLM path cannot be exercised offline | FR-10 and the live chain remain unverified by CI | Certain (by nature) | Med | Explicit M-05 manual checklist with exact commands and expected output; everything testable offline is pinned by T-12/T-14; the checklist is part of the feature DoD | User (manual smoke), developer supports |
| R-11 | integration: constructor/route changes break existing tests, tempting deletions | Coverage loss against NFR-05 | High | Med | T-05/T-07/T-11 adapt the affected tests keeping every original case; T-14 accounts for all 52 baseline tests and forbids silent deletions | Developer; tester verifies the count |
| R-12 | open questions: OQ-01/OQ-02 resolved by the risky assumptions ASM-01/ASM-02 | Wrong scope (city data requested, or the file should be documentation-only) | Low | Med | Plan follows design D5 (file for the tool, runtime source of truth); changes are localized to T-06/T-07; flagged for user confirmation early; if ASM-02 flips, FR-09 and the spec wiring are dropped and the tool keeps a hardcoded description | User confirms; planner flag recorded here |
| R-13 | test gap: the logged tools JSON is our rendering, not the byte-exact wire body Koog serializes | Drift between log and reality if Koog's serialization changes | Low | Med | The renderer reuses Koog's own `OpenAICompatibleToolDescriptorSchemaGenerator` and mirrors `toOpenAIChatTool` (T-03); shape pinned by test; residual drift accepted and documented in T-16 | Reviewer |

## Definition of Done (feature level)

1. All 16 tasks are complete; each task's acceptance criteria above are verified by the tester (offline).
2. `./gradlew test` is green with **no real database and no live LLM**; all **52 pre-existing tests are
   still present and green** (adapted only where constructors/routes changed, without losing a covered
   case), and every new component has new offline coverage (T-01…T-13). `./gradlew build` succeeds.
3. The traceability matrix above is satisfied: every FR-01…FR-11 and NFR-01…NFR-08 maps to at least
   one verified task.
4. Manual acceptance on a running local server (M-05 checklist): Postman Request 1 → 200 with a
   natural-language answer, exactly one new `users` row, and `GET /weather/history?limit=5` returns
   that row; the console shows the five-stage chain in order sharing one correlation id, with a
   non-empty `tools` array containing `get_weather` and zero secret values; `GET /time?location=` for
   a non-built-in place resolves through Koog or returns 404 as documented.
5. `docs/features/koog-everything-and-logging/` contains `01-requirements.md`, `02-design.md` and
   `03-plan.md`, each identifying its stage and consistent with the shipped behavior (NFR-07/NFR-08);
   README updated per FR-10/FR-11.
6. The open interpretation flags R-01 (`tools` on `/time`) and R-04 (`stage=db` line) are confirmed
   by the user or explicitly accepted as implemented.
