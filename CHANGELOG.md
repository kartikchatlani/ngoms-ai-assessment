# Changelog

All notable changes, newest first. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/). Each entry links to the decisions (ADRs), bugs and
scenario docs behind it.

## [0.2.0] Phase 2: Brownfield click analytics

### Added
- **Click capture** on every successful `GET /{code}`. It runs off the request path: a non-blocking
  enqueue into a bounded buffer, then a background writer that batches inserts.
  `HEAD`, 404 and 410 are not counted ([ADR-0006](docs/adr/0006-async-click-capture.md)).
- **Schema** `V2__create_click_events.sql`: `click_events` table with a foreign key to `links`, an
  `agent_class` `CHECK` and a `(link_id, clicked_at)` index. Expand-only, with
  `lock_timeout` ([ADR-0007](docs/adr/0007-analytics-data-model-and-rollout.md)).
- **Stats endpoint** `GET /api/v1/links/{code}/stats?days=N` (1–365, default 30): all-time total,
  last click, zero-filled daily series (UTC), top 10 referrer hosts, clicks without a referrer, and
  counts by client type (DESKTOP / MOBILE / BOT / UNKNOWN).
- **Privacy by design:** no IP addresses or raw User-Agents are stored. Referrers are reduced to their host.
- **Operability:** metrics `shortener.clicks{outcome=recorded|dropped_buffer_full|dropped_write_failed}`
  and the gauge `shortener.clicks.pending`; kill switch `APP_ANALYTICS_ENABLED`; buffer size
  `APP_ANALYTICS_BUFFER_CAPACITY`. Clicks are flushed on graceful shutdown.
- **Benchmark** `scripts/RedirectBench.java`: redirect latency percentiles and throughput. Runs
  with a plain JDK 21, no build needed.
- **Tests:** +46 unit, +6 web-slice, +8 integration, including V1→V2 upgrade of a populated database
  and rollback compatibility (`MigrationUpgradeTest`). The smoke test now has 18 checks (adds HEAD and stats).
- **Docs:** ADR-0006 and ADR-0007, [brownfield scenario](docs/scenarios/02-brownfield.md);
  architecture, testing and limitations docs updated to Phase 2; IMP-015 to IMP-021.

### Changed
- `RedirectController` records clicks. Its HTTP behaviour (status, headers) is unchanged.
- `ApiWebTest` gained a `ClickRecorder` mock (wiring only; no assertions changed) and 3 new tests.
- No existing endpoint, response field or `links` column changed.

### Fixed
- [BUG-002](docs/BUGS.md#bug-002): the JDK HTTP client's User-Agent was classified as DESKTOP
  instead of BOT (found before release).

### Verified
- `./mvnw test` with Docker running: **159/159 tests pass, 0 skipped**. The Phase 1 suite also
  passed against the new code before any new test was written, apart from the wiring fix above.
- **In-place upgrade** of the running Phase 1 stack with its data kept: V2 applied, all 9 existing
  links intact and redirecting. Smoke test **18/18**.
- **Redirect benchmark before vs after:** p50 unchanged (~2 ms), p99 within noise, about 10% lower
  throughput at saturation (click inserts share the laptop's DB). No clicks dropped.
- **Graceful shutdown under load:** 55,114 successful redirects, 55,114 stored clicks.
- Details and output in [scenario 2 §3](docs/scenarios/02-brownfield.md).

### Known gaps
- At-most-once delivery: a crash can lose up to one buffer of clicks ([ADR-0006](docs/adr/0006-async-click-capture.md)).
- No rollups or retention yet (IMP-015, IMP-016). One app instance, so deploys have a few seconds of downtime (IMP-020).

## [0.1.0] Phase 1: Greenfield core

### Added
- **Project scaffold:** Java 21, Spring Boot 4.1.2, Maven wrapper (Maven 3.9.16)
  ([ADR-0001](docs/adr/0001-java-spring-boot-stack.md)).
- **Schema** `V1__create_links.sql`: `links` table with `UNIQUE(code)`, format and expiry `CHECK`
  constraints, and a partial index for pagination ([ADR-0002](docs/adr/0002-postgres-jdbc-flyway.md)).
- **Create link** `POST /api/v1/links`: random 7-char base62 codes with collision retry, optional
  custom alias, optional expiry ([ADR-0003](docs/adr/0003-short-code-generation.md)).
- **Redirect** `GET|HEAD /{code}`: 302 with `Cache-Control: no-store`; 404 unknown, 410
  expired/deleted ([ADR-0004](docs/adr/0004-redirect-semantics.md)).
- **Manage links:** `GET /api/v1/links/{code}`, `GET /api/v1/links` (keyset pagination),
  `DELETE /api/v1/links/{code}` (idempotent soft delete) ([ADR-0005](docs/adr/0005-api-design.md)).
- **URL safety policy:** http/https only, host required, credentials rejected, self-links rejected,
  length cap.
- **Alias policy:** `[A-Za-z0-9_-]{3,32}`, reserved words blocked case-insensitively.
- **Error contract:** RFC 9457 Problem Details with stable `errorCode`. DB outage gives 503 + `Retry-After`.
- **Operability:** liveness/readiness probes (readiness includes DB), graceful shutdown,
  `X-Request-Id` correlation in logs and responses, JSON logs in containers, validated config.
- **Packaging:** multi-stage Dockerfile (non-root JRE runtime), `docker-compose.yml` with Postgres 17.
  Verified: `docker compose up --build` starts the stack.
- **Tests:** 67 unit, 23 web-slice, 9 integration (Testcontainers against PostgreSQL 17);
  `scripts/smoke-test.sh` (15 end-to-end checks; on Windows run it as `bash ./scripts/smoke-test.sh`).
- **Docs:** architecture overview, ADRs 0001–0005 (ADR-0001 includes WebFlux/Reactor as a rejected
  option), greenfield scenario, testing approach, limitations/tradeoffs, bug log, improvements backlog.

### Fixed
- [BUG-001](docs/BUGS.md#bug-001): far-future `expiresAt` returned 500; now 400.

### Verified
- `./mvnw test` with Docker running: **99/99 tests pass, 0 skipped**.
- Smoke test against the Docker Compose stack: **15/15 pass**.
- Details and output in [scenario 1 §3.2](docs/scenarios/01-greenfield.md).

### Known gaps
- No CI yet. Integration tests skip when Docker isn't running, so a green local build
  without Docker is not a full pass ([IMP-004](docs/IMPROVEMENTS.md)).
