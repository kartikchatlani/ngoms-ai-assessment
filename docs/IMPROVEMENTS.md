# Improvements backlog

Known gaps and ideas, each with its origin date and rough priority. When an item ships, it moves
to the "Done" table with the phase that delivered it.

**Priority:** P1 = needed before real production traffic · P2 = valuable soon · P3 = nice to have

## Open

| ID | Added | Priority | Improvement | Why / notes |
|---|---|---|---|---|
| IMP-001 | 2026-11-18 | P1 | Authentication and link ownership | Today anyone can delete anyone's link. Candidate for Phase 3 |
| IMP-002 | 2026-11-18 | P1 | Rate limiting on create (per client) | Spam and resource exhaustion. Token bucket in Redis or at the gateway |
| IMP-003 | 2026-11-18 | P1 | Malicious-URL screening (e.g. Safe Browsing API, domain blocklist) | Shorteners are a favourite phishing tool. Our domain's reputation is at stake |
| IMP-004 | 2026-11-18 | P1 | CI pipeline (GitHub Actions: `./mvnw verify` with Docker for Testcontainers) | Integration tests must run on every change, not only on a dev box |
| IMP-005 | 2026-11-18 | P2 | Redirect cache (Caffeine in-process and/or Redis) | Every redirect is a DB query today. Add after a load-test baseline so the gain is measured |
| IMP-006 | 2026-11-18 | P2 | `Idempotency-Key` header on create | Safe client retries without the info leak of URL dedupe (ADR-0005) |
| IMP-007 | 2026-11-18 | P2 | HTML error pages for browser requests to `/{code}` (content negotiation) | Browser users currently see JSON on 404/410 |
| IMP-008 | 2026-11-18 | P2 | Accept IDN hosts (convert with `java.net.IDN.toASCII`) | `https://bücher.de` is rejected today |
| IMP-009 | 2026-11-18 | P2 | Load test (k6 or Gatling) for redirect p50/p99 and create throughput | Needed to justify IMP-005 with numbers. Phase 2 added `scripts/RedirectBench.java` for before/after comparisons on one machine; a real load test on separate hardware is still open |
| IMP-010 | 2026-11-18 | P3 | Human-friendly alphabet option (no 0/O/1/l/I) | For codes that are read aloud or typed |
| IMP-011 | 2026-11-18 | P3 | Private-network (SSRF) destination guard | Only matters if we ever fetch destinations (previews, unfurling) |
| IMP-012 | 2026-11-18 | P3 | OpenAPI spec (springdoc) and Swagger UI | Self-documenting API for consumers |
| IMP-013 | 2026-11-18 | P3 | Purge job: hard-delete URL text of old deleted links, keep a code tombstone | Storage hygiene and privacy (right to erasure) |
| IMP-014 | 2026-11-18 | P3 | Prometheus metrics endpoint and custom counters (creates, collisions, redirects by status) | Dashboards and alerting. Phase 2 added the click counters (`shortener.clicks*`); the rest is open |
| IMP-015 | 2026-11-19 | P2 | Daily rollup table for click stats (per link per day), kept by a periodic job | Stats scan every click of a link. Trigger: stats p95 above ~100 ms, or links with millions of clicks (ADR-0007) |
| IMP-016 | 2026-11-19 | P2 | Click retention policy plus monthly partitioning of `click_events` | Raw events grow without bound. With partitions, retention becomes `DROP PARTITION` instead of a huge `DELETE` |
| IMP-017 | 2026-11-19 | P3 | Durable click pipeline (Kafka / Redis Streams) behind `ClickRecorder` | Only if clicks become billing-grade. Today a crash can lose up to one buffer of clicks (ADR-0006) |
| IMP-018 | 2026-11-19 | P3 | Better bot detection (maintained UA database, known crawler IP ranges) | The regex heuristic misses bots that pretend to be browsers |
| IMP-019 | 2026-11-19 | P3 | `tz` parameter on stats (daily buckets in the caller's time zone) | Days are UTC only today |
| IMP-020 | 2026-11-19 | P2 | Zero-downtime deploys: two or more app instances behind a load balancer, rolling restart | Local compose has one instance, so a deploy is a few seconds of downtime. The schema side is already rolling-deploy safe (expand-only) |
| IMP-021 | 2026-11-19 | P3 | `trackingSince` in stats for links created before Phase 2 | "0 clicks" and "created before tracking existed" currently look the same |

## Done

| ID | Done | Phase | Notes |
|---|---|---|---|
| none yet | | | |
