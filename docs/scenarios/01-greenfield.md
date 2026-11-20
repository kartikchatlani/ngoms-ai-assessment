# Scenario 1: Greenfield: core shortener from an empty repo

- **Created:** 2026-11-18
- **Starting point:** empty repository (README + .gitignore)
- **Goal:** a runnable, tested service that creates short links and redirects them, with the
  production foundations (validation, error contract, health, migrations, containerisation) in place
  so later phases build on solid ground.

---

## 1. Decomposition


### 1.1 Requirements → capabilities
| Requirement (stated or implied) | Capability | Where |
|---|---|---|
| Shorten a URL | Create endpoint, code generation | `LinkController`, `LinkService.create`, `RandomCodeGenerator` |
| Short link must work | Redirect endpoint | `RedirectController`, `LinkService.resolve` |
| Codes must be unique, forever | DB constraint, no reuse after delete | `V1__create_links.sql`, ADR-0003/0004 |
| Users want memorable links | Custom aliases | `AliasPolicy` |
| Campaign links end | Expiry | `Link.statusAt`, `expiresAt` |
| Owners manage links | Get / list / delete | `LinkController` |
| Don't become an attack tool | URL safety rules | `UrlPolicy` |
| Production-grade | Error contract, health, logs, graceful shutdown, migrations, Docker | `ApiExceptionHandler`, Actuator, `RequestIdFilter`, Flyway, compose |

### 1.2 Key risks identified up front (and how each was addressed)
| Risk | Why it matters | Mitigation |
|---|---|---|
| Duplicate codes under concurrency | Two users get the same link, a correctness failure | DB `UNIQUE` + `ON CONFLICT`; concurrency integration test |
| Enumerable codes | Anyone can crawl all links | Random CSPRNG codes, not counters (ADR-0003) |
| Browser-cached redirects | Deletes and expiry silently stop working | 302 + `no-store`, not 301 (ADR-0004) |
| Code reuse after delete | Old printed links hijacked | Soft delete; deleted codes stay reserved |
| `javascript:` / credential URLs | XSS / phishing via our domain | Scheme allowlist, userinfo rejection |
| Self-referencing links | Redirect loops | Reject destinations on our own host |
| Leaky errors | Stack traces/SQL to clients | Catch-all handler, generic 500 |

### 1.3 Explicitly out of scope for Phase 1 (and why)
- **Analytics:** Phase 2. It is a deliberate brownfield change to a running system.
- **Caching (Redis):** premature until there is a measured read-latency need. Postgres with a
  unique-index lookup is sub-millisecond at this scale.
- **Auth / ownership / rate limiting:** these depend on product decisions that are part of the Phase 3
  ambiguous scenario. Recorded as a limitation.
- **Malicious-domain reputation checks:** needs an external feed. Recorded as a limitation.

### 1.4 Work breakdown (order of execution)
1. Stack decision and scaffold (ADR-0001), Maven wrapper, compile check against Boot 4.
2. Schema + migration (ADR-0002), with constraints first so the DB enforces invariants.
3. Domain: `Link`, policies, generator (ADR-0003), service.
4. HTTP layer + error contract (ADR-0005) + redirect semantics (ADR-0004).
5. Cross-cutting: request ids, health groups, graceful shutdown, config validation.
6. Tests at three levels (see §3), Docker, smoke script, docs.

---

## 2. Execution

Notable implementation choices beyond the ADRs:

- **`ON CONFLICT DO NOTHING RETURNING`**: a collision is an *empty result*, not an exception.
  With JPA a unique-violation exception would mark the transaction rollback-only and force awkward
  retry plumbing. Here the retry loop is a plain `for` loop (`LinkService.create`).
- **Injected `Clock`**: all "now" logic (expiry, created_at) uses one `Clock` bean, so tests are
  deterministic (`Clock.fixed`) with no sleeps.
- **Validation in layers**: Bean Validation rejects shape errors (missing `url`, oversized fields)
  before any business code runs. Domain policies apply the rules. DB `CHECK` constraints are the last
  line of defence if a future code path skips the service.
- **Route pattern = alias pattern**: `/{code:[A-Za-z0-9_-]{3,32}}` mirrors `AliasPolicy.CODE_REGEX`
  and the DB `CHECK`, so junk paths (`/favicon.ico`, `/wp-login.php`) are rejected with no DB query.
