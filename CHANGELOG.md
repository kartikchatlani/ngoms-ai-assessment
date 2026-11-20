# Changelog

All notable changes, newest first. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/). Each entry links to the decisions (ADRs), bugs and
scenario docs behind it.

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
