# Ai-Turbo — Time API

A Kotlin server built with **Ktor** + **Koin** that returns the current local **date and time**
for any location, resolved in real time.

You pass a location (a city, a region, or a raw IANA time zone id), and the service answers
with the live local date, clock time, and UTC offset.

## How it works

`GET /time?location=Moscow` resolves the location into an IANA time zone in three steps:

1. **Direct match** — if the value is already an IANA id (`Europe/Paris`), it is used as is.
2. **Built-in map** — ~130 well-known cities are resolved offline, without any HTTP calls.
3. **DeepSeek LLM over HTTP** — anything else (e.g. `location=Canberra` or `location=Kisumu`)
   is sent to the DeepSeek API with your key, which returns the time zone. Results are
   cached in memory.

Dependency injection is done with **Koin**; the clock is injectable so tests run with frozen time.

## API

| Endpoint | Description |
|---|---|
| `GET /` | Service info |
| `GET /time?location=<place>` | Current date and time in that place |

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

Error codes:

| Code | Meaning |
|---|---|
| `400` | `location` query parameter is missing |
| `404` | The location could not be resolved to a time zone |
| `500` | Internal server error |

## Testing with Postman

1. Start the server (see below).
2. In Postman create a `GET` request: `http://localhost:8080/time`.
3. In the **Params** tab add a query param: key `location`, value `Moscow` (or any city,
   or an IANA id like `America/New_York`).
4. Press **Send** — you will get the JSON with the live date and time.

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

## Configuration

`src/main/resources/application.conf` — port, DeepSeek base URL, model, and API key.
Environment variables override the file values:

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | Server port |
| `DEEPSEEK_API_KEY` | *(from `.env` file or environment)* | Key for the DeepSeek API |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | LLM API endpoint |
| `DEEPSEEK_MODEL` | `deepseek-chat` | Model name |

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
