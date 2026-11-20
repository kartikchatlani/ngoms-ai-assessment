# Architecture overview

> **Living document.** Updated at the end of each phase. Current as of **Phase 3**.

## 1. What the system does
A URL shortener has two core jobs, plus analytics (since Phase 2):

1. **Create:** turn a long URL into a short code (`POST /api/v1/links`).
2. **Redirect:** send anyone who opens `/{code}` to the long URL (`GET /{code}` returns 302).
3. **Analytics:** count each redirect and report clicks over time, referrers and client types
   (`GET /api/v1/links/{code}/stats`).
4. **Abuse controls** (since Phase 3): API keys and per-key ownership, rate limits on create, and
   URL screening with admin takedown. Redirects and anonymous creation stay open to everyone.

Redirects are the **hot path**. In real shorteners they outnumber creates by 100:1 or more, so
the design optimises for fast, always-available reads.

## 2. Components

```mermaid
flowchart LR
    C[Client / Browser] -->|HTTP| F[RequestIdFilter<br/>X-Request-Id + MDC]
    F --> SEC[Spring Security chain<br/>ApiKeyAuthenticationFilter<br/>default-deny URL rules]
    SEC --> RC[RedirectController<br/>GET /code]
    SEC --> LC[LinkController<br/>/api/v1/links]
    SEC --> SC[LinkStatsController<br/>/api/v1/links/code/stats]
    SEC --> AC[AdminController<br/>/api/v1/admin]
    SEC -.hash lookup.-> K[(api_keys)]
    LC --> RL[CreateRateLimiter<br/>token buckets, in memory]
    S --> US[UrlScreener<br/>blocklist in memory]
    US -.refresh 30 s.-> BD[(blocked_domains)]
    AC --> S
    AC --> US
    RC --> S[LinkService]
    LC --> S
    RC -->|offer, non-blocking| CR[ClickRecorder<br/>bounded queue]
    CR -->|batch insert<br/>background thread| CDB[(click_events)]
    SC --> SS[LinkStatsService]
    SS --> S
    SS --> CRP[ClickRepository]
    CRP --> CDB
    S --> UP[UrlPolicy]
    S --> AP[AliasPolicy]
    S --> G[RandomCodeGenerator<br/>SecureRandom base62]
    S --> R[LinkRepository<br/>JdbcClient SQL]
    R --> DB[(PostgreSQL<br/>links table)]
    EH[ApiExceptionHandler<br/>RFC 9457 Problem Details] -.maps errors.-> C
    A[Actuator<br/>liveness / readiness / metrics] --> DB
```

| Package | Responsibility |
|---|---|
| `com.shortener.api` | HTTP only: request/response DTOs, controllers, error mapping. No business rules. |
| `com.shortener.link` | Domain: `Link`, use cases (`LinkService`), policies, code generation, persistence. No HTTP types. |
| `com.shortener.analytics` | Click capture (`ClickRecorder`, `ClickClassifier`) and stats (`LinkStatsService`, `ClickRepository`). Depends on `link`, never the reverse. |
| `com.shortener.auth` | API keys: issue, SHA-256 hash, authenticate, revoke, bootstrap admin (ADR-0008). |
| `com.shortener.abuse` | `CreateRateLimiter` (ADR-0009) and `UrlScreener` with the domain blocklist (ADR-0010). Depends on nothing else in the app. |
| `com.shortener.config` | Typed, validated configuration, the injectable `Clock`, and `SecurityConfig` (who may call what). |
| `com.shortener.web` | Cross-cutting servlet concerns: request id correlation, the API-key filter, client-IP resolution. |

**Dependency rule:** `api → analytics → link → abuse`, plus `api → auth`. The domain never sees
Spring Security: controllers turn the authenticated key into a `link.Caller`, and every management
method in `LinkService` takes one, so an ownership check can't be forgotten without a compile error. The domain never imports from `api`, so the HTTP layer
could be replaced (gRPC, messaging) without touching business logic. Domain exceptions carry meaning;
only `ApiExceptionHandler` knows their HTTP status.

## 3. Data model

