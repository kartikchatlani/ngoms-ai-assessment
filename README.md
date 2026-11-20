# URL Shortener

A production-style URL shortener in **Java 21 / Spring Boot 4 / PostgreSQL**, built in three
documented stages: greenfield, brownfield and ambiguous. Each design decision is recorded with
its alternatives and rationale.

| Status | |
|---|---|
| Phase 1: Greenfield core (create, redirect, manage) | ✅ Done |
| Phase 2: Brownfield (click analytics on the running system) | ✅ Done |
| Phase 3: Ambiguous requirement ("lock it down": keys, rate limits, URL screening) | ✅ Done |

---

## Quick start

### Option A: Docker (recommended; needs only Docker Desktop)
```bash
docker compose up --build
# in another terminal:
./scripts/smoke-test.sh        # 31 end-to-end checks (macOS / Linux / Git Bash)
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
It creates one link per run anonymously, which counts against the anonymous limit (10/hour).
Set `API_KEY` to a client key to create it with that key instead.

## Using the API

**Who can do what (since Phase 3).** Anyone can create a link (rate-limited to 10 per hour per IP)
and follow redirects. Everything else needs an API key, and covers only the links that key created.
Keys are issued by an admin. Locally, the admin key is `sk_local_dev_admin_change_me` (set in
`docker-compose.yml`; never use it anywhere real).

Replace `Ab3xY9z` below with the `code` returned when you create a link. You can also open any
`shortUrl` in a browser to follow the redirect.

### Bash (macOS / Linux / Git Bash)
```bash
# 1. As admin, issue yourself a client key. The secret is shown once: keep it.
curl -s -X POST localhost:8080/api/v1/admin/keys \
  -H 'Authorization: Bearer sk_local_dev_admin_change_me' \
  -H 'Content-Type: application/json' -d '{"name":"me"}'
# -> 201 {"id":2,"name":"me","prefix":"sk_Ab3xY9","key":"sk_...","admin":false,...}
KEY=sk_...   # paste the "key" value

# 2. Create a short link owned by your key (works without the header too, but then nobody owns it)
curl -s -X POST localhost:8080/api/v1/links -H "Authorization: Bearer $KEY" \
  -H 'Content-Type: application/json' -d '{"url":"https://example.com/a/very/long/path"}'
# -> 201 {"code":"Ab3xY9z","shortUrl":"http://localhost:8080/Ab3xY9z","status":"ACTIVE",...}

# Custom alias + expiry
curl -s -X POST localhost:8080/api/v1/links -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/sale","alias":"black-friday","expiresAt":"2026-12-01T00:00:00Z"}'

# 3. Follow it (no key needed; 302 -> destination); every GET redirect is counted
curl -i localhost:8080/Ab3xY9z

# 4. Your link's stats, details, your links, delete (all need your key)
curl -s -H "Authorization: Bearer $KEY" 'localhost:8080/api/v1/links/Ab3xY9z/stats?days=7'
curl -s -H "Authorization: Bearer $KEY" localhost:8080/api/v1/links/Ab3xY9z
curl -s -H "Authorization: Bearer $KEY" 'localhost:8080/api/v1/links?limit=10'   # pass nextCursor back as &cursor=
curl -i -H "Authorization: Bearer $KEY" -X DELETE localhost:8080/api/v1/links/Ab3xY9z  # 204; redirect now 410
```

### PowerShell (Windows)
In Windows PowerShell, `curl` is an alias for `Invoke-WebRequest` and mangles JSON quoting, so
use `Invoke-RestMethod` (or `curl.exe` for raw responses).
```powershell
# 1. As admin, issue yourself a client key (kept in $key; the secret is shown only once)
$admin = @{ Authorization = 'Bearer sk_local_dev_admin_change_me' }
$key = Invoke-RestMethod -Method Post http://localhost:8080/api/v1/admin/keys -Headers $admin `
  -ContentType 'application/json' -Body '{"name":"me"}'
$auth = @{ Authorization = "Bearer $($key.key)" }

# 2. Create a short link owned by your key (the response is kept in $link)
$link = Invoke-RestMethod -Method Post http://localhost:8080/api/v1/links -Headers $auth `
  -ContentType 'application/json' -Body '{"url":"https://example.com/a/very/long/path"}'
$link   # -> code, shortUrl, status, ...

# Custom alias + expiry
Invoke-RestMethod -Method Post http://localhost:8080/api/v1/links -Headers $auth -ContentType 'application/json' `
  -Body '{"url":"https://example.com/sale","alias":"black-friday","expiresAt":"2026-12-01T00:00:00Z"}'

# 3. Follow it (no key needed); curl.exe shows the raw response without following
curl.exe -i "http://localhost:8080/$($link.code)"

