# Limitations and tradeoffs

> Current as of **Phase 2**. Each item says what we gave up, why, and what would
> change the decision.

## Tradeoffs (deliberate)

| Decision | Gained | Gave up | Revisit when |
|---|---|---|---|
| 302 + `no-store` instead of 301 (ADR-0004) | Deletes/expiry take effect instantly; every click is countable | Every click hits our servers | Never for correctness; absorb the load with caching |
| Random codes instead of a counter (ADR-0003) | Unguessable; no coordination | Tiny retryable collision chance; codes not ordered | Code space more than about 1% full: raise `APP_CODE_LENGTH` |
| Case-sensitive base62 | 45× more codes than case-insensitive at 7 chars | `abc` ≠ `ABC`; ambiguous glyphs (0/O, 1/l) | Links often read aloud or typed: offer a human-friendly alphabet |
| Soft delete, no code reuse | No link hijacking | Rows retained forever | Storage matters: purge URL text, keep tombstone |
| No dedupe of identical URLs (ADR-0005) | Per-link expiry and analytics; no info leak | Duplicate rows on client retries | Add `Idempotency-Key` support |
| Plain JDBC over JPA (ADR-0002) | Explicit SQL; atomic `ON CONFLICT` | Hand-written row mapping | Domain grows to many entities |
| Keyset pagination (ADR-0005) | Stable, O(log n) pages | No "jump to page N" | A UI needs page numbers |
| Single PostgreSQL instance | Simplicity | Redirects depend on one DB | Availability SLO demands it: read replicas plus a cache in front |
| Async in-process click capture (ADR-0006) | Redirect pays only an in-memory offer; no new infrastructure | At-most-once: a crash loses up to one buffer (10,000) of clicks; overload drops are counted, not prevented | Clicks become billing-grade: durable pipeline (IMP-017) |
| Clicks share the links database | One system to run and back up | At saturation, click inserts compete with redirects (~10% throughput in our benchmark) | Write volume hurts redirect latency: separate click store or rollups |
| Raw events, aggregated at query time (ADR-0007) | Any new breakdown works on old data | Stats cost grows with a link's click count | Stats p95 > ~100 ms or very hot links: daily rollups (IMP-015) |
| Store referrer host, agent class only; no IP (ADR-0007) | Privacy by design; little personal data to protect | No geo, no unique visitors, coarse device info | A product need justifies a documented privacy decision |
| Bots counted and labelled, not excluded | Totals match real traffic; clients choose | "Clicks" includes crawlers unless the client subtracts `BOT` | Users expect human-only counts by default |
| Separate stats endpoint (ADR-0007) | Existing responses unchanged; cost only for callers who ask | Two calls to get a link and its stats | A UI needs both together often enough |

## Limitations (known gaps, not yet addressed)

| Limitation | Risk | Planned in |
|---|---|---|
| **No authentication or ownership.** Anyone can list or delete any link | High in production | Phase 3 candidate |
| **No rate limiting** | Spam / resource exhaustion | Phase 3 candidate |
| **No malicious-URL screening** (Safe Browsing / reputation) | Our domain used for phishing | Phase 3 candidate |
| **No private-network (SSRF) guard.** `http://10.0.0.1` is accepted | Low today: we never *fetch* the URL, we only redirect to it. Becomes relevant if we add link previews | IMPROVEMENTS.md |
| **IDN hosts rejected** (`https://bücher.de`) unless the user sends punycode (`xn--bcher-kva.de`) | Usability for non-ASCII domains | IMPROVEMENTS.md |
| URLs with unencoded spaces or illegal characters are rejected rather than auto-encoded | Usability: users must paste properly encoded URLs | IMPROVEMENTS.md |
| Redirect errors (404/410) return JSON, not an HTML page | Poor experience for browser users | IMPROVEMENTS.md |
| No caching layer | Every redirect is a DB query | Once measured (IMP-005) |
| Click data grows without bound (no retention) | Storage cost; privacy (keep no longer than needed) | IMP-016 |
| Stats days are UTC only | A user in UTC-8 sees evening clicks on "tomorrow" | IMP-019 |
| Bot detection is a regex heuristic | Bots that pretend to be browsers count as DESKTOP or MOBILE | IMP-018 |
| No stats before Phase 2 was deployed | Older links show 0 clicks for their earlier life, indistinguishable from "no clicks" | IMP-021 |
| Single app instance: deploys have a few seconds of downtime | Brief unavailability on each release | IMP-020 |
| No CI: integration tests skip silently when Docker isn't running | A green local build may not be a full pass | IMP-004 |
