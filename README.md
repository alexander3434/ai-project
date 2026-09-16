# Ai-Turbo — Time API + Weather Agent

A Kotlin server built with **Ktor** + **Koin** that returns the current local **date and time**
for any location, resolved in real time.

It also runs a **Koog** (JetBrains) LLM agent with the **DeepSeek** model and a `get_weather`
tool: when the user asks about the weather, the agent calls the tool, the tool fetches the
current weather from Open-Meteo and records the request in a **PostgreSQL** database
(the `users` table — the id increments on every request).

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

Dependency injection is done with **Koin**; the clock is injectable so tests run with frozen time.

## API

| Endpoint | Description |
|---|---|
| `GET /` | Service info |
| `GET /time?location=<place>` | Current date and time in that place |
| `POST /weather` | Ask the LLM agent anything (weather questions trigger the database recording tool) |
| `GET /weather/history?limit=<n>` | The most recent weather request records from the database |

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

Error codes:

| Code | Meaning |
|---|---|
| `400` | `location` / `message` query parameter is missing, or the request body is invalid |
| `404` | The location could not be resolved to a time zone |
| `500` | Internal server error |
| `503` | The database or the LLM agent (DeepSeek) is unavailable |

## Logging — the five-stage request chain

Every request is traced end to end by one correlation id (`req=<8 hex chars>`) on the dedicated
logger `com.aiturbo.trace` (configured at INFO in `logback.xml`, so no setup is needed). One
`POST /weather` writes these lines, in order:

```
req=5820350b stage=inbound method=POST path=/weather query=- client=127.0.0.1:52344 body={"message":"Какая сейчас погода в Москве?"}
req=5820350b stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-chat tool_choice=- tools_count=1 tools=[{"type":"function","function":{"name":"get_weather","description":"…","parameters":{"type":"object","properties":{"location":{"description":"…","type":"string"}},"required":["location"]}}}] messages=[system: "Ты — ассистент по погоде…", user: "Какая сейчас погода в Москве?"]
req=5820350b stage=deepseek-response model=deepseek-chat text="" tool_calls=[{"name":"get_weather","args":"{\"location\":\"Москва\"}"}]
req=5820350b stage=db tool=get_weather saved=true id=7
req=5820350b stage=tool tool=get_weather args={"location":"Москва"} is_error=false result="Москва, Россия: +15.4°C, облачно, влажность 66%, часовой пояс Europe/Moscow, местное время 2026-09-14 19:45:03 (Monday)"
req=5820350b stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-chat tool_choice=- tools_count=1 tools=[…] messages=[system: "…", user: "…", assistant: "", tool: "…"]
req=5820350b stage=deepseek-response model=deepseek-chat text="Сейчас в Москве +15.4°C…" tool_calls=[]
req=5820350b stage=outbound status=200 body={"message":"Какая сейчас погода в Москве?","answer":"Сейчас в Москве +15.4°C…"}
```

- `inbound` — what arrived: method, path, query, client address and the request body.
- `deepseek-request` / `deepseek-response` — every LLM round-trip (the weather agent and the
  `GET /time` fallback), with the model, the tools actually sent (`tools_count` plus the full
  definitions) and the answer; a failed call is logged with the error instead of the answer.
- `tool` — one line per `get_weather` invocation with its arguments, result and `is_error`.
- `db` — a tool-internal detail line with the database outcome (`saved=true id=…`, or
  `saved=false reason=…` when the insert fails). A full weather request therefore shows the five
  chain stages plus this one extra `stage=db` line.
- `outbound` — the status code and the JSON body sent to the client; 400/404/500/503 bodies too.

`GET /time` resolved through Koog shows the same chain without `tool`/`db`:

```
req=1a2b3c4d stage=inbound method=GET path=/time query=location=Kisumu client=127.0.0.1:52345
req=1a2b3c4d stage=deepseek-request endpoint=https://api.deepseek.com/chat/completions model=deepseek-chat tool_choice=none tools_count=1 tools=[…get_weather…] messages=[system: "You are a precise geolocation assistant…", user: "Location: Kisumu"]
req=1a2b3c4d stage=deepseek-response model=deepseek-chat text="{\"timezone\":\"Africa/Nairobi\"}" tool_calls=[]
req=1a2b3c4d stage=outbound status=200 body={"location":"Kisumu","timezone":"Africa/Nairobi",…}
```

Notes:

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

## Testing with Postman

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

## Database

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

The application connects with plain JDBC and opens a connection per call, so it
starts fine even when the database is down (weather answers are still returned,
just not recorded).

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
  with a fake agent and repository (200 / 400 / 503 cases) and the trace lines they write.
