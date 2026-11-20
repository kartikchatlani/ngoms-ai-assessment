# Testing approach

> Current as of **Phase 3**.

## Philosophy
- **Test behaviour, not implementation.** Tests assert outcomes (status codes, stored state, error
  codes) so that refactoring does not break them.
- **Each level answers a different question.** We don't re-test the same thing three times.
- **Real dependencies where they matter.** SQL, constraints and concurrency are tested against real
  PostgreSQL (Testcontainers), not H2. H2 has no compatible `ON CONFLICT`, so it would test
  something other than production.
- **Deterministic.** Time comes from an injected `Clock`, there are no `sleep`s, and statistical tests use
  bounds about 10 standard deviations wide. Asynchronous click writes are awaited exactly with
  `ClickRecorder.awaitIdle()` (it tracks in-flight batches too), not with fixed delays.
- **Every bug gets a regression test**, named `..._regressionBugNNN` and cross-referenced in
  [BUGS.md](BUGS.md).

## The pyramid

| Level | Question answered | Speed | Location |
|---|---|---|---|
| **Unit** (166) | Are the business rules right? | ms | `src/test/java/com/shortener/{link,analytics,abuse,auth,web}/*Test.java` |
| **Web slice** (45) | Is the HTTP contract right (status, headers, JSON, error shape), including 401/403/429 and the access rules? | ~3s total | `src/test/java/com/shortener/api/*WebTest.java` |
| **Integration** (30) | Does it really work with Postgres, Flyway, Spring Security and HTTP together, including under concurrency? Do migrations upgrade a populated DB and stay rollback-safe? | ~20s + container start | `LinkApiIntegrationTest`, `AnalyticsIntegrationTest`, `AbuseIntegrationTest`, `MigrationUpgradeTest` |
| **Smoke** (31 checks) | Does the deployed artifact work, including every abuse control? | seconds | `scripts/smoke-test.sh` |
| **Benchmark** | Did a change slow the redirect path? (before/after on one machine) | ~1 min | `scripts/RedirectBench.java` |

## How to run

```bash
./mvnw test                      # unit + web + integration (integration auto-skips without Docker)
./mvnw test -Dtest=LinkServiceTest   # a single class
docker compose up --build -d && ./scripts/smoke-test.sh   # end-to-end against the container
java scripts/RedirectBench.java http://localhost:8080 20000 32   # redirect latency (JDK 21, no build needed)
```
**Windows:** use `mvnw.cmd` in PowerShell, and run the smoke script with `bash ./scripts/smoke-test.sh`.
PowerShell can't execute `.sh` files directly; it opens them in the associated editor instead.

## Coverage of risk (not just lines)
The risk registers in [scenario 1 §1.2](scenarios/01-greenfield.md) and
[scenario 2 §1.3](scenarios/02-brownfield.md) map each identified risk to the test that guards it.
Key behaviours are summarised in §3.4 and §3.5 of those documents.

## Security testing (Phase 3)
- **Every symptom in the request has an end-to-end test** that reproduces it and shows it fixed
  (`AbuseIntegrationTest`, mapped in [scenario 3 §3.3](scenarios/03-ambiguous.md)).
- **Negative paths are tested for exact responses, not just status codes.** "Someone else's link"
  and "no such link" are compared body-for-body and header-for-header, because a small difference
  would leak ownership.
- **Web-slice tests run the real `SecurityConfig`**, not a disabled or mocked one, so the URL rules
  are under test. Services are mocked; keys are resolved through a mocked `ApiKeyService`.
- **Time-based logic uses a fake clock** (`CreateRateLimiter` takes a nanosecond supplier), so
  rate-limit refill is tested without sleeping.

## Brownfield testing practice (Phases 2 and 3)
- **Run the old suite before writing new tests.** Any failure at that point is a regression or a
  wiring change, and it is recorded as such ([scenario 2 §3.2](scenarios/02-brownfield.md)).
- **Measure before changing.** The redirect benchmark ran on the unchanged build first, so the
  "after" numbers have something to be compared with.
- **Test migrations against data, in both directions.** `MigrationUpgradeTest` upgrades populated
  V1→V2 and V2→V4 databases, then validates each with the previous release's migrations only,
  which is what a rollback does. Phase 3 also ran the real Phase 2 image against the V4 database.
- **Run the previous release's smoke test against the new release.** Its failures are the contract
  diff: in Phase 3, exactly the intended 401s ([scenario 3 §3.2](scenarios/03-ambiguous.md)).
- **Compare performance side by side, not minutes apart.** Phase 3's first before/after number
  overstated the cost about four times over. The reliable method: run every build at once against
  one database, warm each up, then do several rounds with the run order rotated, and report medians
  and ranges ([scenario 3 §3.5](scenarios/03-ambiguous.md)).

## Known gaps
- Integration tests **auto-skip when Docker isn't running**, so a green build without Docker is
  not a full pass. Check that the four integration classes report `Skipped: 0` (last full run:
  241/241 passed). CI (IMP-004) would make this impossible to miss.
- The benchmark runs the app, the database and the load generator on one machine. It is good for
  before/after comparisons, not for capacity figures (IMP-009).
- Graceful shutdown under load and the in-place upgrade were verified manually against Docker
  Compose ([scenario 2 §3.4](scenarios/02-brownfield.md)), not by an automated test.
- No CI pipeline yet (IMPROVEMENTS.md). Tests run locally.
- No mutation testing or coverage gate.
