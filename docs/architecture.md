# Architecture overview

> **Living document.** Updated at the end of each phase. Current as of **Phase 1**.

## 1. What the system does
A URL shortener has two jobs:

1. **Create:** turn a long URL into a short code (`POST /api/v1/links`).
2. **Redirect:** send anyone who opens `/{code}` to the long URL (`GET /{code}` returns 302).

Redirects are the **hot path**. In real shorteners they outnumber creates by 100:1 or more, so
the design optimises for fast, always-available reads.

## 2. Components (Phase 1)

```mermaid
flowchart LR
    C[Client / Browser] -->|HTTP| F[RequestIdFilter<br/>X-Request-Id + MDC]
    F --> RC[RedirectController<br/>GET /code]
    F --> LC[LinkController<br/>/api/v1/links]
    RC --> S[LinkService]
    LC --> S
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
| `com.shortener.config` | Typed, validated configuration (`AppProperties`) and the injectable `Clock`. |
| `com.shortener.web` | Cross-cutting servlet concerns (request id correlation). |

**Dependency rule:** `api → link → (DB)`. The domain never imports from `api`, so the HTTP layer
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
  deleted_at   TIMESTAMPTZ  NULL            -- soft delete; code never reused
)
INDEX ix_links_live_id_desc ON links(id DESC) WHERE deleted_at IS NULL   -- list pagination
```
The `UNIQUE(code)` constraint is the uniqueness guarantee (ADR-0003). The redirect lookup uses
its backing index.

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

**Redirect**
```mermaid
sequenceDiagram
    participant B as Browser
    participant S as LinkService
    participant DB as PostgreSQL
    B->>S: GET /Ab3xY9z
    S->>DB: SELECT ... WHERE code = ?  (unique index)
    alt not found
        S-->>B: 404 LINK_NOT_FOUND
    else expired / deleted
        S-->>B: 410 LINK_EXPIRED / LINK_DELETED
    else active
        S-->>B: 302 Location: long URL, Cache-Control: no-store
    end
```

## 5. API summary

| Method & path | Purpose | Success | Notable errors |
|---|---|---|---|
| `POST /api/v1/links` | Create `{url, alias?, expiresAt?}` | 201 + `Location` | 400 invalid, 409 alias taken, 503 exhausted / DB down |
| `GET /api/v1/links/{code}` | Link details (incl. `status`) | 200 | 404 |
| `GET /api/v1/links?limit=&cursor=` | List live links, newest first | 200 `{items, nextCursor}` | 400 bad limit/cursor |
| `DELETE /api/v1/links/{code}` | Soft delete (idempotent) | 204 | 404 never existed |
| `GET /{code}` (and `HEAD`) | Redirect | 302 | 404 unknown, 410 expired/deleted |
| `GET /actuator/health/{liveness,readiness}` | Probes | 200 | 503 when not ready |

## 6. Reliability properties (Phase 1)

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

## 7. What's next
- **Phase 2 (brownfield):** click analytics added to the running system without slowing
  redirects or breaking existing clients.
- **Phase 3 (ambiguous):** an under-specified requirement, taken from interpretation to delivery.
