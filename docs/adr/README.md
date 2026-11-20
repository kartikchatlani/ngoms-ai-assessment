# Architecture Decision Records

Each significant decision gets one short record: **context, options considered, decision,
consequences**. Records are never edited to change a decision. A later ADR supersedes or amends
it instead (below), so the history of the reasoning is preserved.

**When a later ADR changes an earlier one:**
- **Supersedes:** the new ADR replaces the old decision entirely. The old one's status becomes
  "Superseded by ADR-NNNN". Not used so far.
- **Amends:** the new ADR changes *part* of an earlier decision and leaves the rest in force. The new
  ADR's header says **Amends: ADR-NNNN** and what it changes; the earlier ADR's header gets
  **Amended by: ADR-NNNN**. The earlier decision text is never rewritten, so both the original
  reasoning and the change can be read side by side.
- **Correction:** a factual error in an ADR's supporting detail (a measurement, say) is fixed in place
  and noted in its header. A correction may not change the decision; that needs a new ADR.

**Sign-off:** a decision marked **(KC)** has been reviewed and signed off by the engineer
responsible for the project. Unmarked decisions are accepted but not yet signed off.

| ADR | Title | Status | Created | Accepted |
|---|---|---|---|---|
| [0001](0001-java-spring-boot-stack.md) | Java 21 + Spring Boot 4 stack | Accepted | 2026-11-18 | 2026-11-18 |
| [0002](0002-postgres-jdbc-flyway.md) | PostgreSQL with plain JDBC and Flyway | Accepted | 2026-11-18 | 2026-11-18 |
| [0003](0003-short-code-generation.md) | Random base62 codes, uniqueness enforced by the DB | Accepted | 2026-11-18 | 2026-11-18 |
| [0004](0004-redirect-semantics.md) | 302 redirects, 404 vs 410, soft delete without code reuse | Accepted; amended by [0010](0010-url-screening-and-takedown.md) | 2026-11-18 | 2026-11-18 |
| [0005](0005-api-design.md) | API conventions: versioning, Problem Details, keyset pagination, no dedupe | Accepted; amended by [0008](0008-api-keys-and-ownership.md) | 2026-11-18 | 2026-11-18 |
| [0006](0006-async-click-capture.md) | Capture clicks asynchronously, off the redirect path | Accepted; corrected | 2026-11-19 | 2026-11-19 |
| [0007](0007-analytics-data-model-and-rollout.md) | Analytics data model, privacy, and a rollback-safe migration | Accepted | 2026-11-19 | 2026-11-19 |
| [0008](0008-api-keys-and-ownership.md) | API keys and link ownership | Accepted; amends [0005](0005-api-design.md) | 2026-11-20 | 2026-11-20 |
| [0009](0009-rate-limiting.md) | Rate-limit link creation per key and per IP | Accepted | 2026-11-20 | 2026-11-20 |
| [0010](0010-url-screening-and-takedown.md) | URL screening and admin takedown | Accepted; amends [0004](0004-redirect-semantics.md) | 2026-11-20 | 2026-11-20 |