# 4. Stats, details, your links, delete (all need your key)
Invoke-RestMethod "http://localhost:8080/api/v1/links/$($link.code)/stats?days=7" -Headers $auth | ConvertTo-Json -Depth 4
Invoke-RestMethod "http://localhost:8080/api/v1/links/$($link.code)" -Headers $auth
Invoke-RestMethod "http://localhost:8080/api/v1/links?limit=10" -Headers $auth   # pass nextCursor back as &cursor=
Invoke-RestMethod -Method Delete "http://localhost:8080/api/v1/links/$($link.code)" -Headers $auth   # 204; redirect now 410
```
Error responses (4xx/5xx) make `Invoke-RestMethod` throw. Use `curl.exe -i` to see the error body.

### Admin: dealing with abuse
```bash
ADMIN='Authorization: Bearer sk_local_dev_admin_change_me'
# Block a domain: every existing link to it (and its subdomains) returns 410 at once; new ones are refused
curl -i -X POST localhost:8080/api/v1/admin/blocked-domains -H "$ADMIN" -H 'Content-Type: application/json' \
  -d '{"domain":"evil.example","reason":"phishing reports"}'
curl -i -X DELETE localhost:8080/api/v1/admin/blocked-domains/evil.example -H "$ADMIN"     # undo
# Take down one link (its owner can't undo this), or hand an ownerless link to a key
curl -i -X PUT localhost:8080/api/v1/admin/links/Ab3xY9z/block -H "$ADMIN" -H 'Content-Type: application/json' -d '{"reason":"report #42"}'
curl -i -X PUT localhost:8080/api/v1/admin/links/Ab3xY9z/owner -H "$ADMIN" -H 'Content-Type: application/json' -d '{"keyId":2}'
# Keys
curl -s localhost:8080/api/v1/admin/keys -H "$ADMIN"                  # list (never shows secrets)
curl -i -X DELETE localhost:8080/api/v1/admin/keys/2 -H "$ADMIN"      # revoke: 401 from then on
```

Errors are [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) with a stable `errorCode`:
```json
{"status":409,"title":"Alias already taken","detail":"Alias 'black-friday' is already taken","errorCode":"ALIAS_TAKEN"}
```
Common ones since Phase 3: `401 AUTH_REQUIRED` (no key), `401 INVALID_API_KEY`, `403 FORBIDDEN`
(not an admin key), `404 LINK_NOT_FOUND` (also for links that aren't yours), `429 RATE_LIMITED`
(with `Retry-After`), `400 URL_NOT_ALLOWED`, `410 LINK_BLOCKED`.

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
| `APP_ADMIN_KEY` | *(none)* | Bootstrap admin API key, 20+ characters. Changing it revokes the old one on restart |
| `APP_ANONYMOUS_CREATES_PER_HOUR` | `10` | Links one IP may create per hour without a key |
| `APP_KEY_CREATES_PER_HOUR` | `1000` | Links one API key may create per hour (admin keys: unlimited) |
| `APP_TRUSTED_PROXIES` | *(none)* | Comma-separated proxy IPs whose `X-Forwarded-For` is believed. Leave empty unless behind a proxy |

Health (public): `/actuator/health/liveness`, `/actuator/health/readiness`. Metrics (admin key):
`/actuator/metrics`, including `shortener.clicks`, `shortener.clicks.pending` and
`shortener.ratelimit.rejected`.

Stats are eventually consistent: a click appears once the background writer has stored it,
normally within milliseconds.

## Documentation map
| Document | What's in it |
|---|---|
| [Engineering summary](docs/ENGINEERING-SUMMARY.md) | **Start here:** plan, decisions, validation, assumptions, risks and limitations on one page |
| [Architecture overview](docs/architecture.md) | Components, data model, request flows, reliability properties |
| [Decision records (ADRs)](docs/adr/README.md) | Every significant decision with the options considered |
| [Scenario 1: Greenfield](docs/scenarios/01-greenfield.md) | Decomposition, execution and validation of the core build |
| [Scenario 2: Brownfield](docs/scenarios/02-brownfield.md) | Click analytics added to the running system: before/after benchmark, live upgrade, rollback safety |
| [Scenario 3: Ambiguous](docs/scenarios/03-ambiguous.md) | "Lock it down": interpreting a vague request, assumptions, keys / rate limits / screening, contract diff |
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
  auth/      API keys: issue, hash, authenticate, revoke
  abuse/     Rate limiter, URL screening and domain blocklist
  config/    Typed config, Clock, SecurityConfig (who may call what)
  web/       Request-id filter, API-key filter, client-IP resolution
src/main/resources/db/migration/   Flyway SQL migrations
src/test/java/...                  Unit, web-slice and Testcontainers integration tests
scripts/smoke-test.sh              End-to-end check against a running instance
scripts/RedirectBench.java         Redirect latency/throughput benchmark (JDK 21 single-file)
docs/                              Architecture, ADRs, scenarios, logs
```
