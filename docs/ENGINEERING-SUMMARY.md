# Engineering summary

- **Created:** 2026-11-20
- **Scope:** a production-style URL shortener in Java 21 / Spring Boot 4 / PostgreSQL, built in
  three stages. Each stage is documented as decomposition → execution → validation.
- **Run it:** `docker compose up --build`, then `bash ./scripts/smoke-test.sh`. Full guide: [SETUP.md](SETUP.md).

---

## 1. What was built

| Phase | Scenario | Delivered | Scenario doc |
|---|---|---|---|
| 1 | **Greenfield:** empty repo | Create, redirect, manage links; random base62 codes; expiry; soft delete; Problem Details errors; health probes; Docker | [01-greenfield](scenarios/01-greenfield.md) |
| 2 | **Brownfield:** change a running system | Click analytics captured off the redirect path; stats endpoint; rollback-safe migration | [02-brownfield](scenarios/02-brownfield.md) |
| 3 | **Ambiguous:** "lock it down" | API keys and ownership; rate limits on create; domain blocklist and takedown | [03-ambiguous](scenarios/03-ambiguous.md) |

Current design: [architecture.md](architecture.md).

## 2. Plan and reasoning

The approach was the same in every phase: decide what must be true before deciding how, record
each significant decision with the options rejected, and prove the result at the level where it
could fail.

| Decision | Why | ADR |
|---|---|---|
| Spring MVC on virtual threads, not WebFlux | Blocking JDBC concurrency without reactive complexity | [0001](adr/0001-java-spring-boot-stack.md) |
| PostgreSQL + plain SQL (`JdbcClient`) + Flyway | The database guarantees uniqueness (`ON CONFLICT`); the SQL stays visible | [0002](adr/0002-postgres-jdbc-flyway.md) |
| Random 7-char base62 codes | Unguessable; no coordination; collisions retried | [0003](adr/0003-short-code-generation.md) |
| 302 + `no-store`, soft delete, codes never reused | Deletes and expiry take effect immediately; no link hijacking | [0004](adr/0004-redirect-semantics.md) |
| `/api/v1`, Problem Details, keyset pagination | Stable error contract; consistent pages | [0005](adr/0005-api-design.md) |
| Clicks queued in memory, written in batches | The redirect pays one in-memory offer, never a DB write | [0006](adr/0006-async-click-capture.md) |
| Raw click events; host-only referrer; no IP | Flexible stats; privacy by design; expand-only migration | [0007](adr/0007-analytics-data-model-and-rollout.md) |
| Admin-issued API keys; not yours = 404 | Fixes unauthorised deletes without making the public sign up | [0008](adr/0008-api-keys-and-ownership.md) |
| Token bucket per key / per IP on create only | Stops scripts; normal users and redirects unaffected | [0009](adr/0009-rate-limiting.md) |
| Blocklist checked on create **and** redirect | Blocking a domain disables existing phishing links at once | [0010](adr/0010-url-screening-and-takedown.md) |

## 3. Artifacts

| Artifact | Where |
|---|---|
| Source (packages `api`, `link`, `analytics`, `auth`, `abuse`, `config`, `web`) | [src/main/java/com/shortener](../src/main/java/com/shortener) |
| Schema migrations V1–V4 | [db/migration](../src/main/resources/db/migration) |
| Tests: 241 (166 unit, 45 web-slice, 30 integration on real PostgreSQL) | [src/test](../src/test/java/com/shortener) |
| Smoke test (31 checks); redirect benchmark | [scripts/](../scripts) |
| Docker image and Compose stack | [Dockerfile](../Dockerfile), [docker-compose.yml](../docker-compose.yml) |
| 10 ADRs; 3 scenario docs; architecture | [adr/](adr/README.md), [scenarios/](scenarios), [architecture.md](architecture.md) |
| Bug log (3), improvements backlog (25 open), changelog | [BUGS.md](BUGS.md), [IMPROVEMENTS.md](IMPROVEMENTS.md), [CHANGELOG](../CHANGELOG.md) |

## 4. How it was validated

**Test levels.** Each level answers a different question ([testing.md](testing.md)):

- **Unit tests:** the business rules.
- **Web-slice tests:** the HTTP contract, with the real security rules applied.
- **Integration tests:** SQL, constraints, concurrency and security end to end, using
  Testcontainers PostgreSQL rather than H2.
- **Smoke test:** the deployed container.

Every bug has a named regression test.

**Brownfield techniques (Phases 2 and 3):**
- **Benchmark before changing anything,** so "after" has a baseline.
- **Run the previous release's tests and smoke test unchanged** against the new code. Phase 3's
  13 failing tests and 7 failing smoke checks were all intended contract changes, and nothing else
  failed.