```sql
links (
  id           BIGINT IDENTITY PK,          -- internal only, never exposed
  code         VARCHAR(32)  UNIQUE NOT NULL, -- case-sensitive; CHECK '^[A-Za-z0-9_-]{3,32}$'
  long_url     TEXT         NOT NULL,
  custom_alias BOOLEAN      NOT NULL,
  created_at   TIMESTAMPTZ  NOT NULL,
  expires_at   TIMESTAMPTZ  NULL,           -- CHECK expires_at > created_at
  deleted_at   TIMESTAMPTZ  NULL,           -- soft delete; code never reused
  -- Phase 3, migrations V3/V4 (ADR-0008, ADR-0010)
  owner_key_id   BIGINT       NULL FK -> api_keys,  -- who can manage it; NULL = admin-only
  created_by     BIGINT       NULL FK -> api_keys,  -- never changes
  deleted_by     BIGINT       NULL FK -> api_keys,
  blocked_at     TIMESTAMPTZ  NULL,                 -- admin takedown
  blocked_reason VARCHAR(200) NULL                  -- internal; never shown to the owner
)
INDEX ix_links_live_id_desc ON links(id DESC) WHERE deleted_at IS NULL                    -- admin list
INDEX ix_links_owner_live_id_desc ON links(owner_key_id, id DESC) WHERE deleted_at IS NULL -- "my links"
```
```sql
api_keys (                                   -- Phase 3 (ADR-0008)
  id BIGINT IDENTITY PK, name VARCHAR(100), key_hash CHAR(64) UNIQUE,  -- SHA-256; the key itself is never stored
  key_prefix VARCHAR(16), admin BOOLEAN, created_at TIMESTAMPTZ, revoked_at TIMESTAMPTZ NULL
)
blocked_domains (                            -- Phase 3 (ADR-0010)
  domain VARCHAR(253) PK,                    -- CHECK lower-case; blocks subdomains too
  reason VARCHAR(200), created_at TIMESTAMPTZ, created_by BIGINT FK -> api_keys
)
```
```sql
click_events (                               -- Phase 2, migration V2 (ADR-0007)
  id            BIGINT IDENTITY PK,
  link_id       BIGINT       NOT NULL FK -> links(id),
  clicked_at    TIMESTAMPTZ  NOT NULL,
  referrer_host VARCHAR(255) NULL,          -- host only; never the path or query
  agent_class   VARCHAR(16)  NOT NULL       -- CHECK IN (DESKTOP, MOBILE, BOT, UNKNOWN)
)
INDEX ix_click_events_link_time ON click_events(link_id, clicked_at)    -- every stats query
```
The `UNIQUE(code)` constraint is the uniqueness guarantee (ADR-0003). The redirect lookup uses
its backing index. No IP addresses or raw User-Agents are stored (ADR-0007).

## 4. Request flows

**Create (generated code)**
```mermaid
sequenceDiagram
    participant C as Client
    participant S as LinkService
    participant DB as PostgreSQL
    C->>S: POST /api/v1/links {url, expiresAt?}
    S->>S: UrlPolicy.validate (scheme, host, no creds, no self-loop, length)
    loop up to 5 attempts
        S->>S: code = random base62(7)
        S->>DB: INSERT ... ON CONFLICT (code) DO NOTHING RETURNING *
        DB-->>S: row (success) or nothing (collision -> retry)
    end
    S-->>C: 201 Created + Location + LinkResponse
```

**Redirect (with click capture)**
```mermaid
sequenceDiagram
    participant B as Browser
    participant S as LinkService
    participant Q as ClickRecorder queue
    participant W as Writer thread
    participant DB as PostgreSQL
    B->>S: GET /Ab3xY9z
    S->>DB: SELECT ... WHERE code = ?  (unique index)
    alt not found
        S-->>B: 404 LINK_NOT_FOUND
    else expired / deleted
        S-->>B: 410 LINK_EXPIRED / LINK_DELETED
    else active
        S->>Q: offer(click)  (in memory, never blocks; dropped + counted if full)
        S-->>B: 302 Location: long URL, Cache-Control: no-store
    end
    Note over W,DB: asynchronously, off the request path
    W->>Q: poll + drainTo(batch, 500)
    W->>DB: batch INSERT INTO click_events
```
Only successful `GET` redirects are clicks. `HEAD` (link checkers) and 404/410 are not counted.

## 5. API summary

