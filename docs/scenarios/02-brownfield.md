# Scenario 2: Brownfield: click analytics on the running system

- **Created:** 2026-11-19
- **Starting point:** the Phase 1 service ([scenario 1](01-greenfield.md)), deployed and holding
  data, with existing API clients and a 99-test suite.
- **Goal:** answer "how is my link doing?" (clicks over time, where they come from, human or bot)
  **without** slowing redirects, changing existing API contracts, or making the deploy or a
  rollback risky.

---

## 1. Decomposition

### 1.1 What makes this brownfield
In greenfield the only constraint is the requirement. Here the running system adds four more, and
they shaped the design more than the feature did:

| Constraint | Consequence for the design |
|---|---|
| Redirects are the hot path and already fast | Click capture must cost the redirect ~nothing → asynchronous (ADR-0006) |
| Existing clients use `/api/v1/links` | No changes to existing responses; stats are a **new** endpoint (ADR-0007) |
| The database already holds production links | Migration must be additive and safe on a populated, in-use table |
| Deploys can go wrong | Rolling back to Phase 1 must work **after** V2 has run; there must be an off switch |

### 1.2 Requirements → capabilities
| Need | Capability | Where |
|---|---|---|
| Count every real click | Record successful GET redirects, not HEAD, 404 or 410 | `RedirectController`, `ClickRecorder.record` |
| Don't slow redirects | Non-blocking enqueue + background batch writer | `ClickRecorder` (ADR-0006) |
| Clicks over time | Daily series, zero-filled, UTC | `LinkStatsService`, `ClickRepository.daily` |
| Traffic sources | Top referrer hosts | `ClickClassifier.referrerHost`, `ClickRepository.topReferrers` |
| Human vs bot | Coarse agent class | `ClickClassifier.agentClass` |
| Privacy | No IP, no raw UA, referrer host only | `ClickClassifier`, `V2__create_click_events.sql` (ADR-0007) |
| Observe the pipeline | Recorded/dropped counters, pending gauge | `shortener.clicks*` metrics |
| Safe rollout | Expand-only migration, `lock_timeout`, kill switch | `V2`, `APP_ANALYTICS_ENABLED` |

### 1.3 Risk register
| Risk | Why it matters | Mitigation | Proof |
|---|---|---|---|
| Analytics slows redirects | Every user pays for it | In-memory offer only on the request thread | Before/after benchmark (§3.3); `fullBufferDropsClicksInsteadOfBlocking` |
| Analytics DB failure breaks redirects | An optional feature takes down the core one | `record()` cannot throw; writer failures are caught and counted | `writeFailureDropsTheBatchAndTheWriterKeepsGoing`, `recordNeverThrowsEvenIfTheClockFails` |
| Hot-link contention | A viral link serialises on one row | Append-only inserts, no shared counter row | ADR-0006 options; `concurrentRedirectsAreAllCounted` (500 parallel) |
| Clicks lost on deploy | Numbers drop at every release | Writer stops after the web server drains, then flushes | Mid-load `docker compose stop` (§3.4): 55,114 / 55,114 |
| Migration locks `links` | Redirects or creates stall during deploy | New table only; `SET LOCAL lock_timeout = '5s'` | ADR-0007; reads unaffected by the FK lock |
| Rollback impossible after migrating | A bad release can't be reverted | Expand-only + Flyway ignores unknown future migrations | `MigrationUpgradeTest` step 3 |
| Old data breaks the upgrade | Production has expired/deleted rows | Test the upgrade on a populated V1 DB, and on the real running DB | `MigrationUpgradeTest`; live upgrade (§3.4) |
| Existing behaviour changes unnoticed | Regressions in Phase 1 features | Run the unchanged Phase 1 suite first | §3.2 |
| Privacy leak through referrers | Tokens in URLs stored forever | Store host only | `onlyTheReferrerHostIsStored` |

### 1.4 Out of scope (and why)
- **Geo (country) stats:** needs a GeoIP database and a licence decision. Could be added
  later by deriving a country at capture time and discarding the IP (ADR-0007).
- **Unique visitors:** needs a visitor identifier (cookie or IP hash), which is a privacy decision
  that should not be made implicitly.
