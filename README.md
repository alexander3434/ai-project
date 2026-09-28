# Ai-Turbo — Time API + Weather & Fueling Agents

A Kotlin server built with **Ktor** + **Kodein** + **Exposed** that returns the current local
**date and time** for any location, resolved in real time.

It also runs **Koog** (JetBrains) LLM agents with the **DeepSeek** model (or a **local Ollama**
model, selected per request) and two tools:

- `get_weather` — when the user asks about the weather, the agent calls the tool, the tool fetches
  the current weather from Open-Meteo and records the request in a **PostgreSQL** database
  (the `users` table — the id increments on every request);
- `find_fueling` — when the question carries a fueling order GUID (e.g. «Найди данные по проливу
  для заказа 61574131-…»), the agent calls the tool, which reads the order from the read-only
  **stage PostgreSQL** (`fueling` database, `fuelings` / `fuelings_archive` / `fuelings_drop`
  tables) and answers in plain language (see [Fueling order lookup](#fueling-order-lookup-post-fueling)).

## How it works

`GET /time?location=Moscow` resolves the location into an IANA time zone in three steps:

1. **Direct match** — if the value is already an IANA id (`Europe/Paris`), it is used as is.
2. **Built-in map** — ~130 well-known cities are resolved offline, without any HTTP calls.
3. **DeepSeek via Koog** — anything else (e.g. `location=Canberra` or `location=Kisumu`)
   is sent to the DeepSeek API through the same Koog prompt executor the weather agent uses
   (`tool_choice=none`), and the model returns the time zone. Results are cached in memory.

`POST /weather` sends the user's message to the Koog agent. The agent calls the `get_weather`
tool when asked about the weather; the tool resolves the location into a time zone (the same
resolver as above), fetches the current weather from **Open-Meteo** (free, no API key) and
writes a row into the database:

| Column | Value |
|---|---|
| `id` | auto-incremented by the database sequence on every request |
| `data` | local date and time in the region, e.g. `2026-09-14 19:45:03` |
| `time` | current local time in the region, e.g. `19:45:03` |
| `created_at` | the moment the user asked (database `now()` default) |

If two requests land within the same second, the `UNIQUE` constraint on `data` is resolved
by retrying the insert with the timestamp shifted by one second (up to 3 attempts; a retry
consumes one extra sequence value).

Dependency injection is done with **Kodein-DI** (one container, per-slice modules) and the
database access goes through **Exposed**; the clock is injectable so tests run with frozen time.

## API

| Endpoint | Description |
|---|---|
| `GET /` | Service info |
| `GET /time?location=<place>` | Current date and time in that place |
| `POST /weather` | Ask the LLM agent anything (weather questions trigger the database recording tool) |
| `POST /fueling` | Ask the LLM agent about a fueling order (the question must carry the order GUID) |
| `GET /weather/history?limit=<n>` | The most recent weather request records from the database |

### Model selection (`model` field)

`POST /weather` and `POST /fueling` accept an optional `model` string that chooses the provider for
that request; the response is unchanged either way (the serving provider is visible in the log, not
in the response body):

| `model` value | Provider | Notes |
|---|---|---|
| absent, `""`, whitespace | DeepSeek | today's behavior (default) |
| `deepseek` (any case, surrounding whitespace ignored) | DeepSeek | existing OpenAI-compatible path |
| `local` (any case, surrounding whitespace ignored) | local Ollama | the configured `ollama.model`, no DeepSeek key needed |
| anything else non-blank | — | `400 {"error":"Field 'model' must be one of: local, deepseek"}`, no LLM and no tool call |

Example:

```
POST http://localhost:8080/weather
Content-Type: application/json

{"message": "Какая сейчас погода в Москве?", "model": "local"}
```

Example request:

```
GET http://localhost:8080/time?location=Moscow
```

Example response:

```json
{
    "location": "Moscow",
    "timezone": "Europe/Moscow",
    "date": "2026-09-09",
    "time": "18:45:03.123456",
    "dateTime": "2026-09-09T18:45:03.123456+03:00",
    "utcOffset": "+03:00",
    "dayOfWeek": "Wednesday"
}
```

Weather request:

```
POST http://localhost:8080/weather
Content-Type: application/json

{"message": "Какая сейчас погода в Москве?"}
```

Weather response:

```json
{
    "message": "Какая сейчас погода в Москве?",
    "answer": "Сейчас в Москве +15.4°C, облачно, влажность 66%. Местное время — 14 сентября, 19:45."
}
```

Fueling request:

```
POST http://localhost:8080/fueling
Content-Type: application/json

{"message": "Найди данные по проливу для заказа 61574131-999F-48C9-93AE-3EAA68562177"}
```

Fueling response:

```json
{
    "message": "Найди данные по проливу для заказа 61574131-999F-48C9-93AE-3EAA68562177",
    "answer": "Заказ 61574131-999F-48C9-93AE-3EAA68562177 найден в архиве (fuelings_archive): статус SUCCESS, сумма 45.00, топливо AI-95, колонка 12, пистолет 3. По заказу 2 партнёрских события и 1 токен belka; обращений в поддержку нет."
}
```

Error codes:

| Code | Meaning |
|---|---|
| `400` | `location` / `message` query parameter is missing, `model` is not `local`/`deepseek`, or the request body is invalid |
| `404` | The location could not be resolved to a time zone |
| `500` | Internal server error |
| `503` | The database or the LLM agent (DeepSeek) is unavailable |

`POST /fueling` keeps the same contract: a blank/absent `message` is `400`, a missing DeepSeek key
is `503`, and every lookup outcome (found, not found, invalid GUID, stage database unavailable) is a
`200` with a plain-language answer — never a `404` or a `500`.

## Logging — the request chain

Every request is traced end to end by one correlation id (`req=<8 hex chars>`) on the dedicated
logger `com.aiturbo.trace` (configured at INFO in `logback.xml`, so no setup is needed). One
`POST /weather` writes these lines, in order:

```
req=5820350b stage=inbound method=POST path=/weather query=- client=127.0.0.1:52344 body={"message":"Какая сейчас погода в Москве?"}
req=5820350b stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-flash tool_choice=- tools_count=1 tools=[{"type":"function","function":{"name":"get_weather","description":"…","parameters":{"type":"object","properties":{"location":{"description":"…","type":"string"}},"required":["location"]}}}] messages=[system: "Ты — ассистент по погоде…", user: "Какая сейчас погода в Москве?"]
req=5820350b stage=deepseek-response model=deepseek-flash text="" tool_calls=[{"name":"get_weather","args":"{\"location\":\"Москва\"}"}]
req=5820350b stage=db tool=get_weather saved=true id=7
req=5820350b stage=tool tool=get_weather args={"location":"Москва"} is_error=false result="Москва, Россия: +15.4°C, облачно, влажность 66%, часовой пояс Europe/Moscow, местное время 2026-09-14 19:45:03 (Monday)"
req=5820350b stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-flash tool_choice=- tools_count=1 tools=[…] messages=[system: "…", user: "…", assistant: "", tool: "…"]
req=5820350b stage=deepseek-response model=deepseek-flash text="Сейчас в Москве +15.4°C…" tool_calls=[]
req=5820350b stage=outbound status=200 body={"message":"Какая сейчас погода в Москве?","answer":"Сейчас в Москве +15.4°C…"}
```

- `inbound` — what arrived: method, path, query, client address and the request body.
- `deepseek-request` / `deepseek-response` — every LLM round-trip (the weather agent and the
  `GET /time` fallback), with the model, the tools actually sent (`tools_count` plus the full
  definitions) and the answer; a failed call is logged with the error instead of the answer.
- `tool` — one line per tool invocation with its arguments, result and `is_error` (the weather
  agent's `get_weather`, the fueling agent's `find_fueling`); a tool that throws is logged with
  `is_error=true` and the answer still reaches the client.
- `db` — a tool-internal detail line with the database outcome (`saved=true id=…`, or
  `saved=false reason=…` when the insert fails; for the fueling lookup
  `lookup=found records=… tables=… events=… tokens=… feedback=…`, `lookup=not_found records=0` or
  `lookup=unavailable reason="…"`). A full weather request therefore shows the five chain stages
  plus this one extra `stage=db` line.
- `outbound` — the status code and the JSON body sent to the client; 400/404/500/503 bodies too.

A `POST /fueling` request writes the same eight lines with the same single id, in the same order
(`db` before `tool` — the tool emits the lookup line while executing, the strategy the `tool` line
when the call returns):

```
req=9f31c2a0 stage=inbound method=POST path=/fueling query=- client=127.0.0.1:52350 body={"message":"Найди данные по проливу для заказа 61574131-999F-48C9-93AE-3EAA68562177"}
req=9f31c2a0 stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-flash tool_choice=- tools_count=1 tools=[{"type":"function","function":{"name":"find_fueling","description":"…","parameters":{"type":"object","properties":{"orderId":{"description":"…","type":"string"}},"required":["orderId"]}}}] messages=[system: "Ты — ассистент по заказам на пролив (заправку)…", user: "Найди данные по проливу для заказа 61574131-…"]
req=9f31c2a0 stage=deepseek-response model=deepseek-flash text="" tool_calls=[{"name":"find_fueling","args":"{\"orderId\":\"61574131-999F-48C9-93AE-3EAA68562177\"}"}]
req=9f31c2a0 stage=db tool=find_fueling lookup=found records=1 tables=fuelings_archive events=2 tokens=1 feedback=0
req=9f31c2a0 stage=tool tool=find_fueling args={"orderId":"61574131-999F-48C9-93AE-3EAA68562177"} is_error=false result="НАЙДЕНО: 1 (fuelings_archive)…"
req=9f31c2a0 stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-flash tool_choice=- tools_count=1 tools=[…] messages=[…, tool: "…"]
req=9f31c2a0 stage=deepseek-response model=deepseek-flash text="Заказ 61574131-… найден в архиве…" tool_calls=[]
req=9f31c2a0 stage=outbound status=200 body={"message":"Найди данные по проливу для заказа 61574131-…","answer":"Заказ 61574131-… найден в архиве…"}
```

An invalid id (e.g. `12345`) shows the same chain **without** a `stage=db` line and with
`is_error=true` on the `tool` line: the id is rejected before any database access. When the stage
database is unreachable, the `db` line reads `lookup=unavailable reason="…"` and the `tool` line
`is_error=true` — the client still gets `200` with a "temporarily unavailable" answer.

`GET /time` resolved through Koog shows the same chain without `tool`/`db`:

```
req=1a2b3c4d stage=inbound method=GET path=/time query=location=Kisumu client=127.0.0.1:52345
req=1a2b3c4d stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-flash tool_choice=none tools_count=1 tools=[…get_weather…] messages=[system: "You are a precise geolocation assistant…", user: "Location: Kisumu"]
req=1a2b3c4d stage=deepseek-response model=deepseek-flash text="{\"timezone\":\"Africa/Nairobi\"}" tool_calls=[]
req=1a2b3c4d stage=outbound status=200 body={"location":"Kisumu","timezone":"Africa/Nairobi",…}
```

`POST /weather` with `{"model": "local"}` writes the same chain against the local Ollama server:
the stage names are unchanged, and the `endpoint=`/`model=` fields identify the provider that
actually served the request (`local` requests therefore never write a DeepSeek line):

```
req=7c5e1a90 stage=inbound method=POST path=/weather query=- client=127.0.0.1:52360 body={"message":"Какая сейчас погода в Москве?","model":"local"}
req=7c5e1a90 stage=deepseek-request endpoint=http://localhost:11434/api/chat model=qwen3:8b tool_choice=- tools_count=1 tools=[{"type":"function","function":{"name":"get_weather",…}}] messages=[system: "Ты — ассистент по погоде…", user: "Какая сейчас погода в Москве?"]
req=7c5e1a90 stage=deepseek-response model=qwen3:8b text="" tool_calls=[{"name":"get_weather","args":"{\"location\":\"Москва\"}"}]
req=7c5e1a90 stage=db tool=get_weather saved=true id=8
req=7c5e1a90 stage=tool tool=get_weather args={"location":"Москва"} is_error=false result="Москва, Россия: +15.4°C…"
req=7c5e1a90 stage=deepseek-request endpoint=http://localhost:11434/api/chat model=qwen3:8b tool_choice=- tools_count=1 tools=[…] messages=[…]
req=7c5e1a90 stage=deepseek-response model=qwen3:8b text="Сейчас в Москве…" tool_calls=[]
req=7c5e1a90 stage=outbound status=200 body={"message":"Какая сейчас погода в Москве?","answer":"Сейчас в Москве…"}
```

Notes:

- The chain is printed to the console (STDOUT) **and** written to `logs/ai-turbo.log` — daily
  rotation, 7 days of history kept (git-ignored). `tail -f logs/ai-turbo.log` follows it live.
- Body-like values (bodies, prompts, tool arguments and results) are truncated to 4096 characters
  with an explicit `…[truncated, N chars total]` marker.
- No secret is ever logged: the DeepSeek API key and the database password appear nowhere in the
  chain.
- The `/time` request carries the tool definitions with `tool_choice=none` instead of omitting the
  `tools` field — no tool must ever be called there, and an outbound request never carries an empty
  `tools` array. (This design decision supersedes the older "no `tools` field at all" wording of
  the requirements document.)
- Logging never changes the API: rendering for the log is best-effort, and a rendering failure
  cannot alter a status code or a response body.

## Fueling order lookup (`POST /fueling`)

`POST /fueling` sends the user's message to a dedicated Koog agent whose only tool is
`find_fueling` (same model, same executor, same logging choke point as the weather agent). A
question without a GUID is answered with a request for the order id — the tool is simply not
called.

**Tool input** (extracted by DeepSeek from the question, `src/main/resources/tools/find-fueling-tool.json`):

| Element | Value |
|---|---|
| Parameter | `orderId` — one fueling order id per call |
| Type | string |
| Format | Canonical GUID/UUID, 8-4-4-4-12 hex digits, case-insensitive, surrounding whitespace ignored |
| Example | `61574131-999F-48C9-93AE-3EAA68562177` (the stage-verified row; stored uppercase, matched case-insensitively) |
| Invalid input | No lookup at all: the tool rejects it, the trace shows `is_error=true` and no `stage=db` line, and the model answers that the id is not a valid GUID |
| Missing input | The tool is not called; the agent asks for the order id |

**Tool output** (compact structured text the model summarizes; the `stage=tool` line shows it):

| Block | Content |
|---|---|
| Match result | `НАЙДЕНО: n (tables)` or `НЕ НАЙДЕНО: …`; each match names its source table — `fuelings`, `fuelings_archive` or `fuelings_drop` (all three are searched) |
| Fueling record | One `record[i] table=…` block per match with every column: `fueling_id`, `vendor_fueling_order_id`, `user_id`, `status`, `amount`, `actual_amount`, `discount_fuel_price`, `vendor_fuel_price`, `fuel_type`, `gas_station_id`, `gas_pump_id`, `refueling_gun_id`, `fuel_reservation_key`, `fueling_type`, `fueling_payment_type`, `failed_reason`, `fueled_orders`, `extra`, `created_at`, `updated_at`, `finished_at`, `vendor_transaction_date` |
| Timestamps | Epoch-millisecond columns are converted to `yyyy-MM-dd HH:mm:ss UTC`; a value outside 2000-01-01…2100-01-01 is printed raw with a `(raw)` marker; a missing value is `-`. `vendor_transaction_date` is a **text** column in the stage schema (e.g. `2025-01-31T14:12:45.305Z`) and is printed as stored — reading it as an epoch is what made a whole lookup fail once |
| Related rows | All `partner_fueling_events` (event name, partner, delivery status, `data` jsonb, converted times), all `fueling_feedback` rows and all `belka_tokens` rows for the GUID, newest first |
| Counts | `events: total=N shown=M`, `feedback: …`, `belka_tokens: …` — the totals are the real row counts even when more than 50 rows per table are rendered |
| Caps | jsonb fields are cut at 2000 characters with `…[truncated, N chars total]`; the render cap is visible in the `db` line as `capped=true` |
| Errors | Invalid id, not found and stage-database-unavailable are distinct, clearly worded results — never a crash and never a `500` |

The lookup is **read-only**: Exposed `SELECT`s with bind parameters only, table names from a
fixed set of mapped `Table` objects, one transaction (one connection) per call with 10-second
login/connect/socket and query timeouts (`stageDb.timeoutSeconds`). Nothing connects at startup —
the application (and the weather endpoints) work even when the stage database is absent, and an
unreachable stage DB degrades to a "temporarily unavailable" answer with a `200`.

The GUID is canonicalized to lowercase before the query, and the three main lookups match
case-insensitively (`WHERE lower(fueling_id) = ?`, the bound value being the canonical form): the
stage tables store non-lowercase ids (confirmed live, risk R-3), so an uppercase row such as the
README example is still found.

The Postman recipe, the other cases (not found, invalid id, no id) and the exact log lines are in
[Testing with Postman](#testing-with-postman).

## Testing with Postman

### Postman examples (manual verification)

Prerequisites (one-time): the server is running on `http://localhost:8080` (`./gradlew run`), a
`DEEPSEEK_API_KEY` is available (environment variable or git-ignored `.env`), and the local
PostgreSQL container from the README (`TimeAndData`, host port 5439, database `mydb2`) has the
`users` table and `users_id_seq` created. If the database is down, the answer is still returned but
no row is written; if the key is missing, the endpoint returns 503.

For the fueling request, additionally: the git-ignored `.env` contains `STAGE_DB_PASSWORD` (see
[Configuration](#configuration)) and the stage database is reachable from your machine. Without the
password (or with the stage DB down) the endpoint still answers `200`, just with a
"temporarily unavailable" message instead of the order data.

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

**Request 3 — look up a fueling order by GUID**

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `http://localhost:8080/fueling` |
| Headers | `Content-Type: application/json` |
| Body | raw JSON: `{"message": "Найди данные по проливу для заказа 61574131-999F-48C9-93AE-3EAA68562177"}` |

Steps: create the request in Postman, choose **Body → raw → JSON**, paste the body, press **Send**.
The message must carry the order GUID; a non-empty `message` is required, otherwise the server
answers 400 with `{"error":"Field 'message' is required"}`. The GUID in the body must be the one
verified against the stage database (see the smoke checklist in the feature docs) — with any other
well-formed GUID the answer is "no data found", which is still a `200`.

> **Substitution note (OQ-02).** `61574131-999F-48C9-93AE-3EAA68562177` is the GUID verified live
> against the stage database (a row in `fuelings_archive`); it replaces the requirements document's
> example `5e12bef2-2f78-48f0-aab5-ccb6bfeb8469`, which exists in none of the three stage tables —
> the spec folder documents the original example and is not edited here. The row is stored
> **uppercase**, while the looked-up value is canonicalized to lowercase and matched with
> `lower(fueling_id) = ?`, so either case works in the body; the answer echoes the message as sent.

Expected response: `200 OK` (illustrative wording; the real values come from the stage row)

```json
{
    "message": "Найди данные по проливу для заказа 61574131-999F-48C9-93AE-3EAA68562177",
    "answer": "Заказ 61574131-999F-48C9-93AE-3EAA68562177 найден в архиве (fuelings_archive): статус SUCCESS, сумма 45.00, фактическая 45.00, топливо AI-95, колонка 12, пистолет 3. По заказу 2 партнёрских события (последнее: DELIVERED) и 1 токен belka; обращений в поддержку нет."
}
```

Other cases to try (same request, different body):

| Case | Body | Expected |
|---|---|---|
| Found in the main table | GUID of a recent fueling | `200`; the answer and the `stage=db` line name `fuelings` |
| Found in the archive / drop table | GUID stored in `fuelings_archive` or `fuelings_drop` | `200`; the answer names that table |
| Not found | `{"message": "Найди заказ 00000000-0000-4000-8000-000000000000"}` | `200`; the answer states there is no data for the id in the stage database, log `lookup=not_found records=0` |
| Invalid id | `{"message": "Найди заказ 12345"}` | `200`; the answer says the id is not a valid GUID, log `is_error=true` and **no** `stage=db` line |
| No id at all | `{"message": "Найди данные по проливу"}` | `200`; the answer asks for the order id/GUID, the tool is not called |
| Stage DB down | the example body, stage host changed to an unreachable one | `200` in under ~30 s; "temporarily unavailable" answer, log `lookup=unavailable reason="…"` |
| Blank message | `{"message": "   "}` | `400` with `{"error":"Field 'message' is required"}` and no LLM/tool lines |

The exact eight-line trace chain of a successful fueling request is documented in
[Logging](#logging--the-request-chain).

**Request 4 (context) — time endpoint**: `GET http://localhost:8080/time?location=Moscow`, no
headers or body, returns the live date/time JSON for the location.

## Database

### Local weather database (`db.*`)

The weather tool records every request into a PostgreSQL `users` table. The local
development setup is a Docker container:

The weather tool records every request into a PostgreSQL `users` table. The local
development setup is a Docker container:

```bash
docker run --name TimeAndData \
  -e POSTGRES_USER=myuser -e POSTGRES_PASSWORD=mysecret -e POSTGRES_DB=mydb2 \
  -p 5439:5432 -d postgres:16
```

Create the table (the container above does not create it for you):

```sql
CREATE TABLE users (
    id         integer PRIMARY KEY DEFAULT nextval('users_id_seq'),
    data       varchar(255) NOT NULL UNIQUE,
    time       varchar(100),
    created_at timestamp DEFAULT now()
);
```

```sql
CREATE SEQUENCE users_id_seq;
```

The application connects through Exposed and opens a connection per call, so it
starts fine even when the database is down (weather answers are still returned,
just not recorded).

### Stage database (`stageDb.*`, read-only)

The fueling lookup reads the **stage** PostgreSQL — a different server and database from the local
weather one (never the `db.*` settings). It searches the order GUID in `fuelings`,
`fuelings_archive` and `fuelings_drop` and reads the related `partner_fueling_events`,
`fueling_feedback` and `belka_tokens` rows. Access is read-only: Exposed `SELECT`s with bind
parameters only, table names from a fixed set of mapped `Table` objects, no
`INSERT`/`UPDATE`/`DELETE`/DDL anywhere (guarded by a test).

The stage password is never stored in a tracked file. Put it in the git-ignored `.env`:

```
STAGE_DB_PASSWORD=<the stage password>
```

The connection is opened per lookup, with login/connect/socket and query timeouts
(`stageDb.timeoutSeconds`, default 10 s). Nothing connects at startup, so the application and all
weather endpoints work with the stage DB absent: the fueling endpoint then answers `200` with a
"temporarily unavailable" message.

## Run locally

Requires JDK 17+ (or just `./gradlew` — the wrapper works by itself).

```bash
./gradlew run            # starts on http://localhost:8080
```

Or build a distributable and run it:

```bash
./gradlew installDist
./build/install/ai-turbo/bin/ai-turbo
```

## Tests

```bash
./gradlew test
```

The suite covers:

- `TimeServiceTest` — date/time formatting in several time zones with a frozen clock.
- `TimeZoneResolverTest` — direct ids, the built-in city map, fall-through and caching.
- `LlmTimeZoneResolverTest` — the Koog-based resolution with a fake prompt executor (parsing,
  malformed replies, missing key, non-empty tool descriptors, cancellation).
- `ApplicationTest` — full Ktor routes through `testApplication` (200 / 400 / 404 cases) and the
  logged inbound/outbound lines of each request.
- `OpenMeteoWeatherClientTest` — weather client against a mock engine (paths, params,
  parsing, WMO code descriptions, failure cases).
- `GetWeatherToolTest` — the Koog tool with fakes (weather summary, the recorded local
  date and time, no insert on failures, graceful degradation when the DB is down, the
  `stage=db` outcomes, the descriptor coming from the tool JSON resource).
- `WeatherRecordRepositoryTest` — the UNIQUE-conflict retry and the row formatting.
- `DbConfigTest` — database configuration defaults and overrides.
- `WeatherRoutesTest` — POST /weather and GET /weather/history through `testApplication`
  with a fake agent and repository (200 / 400 / 503 cases, the `model` field: unknown → 400,
  `" LOCAL "` → the local target observed by the fake agent, absent/blank → DeepSeek) and the
  trace lines they write.
- `TraceLogTest` / `TraceFormatsTest` — the log line shapes, truncation and error rendering.
- `ToolJsonRendererTest` / `ToolSpecTest` — the rendered `tools` JSON and the resource loader.
- `LoggingPromptExecutorTest` — the executor decorator (request/response lines, empty tools,
  failures, streaming, `close`).
- `KoogWeatherAgentTest` — the agent strategy with a scripted executor (one `stage=tool` line,
  correlation id, failing tool, the tool loop warning, non-empty descriptors).
- `RequestTracingTest` — `beginTrace` / `respondTraced` (idempotency, truncation, statuses,
  a failing log rendering never changing the response).
- `RawDeepSeekCallTest` — static guard: no raw DeepSeek HTTP call anywhere in `src/main/kotlin`.
- `AppModulesTest` — the production Kodein graph resolves offline (no cycle, routing executor,
  both tagged provider executors with their endpoint labels, blank key → the DeepSeek path is
  rejected while the local path stays usable).
- `TraceChainIntegrationTest` — the offline five-stage chain end to end (order, one id,
  non-empty tools, `tool_choice=none` for `/time`, blank key statuses, truncation, no secrets).
- `StageDbConfigTest` — the stage settings: defaults, explicit values, `STAGE_DB_*` / `.env`
  password precedence, blank password, `jdbcUrl`.
- `FuelingIdTest` — the GUID contract: accepted/rejected forms, trimming, lowercasing, the two
  exceptions.
- `FuelingReportTest` — the tool-output renderer: every field, UTC times, the raw/`-` cases, the
  jsonb and 50-row caps with real totals, the not-found text.
- `FindFuelingToolTest` — the tool with the fake repository: found/not-found/unavailable, the
  `stage=db` lines, invalid id without a repository call and without a `db` line, the descriptor
  from the JSON resource.
- `KoogFuelingAgentTest` — the fueling agent strategy with a scripted executor (one `stage=tool`
  line with the GUID, `is_error`, the correlation id, the system prompt, non-convergence).
- `FuelingRoutesTest` — `POST /fueling` through `testApplication` (200 / 400 / 503, the same
  `model` field cases as `/weather`, trace lines, the agent running inside the request trace).
- `FuelingModulesTest` — the composed production graph resolves offline; the weather registry stays
  `get_weather`-only while the fueling registry holds `find_fueling`.
- `FuelingChainIntegrationTest` — the offline eight-stage fueling chain end to end (order, one id,
  `tools_count=1` with the `find_fueling` definition, `db`/`tool` lines, invalid id without a `db`
  line, truncation, no secrets, blank-key 503 on the composed graph).
- `StageReadOnlyGuardTest` — static guard: the stage repository sources contain only read `SELECT`s
  (`selectAll`/`where`/`orderBy`, `lowerCase() eq <bound value>`), no write/DDL statements and no
  interpolated GUIDs.
- `EnvFileTest` — the stdlib `.env` reader (parse rules, missing file, no interpolation).
- `OllamaConfigTest` — the `ollama.*` defaults, the config overrides and the `chatEndpoint` label.
- `LlmTargetTest` — the `model` field mapping: absent/blank → DeepSeek, `local`/`deepseek`
  trimmed and case-insensitive, unknown → rejected, and the request-scoped context element.
- `ProviderRoutingPromptExecutorTest` — the routing executor with two fakes: DeepSeek keeps the
  passed model, `local` substitutes the Ollama model, the blank-key guard fires before any delegate
  call or log line, streaming reads the target at collection time, `close()` closes both delegates.

No test requires a running database, a stage DB, a running Ollama, `.env` or a live LLM.

## Configuration

`src/main/resources/application.conf` — port, DeepSeek base URL, model, API key, the local Ollama
server and model, the local database connection, the stage database connection and Open-Meteo
endpoints. Environment variables override the file values:

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | Server port |
| `DEEPSEEK_API_KEY` | *(from `.env` file or environment)* | Key for the DeepSeek API |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | LLM API endpoint |
| `DEEPSEEK_MODEL` | `deepseek-flash` | Model name (the only override; no fallback model in code) |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Local Ollama server (`ollama.baseUrl`) used for `"model": "local"` |
| `OLLAMA_MODEL` | `qwen3:8b` | Local Ollama model (`ollama.model`); no secret is involved |
| `OLLAMA_TIMEOUT_SECONDS` | `120` | How long one local generation may take (`ollama.timeoutSeconds`); connect stays at 10 s |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5439` | PostgreSQL port (the local Docker mapping) |
| `DB_NAME` | `mydb2` | PostgreSQL database |
| `DB_USER` | `myuser` | PostgreSQL user |
| `DB_PASSWORD` | *(from env, `.env` file, or `mysecret`)* | PostgreSQL password |
| `STAGE_DB_HOST` | `postgres.stage.turboapp.ru` | Stage PostgreSQL host (read-only fueling data) |
| `STAGE_DB_PORT` | `25432` | Stage PostgreSQL port |
| `STAGE_DB_NAME` | `fueling` | Stage database name |
| `STAGE_DB_USER` | `fueling` | Stage database user |
| `STAGE_DB_PASSWORD` | *(from env or `.env`, no default)* | Stage database password — never in a tracked file |
| `STAGE_DB_TIMEOUT_SECONDS` | `10` | Stage connect/socket/login and query timeout |
| `WEATHER_GEOCODING_BASE_URL` | `https://geocoding-api.open-meteo.com` | Open-Meteo geocoding endpoint |
| `WEATHER_FORECAST_BASE_URL` | `https://api.open-meteo.com` | Open-Meteo forecast endpoint |
| `WEATHER_LANGUAGE` | `ru` | Language of the geocoding results |

## Koog agent

The tools (`com.aiturbo.tools`) are built on [Koog](https://github.com/JetBrains/koog) — the
JetBrains framework for LLM agents on the JVM. Each agent calls DeepSeek through Koog's
OpenAI-compatible client (DeepSeek's API is OpenAI-compatible) or the local Ollama model, as
selected per request, and each tool is a Koog `SimpleTool` whose parameter JSON schema is generated
from a serializable args class. When the LLM answers with a tool call, the agent executes the tool
(`get_weather` records the request in PostgreSQL, `find_fueling` reads the stage database) and
feeds the result back to the model.

The two vertical slices are wired additively: the weather slice keeps its `get_weather`-only tool
registry, while the fueling slice has its own `find_fueling`-only registry and agent
(`fuelingModule`, with Kodein string tags separating the two registries), so neither tool can
appear in the other's requests.

Each tool's **name and description come from a JSON resource** —
`src/main/resources/tools/get-weather-tool.json` and
`src/main/resources/tools/find-fueling-tool.json` — the file is the single source of truth
for what DeepSeek receives (the `deepseek-request` lines above show those definitions), and
editing only the file changes what the model is told. If a resource is missing or invalid,
the application fails fast at startup, naming the path.

**All** LLM traffic — both agents and the `/time` fallback — goes through one Koog `PromptExecutor`
(`ProviderRoutingPromptExecutor`) that dispatches on the request-scoped target to one of two
`LoggingPromptExecutor` decorators, one per provider: DeepSeek and local Ollama. The decorators are
the single choke point writing the `deepseek-request` / `deepseek-response` lines, and the
`endpoint=`/`model=` fields of those lines identify the provider that served the call. A blank
DeepSeek key is rejected there before any HTTP attempt or log line, while the local path keeps
working; no production component calls the DeepSeek API directly (guarded by
`RawDeepSeekCallTest`), and an outbound request never carries an empty `tools` array: the executor
warns when a call without tools would go out.

## Secrets (kept out of the repository)

The DeepSeek API key is **not** stored in the repository. The app loads it, in order:

1. `deepseek.apiKey` in `application.conf` (left empty on purpose),
2. the `DEEPSEEK_API_KEY` environment variable,
3. a local `.env` file in the project root (git-ignored).

The stage database password follows the same rule with a different name: `stageDb.password` in
`application.conf` (left empty), then the `STAGE_DB_PASSWORD` environment variable, then `.env`.
There is no default password — without it a fueling lookup answers "temporarily unavailable"
instead of failing.

To run locally, create a `.env` file in the project root:

```
DEEPSEEK_API_KEY=sk-...
STAGE_DB_PASSWORD=...
```

The file never leaves your machine — `.gitignore` excludes it, so it cannot end up
on GitHub. In production, set the `DEEPSEEK_API_KEY` and `STAGE_DB_PASSWORD` environment
variables instead. Neither secret is ever logged or returned in a response.

## Deploy on a host

**Docker:**

```bash
docker build -t ai-turbo .
docker run -p 8080:8080 -e DEEPSEEK_API_KEY=sk-... ai-turbo
```

**Render / Fly.io / Railway:** create a web service pointing at this repository,
use the provided `Dockerfile`, set the `DEEPSEEK_API_KEY` environment variable —
the app picks up the `PORT` env var automatically.

**Any VPS:** install a JDK 17+, upload `build/install/ai-turbo` (or build with
`./gradlew installDist`), and run `bin/ai-turbo` behind a reverse proxy.

## Feature documentation (feature-design pipeline)

The features were specified through the feature-design pipeline; the documents are the reference
for the behavior described above.

**`koog-everything-and-logging`** (time API, weather agent, the trace chain):

1. [`01-requirements.md`](docs/features/koog-everything-and-logging/01-requirements.md) — requirements, the Postman recipe, acceptance criteria.
2. [`02-design.md`](docs/features/koog-everything-and-logging/02-design.md) — the design: log line format, the Koog-only path, the tool JSON resource, the Koin wiring.
3. [`03-plan.md`](docs/features/koog-everything-and-logging/03-plan.md) — the implementation plan (tasks T-01…T-16) and the manual smoke checklist.

The consolidated specification is [`spec.md`](docs/features/koog-everything-and-logging/spec.md)
in the same folder.

**`fueling-order-tool`** (the `find_fueling` tool, `POST /fueling`, the read-only stage lookup):

1. [`01-requirements.md`](docs/features/fueling-order-tool/01-requirements.md) — requirements, the tool input/output contract, the Postman examples.
2. [`02-design.md`](docs/features/fueling-order-tool/02-design.md) — the design: stage schema, DTOs, renderer, Koin wiring, trace lines.
3. [`03-plan.md`](docs/features/fueling-order-tool/03-plan.md) — the implementation plan (T-01…T-17) and the live stage smoke checklist (M-05).

The consolidated specification is [`spec.md`](docs/features/fueling-order-tool/spec.md)
in the same folder. Its "Koin wiring" wording describes the stack at the time it was written.

**`kodein-exposed-ollama`** (Kodein-DI, Exposed repositories, the `model` field and the local
Ollama provider):

1. [`01-requirements.md`](docs/features/kodein-exposed-ollama/01-requirements.md) — requirements, the model-selection contract, the smoke checklist.
2. [`02-design.md`](docs/features/kodein-exposed-ollama/02-design.md) — the design: Kodein modules, Exposed mappings, the routing executor, the trace contract.
3. [`03-plan.md`](docs/features/kodein-exposed-ollama/03-plan.md) — the implementation plan (tasks T-01…T-25) and the milestones.

The consolidated specification is [`spec.md`](docs/features/kodein-exposed-ollama/spec.md)
in the same folder; this feature supersedes the earlier Koin/plain-JDBC descriptions above.
