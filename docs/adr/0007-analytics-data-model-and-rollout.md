# ADR-0007: Analytics data model, privacy, and a rollback-safe migration

- **Status:** Accepted
- **Created:** 2026-11-19
- **Accepted:** 2026-11-19

## Context
ADR-0006 decides *how* clicks are captured. This record decides what we store, how stats are
computed, how they are exposed, and how the schema change reaches a database that already holds
production links. The brownfield constraints are:

- existing clients of `/api/v1/links` must see no change;
- the migration must be safe on a populated, in-use database;
- rolling back to the Phase 1 build must still work after the migration has run.

## Options considered

**Storage shape**
| Option | For | Against |
|---|---|---|
| **Raw event table (`click_events`), aggregate at query time** | Any breakdown can be added later without a backfill; simple writes; correct by construction | Read cost grows with a link's click count |
| Pre-aggregated rollups only (per link per day) | Constant-cost reads | Each new breakdown needs a schema change and is impossible to backfill; upserts on hot rows contend (see ADR-0006, counter option) |
| Both (events + rollup job) | Best of both at scale | Two sources of truth and a job to operate; premature at current volume |

**What to store per click**
| Data | Decision | Why |
|---|---|---|
| IP address | **Not stored** | Personal data under GDPR. Not needed for the stats we offer. Geo lookup is a later option that could derive a country and then discard the IP |
| Full User-Agent | **Not stored.** Reduced to `DESKTOP / MOBILE / BOT / UNKNOWN` | Raw UAs are high-entropy fingerprinting data. The coarse class answers "is this human traffic?" |
| Full Referer URL | **Host only**, lower-cased | Referrer paths and queries can contain session tokens and personal data (`?session=…`, `/inbox/…`). The host answers "where does traffic come from?" |
| Timestamp | Stored (`timestamptz`) | Needed for time series |

**API shape**
| Option | For | Against |
|---|---|---|
| Add `clickCount` to the existing `LinkResponse` | One call for clients | Changes an existing contract; makes `GET` and `list` run aggregation queries for clients who never asked; list would be N aggregations per page |
| **New endpoint `GET /api/v1/links/{code}/stats?days=N`** | Purely additive; cost is paid only by callers who want stats | One extra call for clients who want both |

## Decision
- **Raw events** in `click_events (id, link_id → links.id, clicked_at, referrer_host, agent_class)`,
  with index `(link_id, clicked_at)` that serves every stats query. A `CHECK` constrains
  `agent_class`, and a foreign key to `links` is safe because links are never hard-deleted (ADR-0004).
- **Stats** are aggregated per **UTC day** over a window of 1–365 days (default 30), ending today. The
  daily series is zero-filled. The response also has the all-time total, last click, top 10 referrer
  hosts, clicks without a referrer, and counts by agent class. Bots are **counted and labelled**,
  not hidden. Clients decide whether to exclude them.
- **Visibility** follows `GET /api/v1/links/{code}`: expired links have stats; deleted and unknown
  codes are 404.
- **Migration `V2` is expand-only:** it creates a new table and index and touches nothing in `links`.
  It sets `SET LOCAL lock_timeout = '5s'`, because creating the FK briefly takes a lock on `links`
  that blocks writes. The lock is instant on an empty table. On a busy system the risk is waiting
  behind a long transaction while new traffic queues behind us, so failing fast and retrying is safer.
- **Rollback is safe:** Flyway ignores *applied migrations it doesn't know* by default
  (`ignoreMigrationPatterns = *:future`). A Phase 1 build therefore starts against a V2 database and
  simply never touches `click_events`. `MigrationUpgradeTest` proves both directions against real
  PostgreSQL.

## Consequences
- Stats for a link with millions of clicks scan millions of index entries. That is fine for now and
  is the trigger for daily rollups (IMP-015). The raw table also grows without bound until a
  retention policy exists (IMP-016); time-partitioning by month would make retention a cheap
  `DROP PARTITION`.
- The agent classification is a heuristic. Scripted clients that send a browser-like User-Agent count
  as DESKTOP. Good enough for "mostly human?", not for fraud detection (IMP-018).
- "UTC day" is a choice the client cannot change yet. A `tz` parameter would need the aggregation to
  run in the caller's zone (IMP-019).
- Clicks recorded before Phase 2 do not exist, so stats for older links start at the deploy date.
  The response cannot distinguish "no clicks" from "before tracking began". That gap is documented
  rather than worked around.