- **Rollups, retention, partitioning:** not needed at current volume. Triggers recorded as IMP-015/016.
- **Zero-downtime deploy:** needs ≥2 app instances behind a load balancer; the local compose stack has one.
  The *schema* side is ready for it (rolling deploys need old and new code to run against the same
  schema, which expand-only guarantees). IMP-020.

### 1.5 Work breakdown (order of execution)
1. **Measure first:** benchmark redirects on the unchanged Phase 1 build (baseline, §3.3).
2. Decide capture strategy (ADR-0006) and data model / rollout (ADR-0007).
3. Migration V2 (expand-only).
4. Capture: `ClickClassifier`, `ClickRecorder`, hook in `RedirectController`.
5. **Run the unchanged Phase 1 suite** against the new code before writing any new tests.
6. Read side: `ClickRepository` queries, `LinkStatsService`, `LinkStatsController`.
7. New tests at every level, including migration upgrade/rollback.
8. Upgrade the running Docker stack **in place** (keeping its data), smoke test, re-benchmark, and
   check graceful shutdown under load.

---

## 2. Execution

Notable implementation choices beyond the ADRs:

- **Adaptive batching without a timer.** The writer blocks on `poll`, then `drainTo`s whatever else
  has arrived (up to 500). There is no flush interval to tune. Latency is minimal when idle, and
  batches grow on their own under load (`manyClicksAreBatched`: 1,000 clicks, far fewer than 100
  round trips).
- **`pending` counter rather than queue size.** It counts clicks accepted but not yet committed,
  *including the batch currently being written*. That makes `awaitIdle()` exact, so integration tests
  wait for precisely "everything written" with no sleeps or guesswork. It is also exported as a gauge.
- **No `interrupt()` on stop.** Interrupting a thread inside a JDBC call can break the connection.
  The writer checks `running` every 200 ms instead.
- **Lifecycle phase 0.** In Boot, graceful shutdown runs at `DEFAULT_PHASE - 1024` and the web server
  stops at `DEFAULT_PHASE - 2048`. Lifecycles stop in descending phase order, so phase 0 stops later,
  after the last in-flight redirect has been recorded. The container logs show the order:
  `Graceful shutdown complete` → `Click recorder stopped`.
- **Classification on the request thread.** It is a couple of bounded regex scans (UA capped at 512
  chars). Doing it before the event is queued means the queue holds small records, never raw headers.
- **One window query with `FILTER`.** The breakdowns by agent class and "without referrer" come from a
  single pass (`count(*) FILTER (WHERE …)`), not five queries.
- **Separate controller.** `LinkStatsController` is new; `LinkController` is untouched. Phase 1's
  management API is not modified at all.

### Issues found during execution
- **The existing web-slice test needed a new mock.** `ApiWebTest` failed to start (23 errors) because
  `RedirectController` gained a dependency. This was a test-wiring change, not a behaviour change:
  the other 76 Phase 1 tests passed unchanged on the first run, including all 9 integration tests,
  which ran against the V2 schema. I added one `@MockitoBean` and no assertions changed.
- **A mock-verification pitfall.** The first version of the "HEAD is not counted" test used
  `verifyNoInteractions(clicks)`. It failed, but only when it was the first test in the class. The
  cause was Spring calling lifecycle methods (`start`, `isRunning`) on the mocked `ClickRecorder`
  during context startup. The test now verifies `record(...)` was never called, which is the actual
  claim.
- **BUG-002:** the JDK HTTP client's User-Agent (`Java-http-client/21`) was classified as DESKTOP. Found while writing the integration test, before release. Fixed and
  regression-tested. Details in [BUGS.md](../BUGS.md).

---

## 3. Validation

