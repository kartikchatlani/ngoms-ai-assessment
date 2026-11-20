# URL Shortener

A production-style URL shortener in **Java 21 / Spring Boot 4 / PostgreSQL**, built in three
documented stages: greenfield, brownfield and ambiguous. Each design decision is recorded with
its alternatives and rationale.

| Status | |
|---|---|
| Phase 1: Greenfield core (create, redirect, manage) | ✅ Done |
| Phase 2: Brownfield (click analytics on the running system) | ✅ Done |
| Phase 3: Ambiguous requirement | ⏳ next |

---

## Quick start

### Option A: Docker (recommended; needs only Docker Desktop)
```bash
docker compose up --build
# in another terminal:
./scripts/smoke-test.sh        # 18 end-to-end checks (macOS / Linux / Git Bash)
bash ./scripts/smoke-test.sh   # Windows PowerShell: invoke via bash, or it just opens the file
```

### Option B: run locally (needs JDK 21 and a PostgreSQL)
```bash
docker compose up -d postgres  # or point DB_URL at any PostgreSQL 14+
./mvnw spring-boot:run         # Windows: mvnw.cmd spring-boot:run
```
No Maven install is needed; the wrapper downloads it.

### Run the tests
```bash
./mvnw test    # integration tests need Docker running; without it they are skipped, not failed
```

### Benchmark redirects (optional)
```bash
java scripts/RedirectBench.java http://localhost:8080 20000 32   # requests, concurrency; needs only JDK 21
```

## Using the API

Replace `Ab3xY9z` below with the `code` returned when you create a link. You can also open any
`shortUrl` in a browser to follow the redirect.

### Bash (macOS / Linux / Git Bash)
```bash
# Create a short link
curl -s -X POST localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/a/very/long/path"}'
# -> 201 {"code":"Ab3xY9z","shortUrl":"http://localhost:8080/Ab3xY9z","status":"ACTIVE",...}

# Custom alias + expiry
curl -s -X POST localhost:8080/api/v1/links -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/sale","alias":"black-friday","expiresAt":"2026-12-01T00:00:00Z"}'

# Follow it (302 -> destination); every GET redirect is counted
curl -i localhost:8080/Ab3xY9z

# Click stats: total, daily series, top referrers, client types (days = 1-365, default 30)
curl -s 'localhost:8080/api/v1/links/Ab3xY9z/stats?days=7'
# -> {"totalClicks":1,"window":{"from":"...","to":"...","clicks":1},"daily":[...],"agents":{"BOT":1,...},...}

# Inspect, list, delete
curl -s localhost:8080/api/v1/links/Ab3xY9z
curl -s 'localhost:8080/api/v1/links?limit=10'         # pass nextCursor back as &cursor=
curl -i -X DELETE localhost:8080/api/v1/links/Ab3xY9z  # 204; redirect now returns 410
```

### PowerShell (Windows)
In Windows PowerShell, `curl` is an alias for `Invoke-WebRequest` and mangles JSON quoting, so
use `Invoke-RestMethod` (or `curl.exe` for raw responses).
```powershell
# Create a short link (the response is kept in $link)
$link = Invoke-RestMethod -Method Post http://localhost:8080/api/v1/links `
  -ContentType 'application/json' -Body '{"url":"https://example.com/a/very/long/path"}'
$link   # -> code, shortUrl, status, ...

# Custom alias + expiry
Invoke-RestMethod -Method Post http://localhost:8080/api/v1/links -ContentType 'application/json' `
  -Body '{"url":"https://example.com/sale","alias":"black-friday","expiresAt":"2026-12-01T00:00:00Z"}'

# Follow it (302 -> destination); curl.exe shows the raw response without following
curl.exe -i "http://localhost:8080/$($link.code)"

# Click stats (days = 1-365, default 30); nested fields need ConvertTo-Json to display fully
Invoke-RestMethod "http://localhost:8080/api/v1/links/$($link.code)/stats?days=7" | ConvertTo-Json -Depth 4

# Inspect, list, delete
Invoke-RestMethod "http://localhost:8080/api/v1/links/$($link.code)"
Invoke-RestMethod "http://localhost:8080/api/v1/links?limit=10"   # pass nextCursor back as &cursor=
Invoke-RestMethod -Method Delete "http://localhost:8080/api/v1/links/$($link.code)"   # 204; redirect now returns 410
```
Error responses (4xx/5xx) make `Invoke-RestMethod` throw. Use `curl.exe -i` to see the error body.

Errors are [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) with a stable `errorCode`:
```json
{"status":409,"title":"Alias already taken","detail":"Alias 'black-friday' is already taken","errorCode":"ALIAS_TAKEN"}
```

Full endpoint table: [architecture.md §5](docs/architecture.md#5-api-summary).

## Configuration
| Env var | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/shortener` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `shortener` / `shortener` | DB credentials (local dev defaults) |
| `DB_POOL_SIZE` | `10` | Max DB connections |
| `APP_BASE_URL` | `http://localhost:8080` | Public origin used to build `shortUrl` |
| `APP_CODE_LENGTH` | `7` | Generated code length (5–16) |
| `PORT` | `8080` | HTTP port |
| `APP_ANALYTICS_ENABLED` | `true` | Kill switch for click recording; redirects work either way |
| `APP_ANALYTICS_BUFFER_CAPACITY` | `10000` | Clicks buffered in memory before new ones are dropped (and counted) |

Health: `/actuator/health/liveness`, `/actuator/health/readiness`. Metrics: `/actuator/metrics`
(click pipeline: `/actuator/metrics/shortener.clicks`, `/actuator/metrics/shortener.clicks.pending`).

Stats are eventually consistent: a click appears once the background writer has stored it,
normally within milliseconds.

## Documentation map
| Document | What's in it |
|---|---|
| [Architecture overview](docs/architecture.md) | Components, data model, request flows, reliability properties |
| [Decision records (ADRs)](docs/adr/README.md) | Every significant decision with the options considered |
| [Scenario 1: Greenfield](docs/scenarios/01-greenfield.md) | Decomposition, execution and validation of the core build |
| [Scenario 2: Brownfield](docs/scenarios/02-brownfield.md) | Click analytics added to the running system: before/after benchmark, live upgrade, rollback safety |
| [Testing approach](docs/testing.md) | Test pyramid, how to run, known gaps |
| [Limitations & tradeoffs](docs/limitations-tradeoffs.md) | What we gave up and when we'd revisit |
| [Bug log](docs/BUGS.md) | Every defect: root cause, fix, regression test |
| [Improvements backlog](docs/IMPROVEMENTS.md) | Prioritised known gaps |
| [Changelog](CHANGELOG.md) | History of changes by phase |

## Project layout
```
src/main/java/com/shortener/
  api/       HTTP: controllers, DTOs, error mapping (no business rules)
  link/      Domain: Link, LinkService, policies, code generator, repository
  analytics/ Click capture (ClickRecorder, ClickClassifier) and stats (LinkStatsService)
  config/    Typed config + Clock
  web/       Request-id filter
src/main/resources/db/migration/   Flyway SQL migrations
src/test/java/...                  Unit, web-slice and Testcontainers integration tests
scripts/smoke-test.sh              End-to-end check against a running instance
scripts/RedirectBench.java         Redirect latency/throughput benchmark (JDK 21 single-file)
docs/                              Architecture, ADRs, scenarios, logs
```