- **Upgrade the running stack in place,** keeping its data (22 links and 209,152 clicks moved to V4
  in 37 ms).
- **Roll back for real:** the released Phase 1 and Phase 2 builds start and serve traffic against
  the V4 database.
- **Shut down gracefully under load:** 55,114 redirects produced 55,114 stored clicks.

**Performance.** All three releases ran side by side against one database, in 6 rounds with the
run order rotated ([scenario 3 §3.5](scenarios/03-ambiguous.md)):

| | Throughput (median) | p50 |
|---|---|---|
| Phase 1 | 8,913 req/s | 2.09 ms |
| Phase 2 (+ analytics) | 8,433 req/s | 2.00 ms |
| Phase 3 (+ security) | 8,142 req/s | 2.58 ms |

That's about 5% for analytics and about 3.5% plus 0.6 ms for security. An earlier measurement
taken minutes apart overstated both; the correction is documented rather than hidden.

## 5. Assumptions

| Assumption | Consequence if wrong |
|---|---|
| One app instance; no reverse proxy | Rate limits and the blocklist cache are per instance, so they need Redis or pub/sub (IMP-002, IMP-026) |
| Anonymous users keep creating links; known teams get keys | One security rule makes create key-only |
| 10 anonymous / 1,000 per-key creates per hour | Configuration only |
| No approved external URL-reputation service | Add Safe Browsing behind the existing `UrlScreener` (IMP-003) |
| No identity provider | Keys map onto OIDC tokens later; the domain's `Caller` type stays the same |
| Losing buffered clicks on a crash is acceptable (engagement analytics, not billing) | Durable queue behind `ClickRecorder` (IMP-017) |
| Single-laptop benchmarks are for relative comparison only | A real load test on separate hardware (IMP-009) |

All of Phase 3's assumptions, with the questions behind them:
[scenario 3 §1.2 and §3.6](scenarios/03-ambiguous.md).

## 6. Risks

| Risk | Mitigation in place | Residual |
|---|---|---|
| Phishing through our domain | Blocklist on create and redirect; takedown; no chained shorteners | Reactive: new domains work until reported |
| Unauthorised management of links | Keys, ownership, default-deny rules; `deleted_by` audit | A leaked key can manage its owner's links until revoked |
| Scripted abuse | Token buckets; spoofed `X-Forwarded-For` ignored | Shared IPs share a limit; per-instance counts |
| Analytics hurting redirects | Non-blocking enqueue; bounded buffer; failures counted | Click inserts share the database |
| Risky schema changes | Expand-only migrations; `lock_timeout`; FKs `NOT VALID` then validated; rollback tested | Large-table index builds would need `CONCURRENTLY` |
| Untested changes reaching production | 241 tests, smoke test | **No CI yet** (IMP-004): integration tests skip without Docker |

## 7. Trade-offs

The main ones, each with what was gained, what was given up, and when to revisit, are in
[limitations-tradeoffs.md](limitations-tradeoffs.md):

- **302 instead of 301:** correctness and counting over cacheability.
- **Random codes instead of a counter:** unguessable, at the cost of a retryable collision chance.
- **Async click capture:** a fast redirect, at the cost of at-most-once delivery.
- **Raw click events instead of rollups:** flexible stats, at the cost of read cost growing with clicks.
- **Admin-issued keys instead of accounts:** small and testable, but no self-service.
- **An intentional breaking change** on unauthenticated management endpoints, because the open
  contract was the vulnerability.
- **Default-deny security:** safe by default, but unknown paths return 401 instead of 404.

## 8. Limitations

The most significant known gaps ([full list](limitations-tradeoffs.md#limitations-known-gaps-not-yet-addressed);
[backlog](IMPROVEMENTS.md)):

- **Operations:** no CI pipeline, and single-instance only (deploys have seconds of downtime;
  limits and caches are per instance).
- **Safety:** URL screening is reactive. There is no public "report a link" endpoint, and no SSRF
  guard (none is needed while destinations are never fetched).
- **Analytics:** no rollups, retention or partitioning; days are UTC only; bot detection is a heuristic.
- **API:** JSON errors on browser redirects (no HTML pages); IDN hosts rejected; no `Idempotency-Key`
  on create.

## 9. Bugs found

Three bugs were found, all before release, and each has a root cause, a fix and a regression test
in [BUGS.md](BUGS.md):

- **BUG-001:** a far-future expiry caused a 500.
- **BUG-002:** the JDK HTTP client was classified as a desktop browser.
- **BUG-003:** a missing optional JSON field was rejected, because of Jackson 3's handling of
  primitives.