### 3.1 Strategy
| Level | What it proves | Tooling | New / total |
|---|---|---|---|
| Unit | Classifier rules, recorder guarantees (non-blocking, drop-on-full, failure isolation, flush on stop, kill switch), stats windowing and zero-fill | JUnit, AssertJ, Mockito, Micrometer `SimpleMeterRegistry` | +46 / 113 |
| Web slice | Redirect records GET only; failed redirects not counted; stats JSON shape and errors | `@WebMvcTest` | +6 / 29 |
| Integration | Real redirects → writer → Postgres → stats; 500-way concurrency; privacy; metrics; **V1→V2 upgrade with data; rollback to Phase 1** | Testcontainers PostgreSQL 17, Flyway API | +8 / 17 |
| Smoke | Deployed artifact, including stats | `scripts/smoke-test.sh` | +3 / 18 checks |
| Performance | Redirect latency and throughput before vs after | `scripts/RedirectBench.java` | new |
| Operational | Live in-place upgrade; graceful shutdown under load | Docker Compose | new |

### 3.2 Regression check: Phase 1 suite on Phase 2 code
Run before any new test was written:
```
Tests run: 9,  Failures: 0, Errors: 0   -- LinkApiIntegrationTest   (against the V2 schema)
Tests run: 18, Failures: 0, Errors: 0   -- AliasPolicyTest
Tests run: 21, Failures: 0, Errors: 0   -- LinkServiceTest
Tests run: 3,  Failures: 0, Errors: 0   -- LinkTest
Tests run: 4,  Failures: 0, Errors: 0   -- RandomCodeGeneratorTest
Tests run: 21, Failures: 0, Errors: 0   -- UrlPolicyTest
Tests run: 23, Errors: 23               -- ApiWebTest  (context wiring: missing ClickRecorder bean, see §2)
```

**Final run (Docker Engine 29.9.3, JDK 21.0.12)**
```
./mvnw test
Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0 -- AnalyticsIntegrationTest
Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- LinkApiIntegrationTest
Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- MigrationUpgradeTest
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0 -- ClickClassifierTest
Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0 -- ClickRecorderTest
Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- LinkStatsServiceTest
Tests run: 26, Failures: 0, Errors: 0, Skipped: 0 -- ApiWebTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- LinkStatsWebTest
... Phase 1 unit tests unchanged (67)
Tests run: 159, Failures: 0, Errors: 0, Skipped: 0
```

### 3.3 Redirect performance: before vs after
`java scripts/RedirectBench.java http://localhost:8080 20000 32`: 20,000 redirects at 32 concurrent
connections after warmup, against the Docker Compose stack. The app, PostgreSQL and the load
generator all share one laptop (Windows, Docker Desktop). Three runs each, taken in the same session:

| | Throughput (req/s) | p50 | p95 | p99 |
|---|---|---|---|---|
| **Before** (Phase 1 build) | 8,560 / 8,591 / 8,995 | 1.93 / 2.07 / 1.98 ms | 4.63 / 5.52 / 4.79 ms | 27.0 / 22.7 / 25.4 ms |
| **After** (Phase 2, analytics on) | 7,162 / 8,086 / 7,960 | 1.94 / 3.18 / 2.30 ms | 7.34 / 7.27 / 5.80 ms | 34.8 / 15.0 / 16.3 ms |

**Reading it honestly:**
- Median latency is essentially unchanged and p99 is within run-to-run noise in both directions. The
  request-thread cost is one in-memory offer.
- Throughput at saturation dropped about 10%, and p95 rose by about 1–2 ms (*both overstated: see
  the side-by-side re-measurement below, about 5% and +0.3 ms*). The cause is not the
  redirect code. At ~8,000 redirects/s the writer is also inserting ~8,000 rows/s into **the same
  PostgreSQL on the same laptop**, competing for the same CPU. That is the real cost of storing
  every click. In production it is paid in DB capacity, and the remedies are in ADR-0006 and
  IMP-015/017.
- **No clicks were dropped:** each benchmark link showed exactly 22,000 clicks (20,000 + 2,000
  warmup), and `shortener.clicks{outcome=dropped_*}` stayed at 0.
- These are single-machine numbers. They compare before with after; they are not a capacity
  figure (IMP-009 remains open for a proper load test).

**Re-measured side by side (during Phase 3).** The table above compares runs taken minutes apart,
on different containers. Phase 3 showed that this method can overstate a change's cost several
times over ([scenario 3 §3.5](03-ambiguous.md)). So I re-measured: the Phase 1 and Phase 2 releases,
built from their commits, ran **at the same time against the same database**, in 6 rounds with
rotated run order.

