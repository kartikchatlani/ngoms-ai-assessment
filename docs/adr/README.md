# Architecture Decision Records

Each significant decision gets one short record: **context, options considered, decision,
consequences**. Records are never edited to change a decision. A new ADR *supersedes* the old one,
so the history of the reasoning is preserved.

| ADR | Title | Status | Created | Accepted |
|---|---|---|---|---|
| [0001](0001-java-spring-boot-stack.md) | Java 21 + Spring Boot 4 stack | Accepted | 2026-11-18 | 2026-11-18 |
| [0002](0002-postgres-jdbc-flyway.md) | PostgreSQL with plain JDBC and Flyway | Accepted | 2026-11-18 | 2026-11-18 |
| [0003](0003-short-code-generation.md) | Random base62 codes, uniqueness enforced by the DB | Accepted | 2026-11-18 | 2026-11-18 |
| [0004](0004-redirect-semantics.md) | 302 redirects, 404 vs 410, soft delete without code reuse | Accepted | 2026-11-18 | 2026-11-18 |
| [0005](0005-api-design.md) | API conventions: versioning, Problem Details, keyset pagination, no dedupe | Accepted | 2026-11-18 | 2026-11-18 |
| [0006](0006-async-click-capture.md) | Capture clicks asynchronously, off the redirect path | Accepted | 2026-11-19 | 2026-11-19 |
| [0007](0007-analytics-data-model-and-rollout.md) | Analytics data model, privacy, and a rollback-safe migration | Accepted | 2026-11-19 | 2026-11-19 |
| [0008](0008-api-keys-and-ownership.md) | API keys and link ownership | Accepted | 2026-11-20 | 2026-11-20 |
| [0009](0009-rate-limiting.md) | Rate-limit link creation per key and per IP | Accepted | 2026-11-20 | 2026-11-20 |
| [0010](0010-url-screening-and-takedown.md) | URL screening and admin takedown | Accepted | 2026-11-20 | 2026-11-20 |
