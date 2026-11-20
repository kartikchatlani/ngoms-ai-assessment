# ADR-0006: Capture clicks asynchronously, off the redirect path

- **Status:** Accepted
- **Created:** 2026-11-19
- **Accepted:** 2026-11-19

## Context
Phase 2 adds click analytics to a service that is already running. The redirect (`GET /{code}`) is
the hot path (ADR-0004), and the requirement is that analytics must not slow it down or make it
less available. Each redirect needs to produce a click record. A click that is a few
milliseconds late is fine. A redirect that fails or waits because analytics is struggling is not.

So the question is where and when the click gets written.

## Options considered
| Option | For | Against |
|---|---|---|
| Synchronous `INSERT` in the redirect request | Simplest; exactly-once per redirect | Adds a DB write (~1 ms, more under contention) to every redirect; a slow or failing `click_events` table slows or fails redirects |
| Synchronous counter (`UPDATE links SET clicks = clicks + 1`) | One row per link, cheap reads | Every click on a viral link updates **the same row**: row-lock contention serialises them. Changes the `links` table, the table every redirect reads. Only gives a total, no breakdowns |
| **In-process bounded queue + background batch writer** | Redirect thread does a non-blocking in-memory `offer` only; writes are batched; no new infrastructure | **At-most-once**: clicks in memory are lost on a crash; under extreme overload new clicks are dropped |
| Message broker (Kafka, Redis Streams, SQS) | Durable; decouples writer scaling; replayable | New infrastructure to run, secure and monitor; the redirect still makes a network call to the broker; overkill for the current volume |
| Transactional outbox | Exactly-once with the business write | There is no business write on a redirect to attach it to; it would turn every read into a write |
| Parse access logs offline | Zero runtime cost | Needs a log pipeline; stats delayed by minutes to hours; awkward to test |

## Decision
**In-process bounded queue with a single background writer** (`ClickRecorder`):

- **Redirect side:** the request thread classifies the request (referrer host, agent class; see
  ADR-0007) and calls `queue.offer()`. It never blocks and never throws: on a full queue the click is
  dropped and counted. Only successful `GET` redirects are recorded. `HEAD` is excluded because it
  comes from link checkers, not people, and 404/410 are excluded because they are not clicks.
- **Writer side:** one platform thread does `poll` → `drainTo(batch, 500)` → one JDBC batch insert.
  This batches adaptively. At low traffic, each click is written within milliseconds. Under load,
  clicks accumulate during each insert, so the next batch is larger and DB round trips grow much more
  slowly than traffic.
- **Failure handling:** a failed batch is dropped, not retried. Retrying during a DB outage would hold
  memory and delay fresh clicks. Every outcome is a metric:
  `shortener.clicks{outcome=recorded|dropped_buffer_full|dropped_write_failed}` plus the gauge
  `shortener.clicks.pending`.
- **Graceful shutdown:** the recorder is a `SmartLifecycle` at phase 0, so it stops *after* the web
  server's graceful shutdown has finished in-flight requests. It then writes everything still buffered.
- **Kill switch:** `APP_ANALYTICS_ENABLED=false` turns recording off without a different build.
  Redirects are unaffected, and existing stats stay readable.
- **Defaults:** buffer of 10,000 clicks (about 1 MB) and batch size 500. Configurable via `app.analytics.*`.

## Consequences
- **Delivery is at-most-once.** A crash or `kill -9` loses up to `buffer-capacity` clicks. A graceful
  stop loses none. This was verified by stopping the container during a benchmark: 55,114 successful
  redirects and 55,114 stored clicks ([scenario 2 §3](../scenarios/02-brownfield.md)). Losing clicks on
  a crash is acceptable for engagement analytics. It would **not** be acceptable for billing; that
  would need a durable queue (IMP-017).
- **Stats are eventually consistent,** normally behind by milliseconds. The API documents this.
- **The redirect gets no slower per request** (one in-memory offer). The writes still land on the
  same PostgreSQL, so at saturation there is some throughput cost: about 5% in our single-laptop
  benchmark, where the app, the DB and the load generator share one machine. (The first
  estimate, from runs taken minutes apart, was about 10%; the side-by-side re-measurement in
  scenario 2 §3.3 corrected it.) In production the
  remedy is DB capacity, or moving the click store off the primary.
- **Each instance has its own buffer and writer.** This scales horizontally with no coordination,
  and N instances mean N writers.
- **Upgrade path:** `ClickRecorder.record(...)` is the only call the redirect makes. Swapping the
  in-process queue for Kafka later changes that class, not the redirect path or the read side.