- `TraceLogTest` / `TraceFormatsTest` — the log line shapes, truncation and error rendering.
- `ToolJsonRendererTest` / `ToolSpecTest` — the rendered `tools` JSON and the resource loader.
- `LoggingPromptExecutorTest` — the executor decorator (request/response lines, empty tools,
  failures, streaming, `close`).
- `KoogWeatherAgentTest` — the agent strategy with a scripted executor (one `stage=tool` line,
  correlation id, failing tool, the tool loop warning, non-empty descriptors).
- `RequestTracingTest` — `beginTrace` / `respondTraced` (idempotency, truncation, statuses,
  a failing log rendering never changing the response).
- `RawDeepSeekCallTest` — static guard: no raw DeepSeek HTTP call anywhere in `src/main/kotlin`.
- `AppModulesTest` — the production Koin graph resolves offline (no cycle, logging executor,
  blank key → 503 agent).
- `TraceChainIntegrationTest` — the offline five-stage chain end to end (order, one id,
  non-empty tools, `tool_choice=none` for `/time`, blank key statuses, truncation, no secrets).

No test requires a running database or a live LLM.

## Configuration

`src/main/resources/application.conf` — port, DeepSeek base URL, model, API key,
database connection, and Open-Meteo endpoints.
Environment variables override the file values:

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | Server port |
| `DEEPSEEK_API_KEY` | *(from `.env` file or environment)* | Key for the DeepSeek API |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | LLM API endpoint |
| `DEEPSEEK_MODEL` | `deepseek-chat` | Model name |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5439` | PostgreSQL port (the local Docker mapping) |
| `DB_NAME` | `mydb2` | PostgreSQL database |
| `DB_USER` | `myuser` | PostgreSQL user |
| `DB_PASSWORD` | *(from env, `.env` file, or `mysecret`)* | PostgreSQL password |
| `WEATHER_GEOCODING_BASE_URL` | `https://geocoding-api.open-meteo.com` | Open-Meteo geocoding endpoint |
| `WEATHER_FORECAST_BASE_URL` | `https://api.open-meteo.com` | Open-Meteo forecast endpoint |
| `WEATHER_LANGUAGE` | `ru` | Language of the geocoding results |

## Koog agent

The weather tool is built on [Koog](https://github.com/JetBrains/koog) — the JetBrains
framework for LLM agents on the JVM. The agent uses DeepSeek as its model through Koog's
OpenAI-compatible client (DeepSeek's API is OpenAI-compatible), and the `get_weather`
tool is a Koog `SimpleTool` whose parameter JSON schema is generated from a serializable
args class. When the LLM answers with a tool call, the agent executes the tool (which
records the request in PostgreSQL) and feeds the result back to the model.

The tool **name and description come from the JSON resource**
`src/main/resources/tools/get-weather-tool.json` — the file is the single source of truth
for what DeepSeek receives (the `deepseek-request` line above shows that definition), and
editing only the file changes what the model is told. If the resource is missing or invalid,
the application fails fast at startup, naming the path.

**All** DeepSeek traffic — the agent and the `/time` fallback — goes through one Koog
`PromptExecutor` wrapped by `LoggingPromptExecutor`, which is the single choke point
writing the `deepseek-request` / `deepseek-response` lines. No production component calls
the DeepSeek API directly (guarded by `RawDeepSeekCallTest`), and an outbound request never
carries an empty `tools` array: the executor warns when a call without tools would go out.

## API key (kept out of the repository)

The DeepSeek API key is **not** stored in the repository. The app loads it, in order:

1. `deepseek.apiKey` in `application.conf` (left empty on purpose),
2. the `DEEPSEEK_API_KEY` environment variable,
3. a local `.env` file in the project root (git-ignored).

To run locally, create a `.env` file in the project root:

```
DEEPSEEK_API_KEY=sk-...
```

The file never leaves your machine — `.gitignore` excludes it, so it cannot end up
on GitHub. In production, set the `DEEPSEEK_API_KEY` environment variable instead.

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

This feature (`koog-everything-and-logging`) was specified through the feature-design
pipeline; the documents are the reference for the behavior described above:

1. [`01-requirements.md`](docs/features/koog-everything-and-logging/01-requirements.md) — requirements, the Postman recipe, acceptance criteria.
2. [`02-design.md`](docs/features/koog-everything-and-logging/02-design.md) — the design: log line format, the Koog-only path, the tool JSON resource, the Koin wiring.
3. [`03-plan.md`](docs/features/koog-everything-and-logging/03-plan.md) — the implementation plan (tasks T-01…T-16) and the manual smoke checklist.

The consolidated specification is [`spec.md`](docs/features/koog-everything-and-logging/spec.md)
in the same folder.
