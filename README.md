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
3. **DeepSeek LLM over HTTP** — anything else (e.g. `location=Canberra` or `location=Kisumu`)
   is sent to the DeepSeek API with your key, which returns the time zone. Results are
   cached in memory.

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

## Testing with Postman

1. Start the server (see below).
2. In Postman create a `GET` request: `http://localhost:8080/time`.
3. In the **Params** tab add a query param: key `location`, value `Moscow` (or any city,
   or an IANA id like `America/New_York`).
4. Press **Send** — you will get the JSON with the live date and time.

For the weather agent:

1. Create a `POST` request: `http://localhost:8080/weather`.
2. In the **Body** tab pick **raw** → **JSON** and enter
   `{"message": "Какая сейчас погода в Москве?"}`.
3. Press **Send** — the agent answers and a row appears in the database
   (check `GET http://localhost:8080/weather/history?limit=5`).

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
- `LlmTimeZoneResolverTest` — LLM HTTP client against a mock engine (parsing, malformed
  responses, HTTP errors, missing key).
- `ApplicationTest` — full Ktor routes through `testApplication` (200 / 400 / 404 cases).
- `OpenMeteoWeatherClientTest` — weather client against a mock engine (paths, params,
  parsing, WMO code descriptions, failure cases).
- `GetWeatherToolTest` — the Koog tool with fakes (weather summary, the recorded local
  date and time, no insert on failures, graceful degradation when the DB is down).
- `WeatherRecordRepositoryTest` — the UNIQUE-conflict retry and the row formatting.
- `DbConfigTest` — database configuration defaults and overrides.
- `WeatherRoutesTest` — POST /weather and GET /weather/history through `testApplication`
  with a fake agent and repository (200 / 400 / 503 cases).

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
