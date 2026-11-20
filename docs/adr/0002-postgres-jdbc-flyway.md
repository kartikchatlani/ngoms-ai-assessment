# ADR-0002: PostgreSQL with plain JDBC (JdbcClient) and Flyway migrations

- **Status:** Accepted
- **Created:** 2026-11-18
- **Accepted:** 2026-11-18

## Context
It needs durable storage with a hard uniqueness guarantee on short codes, atomic
"insert if absent" (check and insert in one database step, so concurrent requests can't both
claim the same code), and schema evolution that works on a live system (for the brownfield phase).

## Options considered
**Database**
| Option | For | Against |
|---|---|---|
| **PostgreSQL** | Unique constraints, `INSERT ... ON CONFLICT`, partial indexes, CHECK constraints, mature replication; easy to run in Docker | Single primary for writes (fine at this scale) |
| Redis as primary store | Very fast reads | Durability is configurable but weaker by default; no constraints; analytics queries are awkward |
| DynamoDB / Cassandra | Massive horizontal scale | Overkill for a prototype; conditional writes are possible but tooling and local dev are harder |

**Data access**
| Option | For | Against |
|---|---|---|
| **Spring `JdbcClient` (plain SQL)** | SQL is visible and reviewable; native `ON CONFLICT ... RETURNING`; no ORM session or dirty-checking surprises | Hand-written row mapping (a few lines) |
| Spring Data JPA / Hibernate | Less boilerplate for CRUD | Collision handling becomes exception-driven (a constraint violation poisons the transaction); `ON CONFLICT` needs native queries anyway; hides the SQL that matters most here |
| jOOQ | Type-safe SQL | Code generation step and extra dependency for one table |

**Migrations:** Flyway (versioned SQL files, run on startup) vs Liquibase (XML/YAML changelogs).
Flyway chosen: plain SQL, simpler, and Boot auto-configures it.

## Decision
PostgreSQL 17, accessed with `JdbcClient`, schema owned by Flyway (`src/main/resources/db/migration`).
The application never runs DDL by itself (no `ddl-auto`).

## Consequences
- The schema is defined in one reviewed SQL file per version. Constraints (`UNIQUE`, `CHECK`) are a
  second line of defence behind application validation.
- Timestamps are `TIMESTAMPTZ` and are converted explicitly to and from `OffsetDateTime` (UTC), because the
  PostgreSQL JDBC driver does not bind `Instant` directly.
- Moving to JPA later would be possible but is not planned.