| Method & path | Who | Purpose | Success | Notable errors |
|---|---|---|---|---|
| `POST /api/v1/links` | anyone | Create `{url, alias?, expiresAt?}`; owned by the caller's key, if any | 201 + `Location` | 400 invalid / `URL_NOT_ALLOWED`, 409 alias taken, 429 rate-limited, 503 |
| `GET /api/v1/links/{code}` | owner, admin | Link details (incl. `status`) | 200 | 401, 404 (also: not yours) |
| `GET /api/v1/links?limit=&cursor=` | key | Your live links, newest first (admin: all) | 200 `{items, nextCursor}` | 401, 400 bad limit/cursor |
| `DELETE /api/v1/links/{code}` | owner, admin | Soft delete (idempotent); records `deleted_by` | 204 | 401, 404 |
| `GET /api/v1/links/{code}/stats?days=` | owner, admin | Click stats: total, last click, daily series (UTC), top referrers, client types | 200 | 401, 404, 400 bad `days` (1–365) |
| `GET /{code}` (and `HEAD`) | anyone | Redirect | 302 | 404 unknown, 410 expired / deleted / blocked |
| `POST, GET /api/v1/admin/keys`, `DELETE .../keys/{id}` | admin | Issue (secret shown once), list, revoke keys | 201 / 200 / 204 | 403 for client keys |
| `PUT, DELETE /api/v1/admin/links/{code}/block` | admin | Take down / restore one link | 204 | 404 |
| `PUT /api/v1/admin/links/{code}/owner` | admin | Assign a link to a key | 204 | 404 link or key |
| `POST, GET /api/v1/admin/blocked-domains`, `DELETE .../{domain}` | admin | Domain blocklist | 201 / 200 / 204 | 400 invalid domain |
| `GET /actuator/health/{liveness,readiness}` | anyone | Probes | 200 | 503 when not ready |
| `GET /actuator/metrics/**` | admin | Metrics | 200 | 401, 403 |

## 6. Reliability properties

| Concern | Mechanism |
|---|---|
| Duplicate codes, including under concurrency | DB `UNIQUE` + `ON CONFLICT DO NOTHING`; no check-then-insert races |
| Bad input reaching the DB | Bean Validation (shape), then domain policies (rules), then DB `CHECK` constraints |
| DB outage | Hikari 3s connection timeout (fail fast); 503 + `Retry-After`; readiness probe goes DOWN |
| Restart storms during DB outage | Liveness deliberately excludes the DB |
| Deploys | Graceful shutdown: in-flight requests finish (20s budget) |
| Debuggability | `X-Request-Id` on every response and every log line; JSON logs (ECS) in containers |
| Misconfiguration | `AppProperties` is validated at startup, so bad config fails fast |
| Schema drift | Flyway versioned migrations; app never auto-generates DDL |
| Analytics slowing or breaking redirects | Request thread only does an in-memory `offer`; `record()` cannot throw; writer failures are caught (ADR-0006) |
| Analytics overload | Bounded buffer (10,000): excess clicks are dropped and counted, never queued without limit |
| Clicks lost on deploy | Recorder stops after the web server has drained, then flushes; verified under load |
| Pipeline visibility | `shortener.clicks{outcome=recorded/dropped_buffer_full/dropped_write_failed}`, `shortener.clicks.pending` |
| Risky schema change | Expand-only V2 with `lock_timeout`; Phase 1 build still starts against V2 (rollback-safe, ADR-0007) |
| Feature misbehaving in production | Kill switch `APP_ANALYTICS_ENABLED=false`; redirects unaffected |

**Security properties (Phase 3)**

| Concern | Mechanism |
|---|---|
| Deleting or reading others' links | API key required; ownership checked in `LinkService`; not yours = `404`, identical to missing (ADR-0008) |
| Leaked database | Only SHA-256 of 190-bit keys stored; secrets shown once and never logged (prefix only) |
| Forgotten authorization on a new endpoint | Default-deny URL rules; unmatched paths are refused |
| Scripted mass creation | Token bucket on create: per IP (anonymous) and per key; `429` + `Retry-After` (ADR-0009) |
| Spoofed client IP | `X-Forwarded-For` ignored unless the request comes from a configured trusted proxy |
| Phishing destinations | Domain blocklist on create **and** redirect; other shorteners refused; single-link takedown (ADR-0010) |
| Probing the blocklist | One generic `URL_NOT_ALLOWED` for every rule; details only in logs |
| Blocklist lost on a DB blip | A failed refresh keeps the previous list |
| "Who did this?" | `created_by`, `deleted_by`; key prefix on every log line of the request |
| Risky schema change | V3 expand-only with FKs `NOT VALID`; V4 validates without blocking reads/writes; Phase 2 build starts against V4 |

## 7. What's next
All three phases are delivered. The main triggers for further work, all in IMPROVEMENTS.md:
- **Before scaling out to more than one instance:** shared rate-limit state (IMP-002), blocklist
  change notification (IMP-026), zero-downtime deploys (IMP-020).
- **Before real production traffic:** CI (IMP-004) and proactive URL screening (IMP-003).
- **Analytics at volume:** rollups, retention, durable pipeline (IMP-015 to IMP-017).
