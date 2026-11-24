# Setup and usage guide

From a fresh machine to a running, tested service in about 10 minutes. Commands are given for
**bash** (macOS, Linux, Git Bash on Windows) and **PowerShell** (Windows) where they differ.

---

## 1. Prerequisites

| Tool | Needed for | Check |
|---|---|---|
| **Git** | Getting the code | `git --version` |
| **Docker Desktop** (or Docker Engine + Compose) | Running the service; integration tests | `docker compose version` |
| **JDK 21** *(optional)* | Running the tests and the benchmark outside Docker | `java -version` shows 21 |
| **bash** *(Windows: comes with Git)* | The smoke test script | `bash --version` |

Maven is **not** needed: the repo includes the Maven wrapper (`mvnw`), which downloads it.
Port **8080** (app) and **5432** (PostgreSQL) must be free.

## 2. Get the code

```bash
git clone https://github.com/kartikchatlani/ngoms-ai-assessment.git
cd ngoms-ai-assessment
```

## 3. Start the service

Start Docker Desktop first, then:

```bash
docker compose up --build
```

This builds the app image and starts the app with PostgreSQL 17. Database migrations run
automatically on startup. The first build downloads dependencies and takes a few minutes; later
builds are faster.

The service is ready when the log shows `Started UrlShortenerApplication`. Check it from a second
terminal:

```bash
curl http://localhost:8080/actuator/health        # ... "status":"UP"
```

**Run it in the background instead:** `docker compose up --build -d`, then
`docker compose logs -f app` to follow the logs (Ctrl+C stops following, not the app).

**Stop it:** `docker compose down` keeps the data. `docker compose down -v` also deletes the
database volume.

## 4. Check that everything works

```bash
bash ./scripts/smoke-test.sh          # expect: Result: 31 passed, 0 failed
```

On Windows, run it through `bash` as shown. Opening the `.sh` file directly in PowerShell just
opens it in an editor.

## 5. Use the service

### Who can do what
| Action | Needs |
|---|---|
| Follow a short link (`GET /{code}`) | Nothing |
| Create a link | Nothing. Without a key: 10 per hour per IP, and nobody owns the link |
| View, list, delete your links; see their stats | An API key; covers only links created with that key |
| Issue keys, block domains, take links down, see metrics | The admin key |

Locally, the admin key is **`sk_local_dev_admin_change_me`**, set in `docker-compose.yml`. It is
for local use only.

### Bash
```bash
ADMIN='Authorization: Bearer sk_local_dev_admin_change_me'

# 1. Issue yourself a client key. The secret is shown once, so copy the "key" value.
curl -s -X POST localhost:8080/api/v1/admin/keys -H "$ADMIN" \
  -H 'Content-Type: application/json' -d '{"name":"me"}'
KEY=sk_...                                        # paste it here
AUTH="Authorization: Bearer $KEY"

# 2. Create a link (optionally with "alias" and "expiresAt")
curl -s -X POST localhost:8080/api/v1/links -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"url":"https://example.com/a/very/long/path"}'
# -> {"code":"Ab3xY9z","shortUrl":"http://localhost:8080/Ab3xY9z","status":"ACTIVE",...}
CODE=Ab3xY9z                                      # paste the "code" value

# 3. Follow it (also works in a browser: open the shortUrl)
curl -i localhost:8080/$CODE                      # 302, Location: https://example.com/...

# 4. Manage it
curl -s -H "$AUTH" localhost:8080/api/v1/links/$CODE          # details
curl -s -H "$AUTH" 'localhost:8080/api/v1/links?limit=10'     # your links
curl -i -H "$AUTH" -X DELETE localhost:8080/api/v1/links/$CODE  # 204; the redirect is now 410
```

### PowerShell
```powershell
$admin = @{ Authorization = 'Bearer sk_local_dev_admin_change_me' }

# 1. Issue yourself a client key (kept in $key)
$key  = Invoke-RestMethod -Method Post http://localhost:8080/api/v1/admin/keys -Headers $admin `
          -ContentType 'application/json' -Body '{"name":"me"}'
$auth = @{ Authorization = "Bearer $($key.key)" }

# 2. Create a link (kept in $link)
$link = Invoke-RestMethod -Method Post http://localhost:8080/api/v1/links -Headers $auth `
          -ContentType 'application/json' -Body '{"url":"https://example.com/a/very/long/path"}'
$link.shortUrl

# 3. Follow it (curl.exe shows the raw 302 instead of following it)
curl.exe -i "http://localhost:8080/$($link.code)"