| Build | Throughput, median (range) | p50 | p95 | p99 |
|---|---|---|---|---|
| Phase 1 | 8,913 req/s (8,458 – 9,463) | 2.09 ms | 5.11 ms | 24.1 ms |
| Phase 2 | 8,433 req/s (8,171 – 8,762) | 2.00 ms | 5.39 ms | 30.3 ms |

*Medians over 6 rounds.*

The corrected figures:
- **Throughput:** about **5% lower**, not about 10%.
- **Median latency:** unchanged (2.09 → 2.00 ms, within noise).
- **p95:** up about 0.3 ms, not 1–2 ms.
- **The conclusion stands:** the per-redirect cost is negligible, and the remaining cost is the click
  inserts sharing the database.

### 3.4 Operational validation on the running stack
**In-place upgrade, keeping the existing data.** The Phase 1 stack had been running with 9 links
(2 deleted). Only the app container was rebuilt (`docker compose up -d --build app`); the database
volume was kept.
```
before:  flyway_schema_history = {1 create links};  links = 9
logs:    Current version of schema "public": 1
         Migrating schema "public" to version "2 - create click events"
         Successfully applied 1 migration to schema "public", now at version v2 (execution time 00:00.025s)
after:   flyway_schema_history = {1 create links, 2 create click events};  links = 9
         GET /smoke-1795136808 (created in Phase 1) -> 302 https://example.com/
         readiness 200 about 4 s after the container started
smoke:   18 passed, 0 failed
```
With one instance, the container restart is a few seconds of downtime. Zero-downtime needs two or
more instances (IMP-020); the expand-only schema already allows old and new versions to run side by side.

**Graceful shutdown under load.** I started a 200,000-request benchmark, ran `docker compose stop app`
(SIGTERM) after about 6 s, restarted the app, then compared:
```
bench:   55114 successful redirects (302) before the app went away
logs:    Commencing graceful shutdown. Waiting for active requests to complete
         Graceful shutdown complete
         Click recorder stopped
stats:   totalClicks = 55114
```
Every redirect that succeeded was recorded. Requests after the stop got connection errors, which
is the expected behaviour for a single instance.

### 3.5 Key behaviours and the test that proves each
| Behaviour | Test |
|---|---|
| V1 database with live/expired/deleted links upgrades to V2; data intact | `MigrationUpgradeTest.upgradesAPopulatedV1DatabaseAndStaysRollbackCompatible` |
| Phase 1 build (V1 only) validates and starts against a V2 database | same test, step 3 |
| `record()` never blocks on a stuck database | `ClickRecorderTest.fullBufferDropsClicksInsteadOfBlocking` |
| A failed write drops that batch, is counted, and the writer carries on | `ClickRecorderTest.writeFailureDropsTheBatchAndTheWriterKeepsGoing` |
| Stop flushes everything buffered | `ClickRecorderTest.stopWritesEverythingStillBuffered` |
| Kill switch records nothing | `ClickRecorderTest.disabledRecorderIgnoresClicks` |
| 500 concurrent redirects → exactly 500 clicks | `AnalyticsIntegrationTest.concurrentRedirectsAreAllCounted` |
| Referrer, agent and daily breakdowns are correct; HEAD not counted | `AnalyticsIntegrationTest.redirectsAreCountedAndBrokenDown` |
| Referrer path/query (tokens) never stored | `AnalyticsIntegrationTest.onlyTheReferrerHostIsStored` |
| 404/410 redirects are not clicks; deleted links' stats are 404 | `AnalyticsIntegrationTest.statsFollowTheLinkVisibilityRules`, `ApiWebTest.failedRedirectsAreNotCounted` |
| Window uses UTC days and is zero-filled | `LinkStatsServiceTest.windowCoversTheLastNUtcDaysIncludingToday`, `dailySeriesIsZeroFilledAndOrdered` |
| DB constraints reject bad agent classes and orphan clicks | `MigrationUpgradeTest.v2RejectsInvalidAgentClassesAndUnknownLinks` |