- **Readiness vs liveness**: readiness includes the DB (no DB, no traffic). Liveness does not
  (restarting cannot fix the DB, and restart storms slow recovery).

### Issues found during execution
- **BUG-001:** far-future `expiresAt` caused a 500 instead of a 400. Found in code review,
  fixed, and covered by regression tests. Details in [BUGS.md](../BUGS.md).
- **Environment:** the build machine had only Java 8 and no Docker. A portable JDK 21 was used to
  build and test. Integration tests could not run locally (see §3.3).

---

## 3. Validation

### 3.1 Strategy
| Level | What it proves | Tooling | Count |
|---|---|---|---|
| Unit | Business rules in isolation: generator, URL/alias policy, expiry boundaries, retry and pagination logic | JUnit 5, AssertJ, Mockito | 67 |
| Web slice | HTTP contract: status codes, headers, JSON shape, Problem Details for every error branch, request ids | `@WebMvcTest` + MockMvc | 23 |
| Integration | Real SQL, Flyway migration, constraints, concurrency, full lifecycle over real HTTP | `@SpringBootTest` + Testcontainers PostgreSQL 17 | 9 |
| Smoke | The deployed artifact works end to end | `scripts/smoke-test.sh` (curl) | 15 checks |

### 3.2 Evidence

**Final run, with Docker (Docker Engine 29.9.3, JDK 21.0.12)**
```
./mvnw test
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in com.shortener.LinkApiIntegrationTest
[INFO] Tests run: 99, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

docker compose up --build   +   bash ./scripts/smoke-test.sh
Result: 15 passed, 0 failed
```

**Initial run, no Docker available**
```
./mvnw clean package
[INFO] Tests run: 23  -- in com.shortener.api.ApiWebTest
[INFO] Tests run: 18  -- in com.shortener.link.AliasPolicyTest
[INFO] Tests run: 21  -- in com.shortener.link.LinkServiceTest
[INFO] Tests run: 3   -- in com.shortener.link.LinkTest
[INFO] Tests run: 4   -- in com.shortener.link.RandomCodeGeneratorTest
[INFO] Tests run: 21  -- in com.shortener.link.UrlPolicyTest
[WARNING] Tests run: 9, Skipped: 9 -- in com.shortener.LinkApiIntegrationTest   (no Docker)
[INFO] Tests run: 99, Failures: 0, Errors: 0, Skipped: 9
[INFO] Building jar: target/url-shortener-0.1.0-SNAPSHOT.jar
[INFO] BUILD SUCCESS
```
90 of 90 executed tests pass. The 9 integration tests compile but were skipped (see §3.3).

### 3.3 Validation gap: closed
On the initial run the integration tests and the Docker smoke test could not run: the development
machine had no Docker. They auto-skip without Docker (`@Testcontainers(disabledWithoutDocker = true)`)
instead of failing, so the build stayed green. That made the gap easy to overlook, which is why it
was recorded here.
After Docker Desktop and JDK 21 were installed, all 9 integration tests and all 15 smoke
checks passed with no code changes (§3.2). Both runs are part of the single 0.1.0 release in
the changelog. This confirmed the SQL, migration, constraints and
concurrency behaviour against real PostgreSQL 17.

### 3.4 Key behaviours and the test that proves each
| Behaviour | Test |
|---|---|
| Exactly one winner when 16 requests race for one alias | `LinkApiIntegrationTest.concurrentRequestsForSameAliasProduceExactlyOneWinner` |
| Deleted codes can't be re-registered | `LinkApiIntegrationTest.deletedAliasCannotBeReclaimed` |
| Collision → retry → success; 5 collisions → 503 | `LinkServiceTest.retriesOnCollision...`, `givesUpAfterMaxAttempts` |
| Expiry boundary is inclusive | `LinkTest.expiryIsInclusiveAtTheBoundary` |
| Unsafe URLs rejected (12 cases) | `UrlPolicyTest.rejectsUnsafeOrMalformed` |
| Generator is uniform over all 62 symbols | `RandomCodeGeneratorTest.usesTheWholeAlphabetRoughlyUniformly` |
| DB down → 503 + Retry-After, no internals leaked | `ApiWebTest.databaseDownIs503WithRetryAfter` |
| Pagination visits every link exactly once | `LinkApiIntegrationTest.paginationWalksAllLinksWithoutDuplicates` |
| Non-ASCII destinations are percent-encoded in `Location` | `ApiWebTest.redirectPercentEncodesNonAsciiDestination` |