# 4. Manage it
Invoke-RestMethod "http://localhost:8080/api/v1/links/$($link.code)" -Headers $auth
Invoke-RestMethod "http://localhost:8080/api/v1/links?limit=10" -Headers $auth
Invoke-RestMethod -Method Delete "http://localhost:8080/api/v1/links/$($link.code)" -Headers $auth
```
Keep each PowerShell session open: `$key`, `$auth` and `$link` exist only in the window where you
created them. In PowerShell, `curl` means `Invoke-WebRequest`, so use `curl.exe` for the real
curl. `Invoke-RestMethod` throws on 4xx/5xx; use `curl.exe -i` to see the error body.

### Errors
Every error is JSON with a stable `errorCode`, for example:

| errorCode | Status | Meaning |
|---|---|---|
| `INVALID_REQUEST` | 400 | Bad input |
| `URL_NOT_ALLOWED` | 400 | Blocked domain, or another URL shortener |
| `AUTH_REQUIRED` / `INVALID_API_KEY` | 401 | No key, or an unknown or revoked key |
| `FORBIDDEN` | 403 | Admin-only endpoint |
| `LINK_NOT_FOUND` | 404 | Also returned for links that aren't yours |
| `ALIAS_TAKEN` | 409 | That alias is in use |
| `LINK_EXPIRED` / `LINK_DELETED` / `LINK_BLOCKED` | 410 | The link existed but no longer works |
| `RATE_LIMITED` | 429 | Too many creates; see `Retry-After` |

Every endpoint is listed in [architecture.md §5](docs/architecture.md#5-api-summary).

## 6. Use the analytics

Every successful `GET` redirect is counted in the background, normally within milliseconds.
`HEAD` requests and failed redirects (404/410) are not counted.

**Generate some clicks.** Open the short link in a browser a few times, or simulate visitors.
`-A` sets the User-Agent and `-e` the Referer, which are what the stats break down by:
```bash
curl -s -o /dev/null localhost:8080/$CODE                                                     # BOT (curl)
curl -s -o /dev/null -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" -e "https://news.ycombinator.com/item?id=1" localhost:8080/$CODE   # DESKTOP
curl -s -o /dev/null -A "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0) Mobile" -e "https://t.co/abc" localhost:8080/$CODE                      # MOBILE
```
(PowerShell: the same commands with `curl.exe` and `-o NUL`.)

**Read the stats** (owner or admin key; `days` = 1–365, default 30):
```bash
curl -s -H "$AUTH" "localhost:8080/api/v1/links/$CODE/stats?days=7"
```
```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/links/$($link.code)/stats?days=7" -Headers $auth | ConvertTo-Json -Depth 4
```

| Field | Meaning |
|---|---|
| `totalClicks`, `lastClickedAt` | All-time count and most recent click |
| `window` | The date range (UTC days) and the clicks in it |
| `daily` | Clicks per UTC day, oldest first; empty days show 0 |
| `topReferrers` | Up to 10 referring sites, **host only** (no paths or query strings are stored) |
| `withoutReferrer` | Clicks with no Referer header |
| `agents` | Counts by client type: `DESKTOP`, `MOBILE`, `BOT`, `UNKNOWN` |

No IP addresses or full User-Agent strings are stored.

**Pipeline health** (admin key): `shortener.clicks{outcome=...}` counts recorded and dropped clicks,
and `shortener.clicks.pending` shows clicks not yet written.
```bash
curl -s -H "$ADMIN" "localhost:8080/actuator/metrics/shortener.clicks?tag=outcome:recorded"
```

**Raw data**, if you're curious:
```bash
docker compose exec postgres psql -U shortener -d shortener -c \
  "SELECT l.code, c.clicked_at, c.referrer_host, c.agent_class FROM click_events c JOIN links l ON l.id = c.link_id ORDER BY c.id DESC LIMIT 10"
```

## 7. Test

**Full suite** (Docker must be running):
```bash
./mvnw test              # PowerShell: .\mvnw.cmd test
```
Expect `Tests run: 241, Failures: 0, Errors: 0, Skipped: 0`. That covers unit tests, web-slice
tests, and integration tests against a real PostgreSQL started by Testcontainers. The integration
tests don't touch the Compose database. **If Docker isn't running, the integration tests are
skipped, not failed**, so check that `Skipped` is 0.

**Specific tests:**
```bash
./mvnw test -Dtest=AbuseIntegrationTest          # one class
./mvnw test -Dtest='Link*Test'                   # a pattern
```

**Against the running service:**
```bash
bash ./scripts/smoke-test.sh                                      # 31 end-to-end checks
BASE=http://host:port ADMIN_KEY=... bash ./scripts/smoke-test.sh  # another deployment
```

**Redirect benchmark** (JDK 21 only, no build step):
```bash
java scripts/RedirectBench.java http://localhost:8080 20000 32    # requests, concurrency
```
It creates one link per run. Without a key, that counts against the anonymous limit; set
`API_KEY=<client key>` to avoid it.

How the tests are organised: [testing.md](docs/testing.md).

## 8. Configuration

Settings are environment variables with local defaults. To change one, add it under
`app: environment:` in `docker-compose.yml`, then run `docker compose up -d`. The main ones:

| Variable | Default | Purpose |
|---|---|---|
| `APP_BASE_URL` | `http://localhost:8080` | Public origin used in `shortUrl` |
| `APP_ADMIN_KEY` | *(none outside Compose)* | Bootstrap admin key; changing it revokes the old one |
| `APP_ANONYMOUS_CREATES_PER_HOUR` / `APP_KEY_CREATES_PER_HOUR` | `10` / `1000` | Rate limits |
| `APP_ANALYTICS_ENABLED` | `true` | Click-recording kill switch |
| `APP_TRUSTED_PROXIES` | *(none)* | Only set this behind a reverse proxy |

The full list is in the [README](README.md#configuration).

## 9. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `failed to connect to the docker API` | Docker Desktop isn't running. Start it and wait until it says it's running |
| `port is already allocated` | Something else uses 8080 or 5432. Stop it, or change the left-hand port in `docker-compose.yml` |
| `429 RATE_LIMITED` on create | The anonymous limit is 10 per hour. Locally, all requests share one IP, because Docker forwards them through a single gateway address. Use a key, or raise `APP_ANONYMOUS_CREATES_PER_HOUR` |
| `401` on get/list/delete/stats | Send `Authorization: Bearer <key>` |
| `404` for a link you just created | It was created with a different key, or without one. Anonymous links are admin-only |
| Integration tests show `Skipped` | Docker wasn't running when the tests started |
| App exits with a Flyway "checksum mismatch" | An already-applied migration file was edited. Locally: `docker compose down -v` (deletes the data) |
| `.sh` file opens in an editor | Run it as `bash ./scripts/smoke-test.sh` |
