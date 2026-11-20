# Scenario 3: Ambiguous: "lock it down"

- **Created:** 2026-11-20
- **Starting point:** the Phase 2 service ([scenario 2](02-brownfield.md)): links, redirects and
  click analytics, with no authentication, no rate limiting and no URL screening.
- **Provenance:** the assessment did not supply an ambiguous requirement, so I had Claude write one as a PO: it reports symptoms, not specifications, and has
  constraints that pull against each other. Every symptom is plausible given the system's actual
  gaps (see [limitations](../limitations-tradeoffs.md)).

---

## The request

> **From:** Head of Product
> **Subject:** Shortener abuse
>
> Hey, a few things have come up since we launched the shortener, and I think we need to lock it
> down before the marketing push next month.
>
> - Chrome showed a "deceptive site ahead" warning on one of our short links, and a couple of
>   users have reported links that went to fake login pages.
> - The links table grew much faster last week than our traffic would explain. It looks like
>   someone is scripting it.
> - One of the marketing team's campaign links disappeared, and nobody on the team deleted it.
>
> I don't want to make it painful for normal people to use. The whole point is that it's quick.
> Can you put something in place? It doesn't have to be perfect, it just needs to be much harder to abuse.

### Why this is ambiguous
- **Three symptoms with three different fixes.** Phishing points to URL screening, the scripted
  growth to rate limiting, and the vanished link to authentication and ownership. "Lock it down"
  doesn't say which matters most, or whether all three are in scope.
- **Constraints that pull against each other.** "Much harder to abuse" versus "not painful for
  normal people" and "quick". Every protection adds some friction; the request doesn't say how much
  is acceptable, or for whom.
- **Missing information.** Who "normal people" are (the anonymous public, the marketing team, or both),
  how strict "much harder" is, and what "doesn't have to be perfect" allows. The marketing push
  next month adds a time limit, which forces a choice of scope.

---

## 1. Decomposition

### 1.1 Diagnosis: can the current system explain each symptom?
Before choosing fixes, I checked that each symptom actually follows from the code. Fixing a
problem the system doesn't have would be wasted effort.

| Symptom | Explained by | Evidence in the code | What we can't tell today |
|---|---|---|---|
| Phishing destinations, Chrome warning | `UrlPolicy` checks *shape* only (scheme, host, no credentials, not our own host), not *reputation* | [UrlPolicy.java](../../src/main/java/com/shortener/link/UrlPolicy.java) | Which links are malicious: nothing records reports or checks |
| Table growing faster than traffic | No limit of any kind on `POST /api/v1/links` | [LinkController.java](../../src/main/java/com/shortener/api/LinkController.java) | Who created them: no creator is recorded, so we can't find or clean up the batch |
| Campaign link disappeared | `DELETE /api/v1/links/{code}` needs no credentials. Anyone who knows (or lists) a code can delete it, and `GET /api/v1/links` lists **every** live link | `LinkController.delete`, `LinkController.list` | Who deleted it: `deleted_at` is set, but no actor is recorded |

All three are real gaps, and all three were already recorded as limitations (IMP-001, IMP-002,
IMP-003). The diagnosis also finds a fourth gap the request doesn't mention: **there is no audit
trail**, so none of these incidents can be investigated after the fact.

### 1.2 Questions I would ask, and the assumptions I'm making instead
There is no stakeholder to ask, so each question gets an explicit assumed answer and a reason. The
"If wrong" column is where the design hedges: the riskiest assumptions are kept configurable or
behind an interface.

| # | Question | Why it changes the design | Assumption | Reason | If wrong |
|---|---|---|---|---|---|
| Q1 | Who creates links: the anonymous public, known teams, or both? | Decides whether we can require sign-in to create | **Both.** Anonymous creation stays; known teams (marketing) get API keys | "Not painful for normal people" and "quick" rule out forcing everyone to register. Marketing needs links nobody else can delete | If public: drop anonymous creation (one config flag). If internal only: require keys everywhere |
| Q2 | Does anyone need to delete or see links they didn't create? | Decides the permission model | **No**, except an admin | That is exactly the incident in symptom 3 | Add shared team ownership (keys belong to a team, not a person) |
| Q3 | How strict is "much harder"? | Sets rate-limit numbers | Anonymous: **10 creates per hour per IP**. API key: **1,000 per hour**. Redirects: not limited | A person creates a handful of links per session; a script creates thousands. Limiting redirects would hurt the "normal people" clicking campaign links | Limits are configuration, not code |
| Q4 | Can we use a paid or external screening service (e.g. Google Safe Browsing)? | External dependency, API key, cost, and sending every submitted URL to a third party | **Not in this phase.** Local domain blocklist and heuristics, behind a `UrlScreener` interface | No approved vendor or key; sharing user URLs with Google is a privacy decision for someone else to make; next month's deadline | Add a Safe Browsing implementation of the same interface |
| Q5 | What happens to phishing links that already exist? | Screening at create time does nothing for them | **An admin can take a link down**, and the blocklist is also checked **at redirect time** | The Chrome warning is about links that exist now. Checking on redirect means blocking a domain disables every existing link to it at once | None: takedown is needed under any reading |
| Q6 | Is there an identity provider (SSO, OAuth) to integrate with? | Decides between API keys and a login flow | **No.** Admin-issued API keys | Nothing exists today; an OAuth integration would not fit the deadline | Keys map cleanly onto an identity provider later: the key becomes a token |
| Q7 | Who owns the links created before this change? | Existing rows have no owner | **The admin.** Legacy links keep working; only the admin can delete them, and can assign them to a key | We can't know who created them, and they must not become deletable by anyone | Bulk-assign by URL pattern, e.g. all `example.com/campaign/*` to marketing |
| Q8 | How many app instances, and is there a proxy in front? | In-memory vs shared rate-limit state; which IP to trust | **One instance; requests arrive directly.** In-memory limiter behind an interface; client IP from the socket, with `X-Forwarded-For` trusted only when configured | Matches the current deployment. Trusting `X-Forwarded-For` by default would let anyone bypass the limit by setting the header | Redis-backed limiter (IMP-002); set the trusted-proxy option |

### 1.3 Options for each problem
| Problem | Options | Chosen | Why |
|---|---|---|---|
| Unauthorised deletes | (a) API keys + ownership; (b) a per-link "manage token" returned on create, no accounts; (c) OAuth/SSO | **(a)** | (b) is friendlier for anonymous users, but marketing also needs to *list* their own links, and today's list endpoint showing everyone's links is a leak in itself. (c) doesn't fit the deadline (Q6) |
| Scripted creation | (a) per-IP / per-key token bucket on create; (b) CAPTCHA; (c) proof-of-work | **(a)** | (b) and (c) add friction for every normal user and don't help API clients |
| Phishing links | (a) domain blocklist checked on create **and** redirect; (b) heuristics: reject destinations on other URL shorteners (chained redirects hide the real target, a common phishing trick); (c) admin takedown; (d) Safe Browsing API; (e) interstitial warning page | **(a) + (b) + (c)** | Together they cover new links, existing links and known-bad domains with no external dependency. (d) is deferred (Q4). (e) adds friction to every click |
| No audit trail | (a) record `created_by` and `deleted_by` on links; (b) a separate audit log table | **(a)** | Answers "who did this?" for the incidents in the request; a full audit log is more than the request needs |

### 1.4 Chosen scope
**In scope:**
1. **API keys and ownership.** Keys are issued by an admin and sent as `Authorization: Bearer <key>`.
   Only a hash of each key is stored. Links created with a key belong to that key. Delete, stats
   and list need a key and only cover the caller's own links. An admin key can manage all links.
   **A link you don't own returns `404`, not `403`.** A `403` would confirm that the code is a
   managed link owned by someone else, and so worth targeting. With `404`, links you don't own look
   exactly like links that don't exist. This is not about hiding existence: the redirect
   (`302`/`410` vs `404`) and `409 ALIAS_TAKEN` already reveal that, unavoidably, because short
   links are public. It is about not confirming ownership, and not revealing a link's destination,
   expiry or state to anyone except its owner.
2. **Anonymous creation stays, but rate-limited.** No key: 10 creates per hour per IP. Anonymous
   links can't be deleted by anyone except the admin.
3. **Rate limiting** on create, per key or per IP, with a token bucket. `429 Too Many Requests` with
   `Retry-After`. Redirects are not limited.
4. **URL screening:** a configurable domain blocklist (including subdomains) and a list of known
   shortener domains, checked on create. The blocklist is also checked on redirect.
5. **Admin takedown:** `POST /api/v1/admin/links/{code}/block`. A blocked link returns
   `410 LINK_BLOCKED` and cannot be unblocked by its owner.
6. **Audit fields:** `created_by` and `deleted_by` (key id, or null for anonymous).

**This is a deliberate breaking change.** `DELETE`, `GET /api/v1/links` and `/stats` now return
`401` without a key. The open contract *is* the vulnerability in symptom 3, so keeping it
compatible would mean not fixing it. Creating links and redirects behave as before for anonymous
callers, apart from the rate limit and screening.

**Out of scope, and why:**
- **User accounts, sign-up and a UI:** keys are enough for a known marketing team, and accounts are a product decision.
- **CAPTCHA, interstitial warnings:** friction for normal users (the request's main constraint).
- **Safe Browsing / external reputation feeds:** needs a vendor decision and a privacy decision (Q4).
- **Distributed rate limiting:** one instance today (Q8). In-memory, behind an interface.
- **Rate-limiting redirects** and DDoS protection: belongs at the CDN or load balancer, not the app.
- **A public "report this link" endpoint:** useful, but it would need its own abuse protection; recorded as an improvement.

### 1.5 Risk register
| Risk | Why it matters | Mitigation |
|---|---|---|
| Locking out the marketing team at deploy time | The push is next month, so their links must stay manageable | Admin key exists at first startup; legacy links can be assigned to a key (Q7) |
| Rate limit bypass with a spoofed `X-Forwarded-For` | Makes the limit useless | Header ignored unless a trusted proxy is configured (Q8) |
| Leaked API keys | A key can delete all its owner's links | Only hashes stored; keys can be revoked; admin key from env, never logged |
| Blocklist check slows redirects | Redirects are the hot path | In-memory set lookup on the host, no I/O; re-run the Phase 2 benchmark |
| Legit users behind one IP (office, mobile carrier NAT) share a limit | "Not painful for normal people" | Limits are config; teams that create a lot get keys with their own limits |
| Breaking clients that call `DELETE`/list without auth | Existing integrations stop working | Documented as intentional; clear `401` Problem Details with `errorCode` |
| Error codes reveal who owns what | Attackers find managed links worth targeting | Non-owned links return `404` exactly like unknown ones; a test asserts the two responses are identical |
| Attackers learn which domains are blocked | They switch domains | Same generic error for all screening rejections; the specific rule is logged, not returned |

### 1.6 Work breakdown (order of execution)
1. ADRs: authentication and ownership (ADR-0008), rate limiting (ADR-0009), URL screening and
   takedown (ADR-0010).
2. Migration V3: `api_keys` table; `owner_key_id`, `created_by`, `deleted_by`, `blocked_at` on
   `links`. Expand-only, as in Phase 2.
3. Authentication filter and ownership checks: `401` with no or invalid key, `404` for links the
   key doesn't own, `403` only for non-admin keys calling admin endpoints.
4. Rate limiter (token bucket, per key or IP) on create.
5. `UrlScreener`: blocklist and shortener list on create; blocklist on redirect; admin takedown.
6. Tests at every level, including the assumption-driven cases (spoofed headers, NAT-shared IP,
   legacy ownerless links).
7. Re-run the Phase 2 suite and the redirect benchmark, then upgrade the running stack in place.

---

## 2. Execution

Decisions are recorded in [ADR-0008](../adr/0008-api-keys-and-ownership.md) (keys and ownership),
[ADR-0009](../adr/0009-rate-limiting.md) (rate limiting) and
[ADR-0010](../adr/0010-url-screening-and-takedown.md) (screening and takedown).

### 2.1 What was built
| Symptom | What now happens | Where |
|---|---|---|
| 3: link deleted by someone else | Management needs `Authorization: Bearer <key>`. Keys own the links they create; others get 404. `deleted_by` records who deleted it | `SecurityConfig`, `ApiKeyAuthenticationFilter`, `Caller`, `LinkService` |
| 2: scripted mass creation | Token bucket on create: 10/hour per IP anonymously, 1,000/hour per key, admins unlimited. `429` + `Retry-After` | `CreateRateLimiter`, `ClientIpResolver` |
| 1: phishing links, Chrome warning | Admin domain blocklist, checked on create **and every redirect**; links to other shorteners refused; single-link takedown | `UrlScreener`, `AdminController` |
| (found in diagnosis) no audit trail | `created_by`, `owner_key_id`, `deleted_by`; key prefix in every log line of the request | `V3`, MDC `apiKey` |

### 2.2 Changes from the plan in §1
- **The blocklist moved from configuration to the database.** §1.4 said "configurable domain
  blocklist". While building the takedown path it became clear that blocking a domain must work
  *during an incident* without a redeploy, so it's an admin API over a `blocked_domains` table, held
  in memory and refreshed every 30 s. The list of other shorteners stayed in configuration, since it
  rarely changes.
- **Takedown is `PUT`, not `POST`** (`/api/v1/admin/links/{code}/block`): blocking twice has the same
  effect as blocking once, and `DELETE` on the same path unblocks.
- **Two migrations instead of one.** V3 adds the foreign keys `NOT VALID`; V4 validates them. With a
  single `ADD CONSTRAINT`, PostgreSQL would scan the whole `links` table while holding the lock taken
  by `ADD COLUMN`, which blocks reads, and therefore redirects. As two migrations they run in separate
  transactions, and `VALIDATE CONSTRAINT` blocks neither reads nor writes.
- **A rotated `APP_ADMIN_KEY` revokes the old one.** Without that, changing the variable would leave
  two working admin keys. Not in the plan; found while writing the bootstrap code.
- **Metrics became admin-only.** Not in the plan either: once there was an authorization layer,
  `/actuator/metrics` stood out as public data about traffic and abuse. Health stays public.

### 2.3 Notable implementation choices
- **Spring Security, with a small custom filter.** Default-deny URL rules in one place
  (`SecurityConfig`), standard security headers, and nothing hand-rolled for the parts that are easy
  to get wrong. Its default `Cache-Control` header is turned off, because it would have overridden
  the redirect's own `no-store` decision (ADR-0004).
- **A bad key is a 401, never a quiet fallback to anonymous.** That surfaces broken client
  configuration immediately instead of degrading it to the anonymous rate limit.
- **`Caller` is a domain type.** `LinkService` never sees Spring Security, and every management
  method takes a `Caller`. The Phase 2 unit tests stopped compiling as a result, which is the point:
  a call site that forgets the ownership check can't compile.
- **Rate limiting before the business rules.** `check()` runs before URL validation and screening,
  so a refused URL costs the client a token too; otherwise a script could probe the screening rules
  for free. (Malformed JSON and a missing `url` are rejected even earlier, by Bean Validation, at
  no cost to the client. Those requests do no work worth protecting.)
- **Status is computed once.** `LinkService.statusOf` combines stored state with the blocklist, and
  both the redirect and the management API use it, so they can't disagree.

### 2.4 Issues found during execution
- **BUG-003:** creating an API key without the optional `"admin"` field returned 400. Found by
  `SecurityWebTest` before release. Details in [BUGS.md](../BUGS.md).
- **A dependency cycle, caught before it compiled.** The rate limiter first took a `link.Caller`,
  while `link.LinkService` uses `abuse.UrlScreener`: `abuse` and `link` would have depended on each
  other. The limiter now takes plain values (`keyId`, `admin`, `ip`), so `abuse` doesn't depend on `link`.
- **A test made too permissive by a bulk edit.** The script that added keys to the web tests also
  added one to the new "stats without a key gives 401" test, which would then have tested nothing.
  Caught on review and fixed before running.
- **The live upgrade happened earlier than planned.** Between the baseline benchmark and my upgrade
  step, the Docker stack was rebuilt from the working tree (`docker compose up --build`), which
  applied V3 and V4 to the real Phase 2 data. Rather than redo it, §3.4 verifies what happened from
  the Flyway logs and the data.

---

## 3. Validation

### 3.1 Strategy
| Level | What it proves | New / total |
|---|---|---|
| Unit | Ownership rules, "not yours = not found", screening (subdomains, lookalikes, fail-safe refresh), token bucket with a fake clock, `X-Forwarded-For` rules, key hashing and bootstrap rotation | +53 / 166 |
| Web slice | 401 / 403 / 429 contract, bad key never falls back to anonymous, identical response for every screening rule, admin API, default-deny | +16 / 45 |
| Integration | Each symptom reproduced and shown fixed against real PostgreSQL and the real security chain; V2→V4 upgrade with data; rollback to Phase 2 | +13 / 30 |
| Smoke | The deployed artifact, including every abuse control | +13 / 31 checks |
| Contract diff | The **Phase 2** smoke test, unchanged, against Phase 3 | 18 checks |
| Performance | Redirect cost of the security chain and blocklist check, Phase 2 and Phase 3 side by side | 4 rounds each |

### 3.2 Regression check: the old suite against the new code
**Compile time.** The Phase 1 and 2 unit and web tests no longer compiled, because every
management method now requires a `Caller`. I updated them mechanically: old calls became admin
calls (no restrictions, as in Phase 1) and creates stayed anonymous, so they still test the link
rules they were written for. Ownership got its own tests.

**Integration tests, unchanged, against Phase 3.** These only talk HTTP, so they compiled as they
were. 13 of 17 failed, every one for an intended reason:

| Cause | Tests | Intended by |
|---|---|---|
| `401` on get/list/delete/stats/metrics without a key | 8 | ADR-0008 |
| `429` from the 10-per-hour anonymous limit (tests create 7 to 200 links) | 4 | ADR-0009 |
| Expected exactly 1 pending migration, found 3 (V2, V3, V4) | 1 | V3/V4 exist |

No 500s, no broken redirects. The tests for redirects, referrer privacy, health, validation and the
V2 constraints passed unchanged. I then gave the tests their own client keys and raised their rate
limits (abuse controls are tested separately), and pinned the Phase 2 migration test to `target("2")`.

**Phase 2 smoke test, unchanged, against the Phase 3 deployment: 11 passed, 7 failed.**
```
  FAIL  get link returns 200 (expected 200, got 401)
  FAIL  stats count GET redirects only (expected 2, got )
  FAIL  stats with bad days returns 400 (expected 400, got 401)
  FAIL  list returns 200 (expected 200, got 401)
  FAIL  delete returns 204 (expected 204, got 401)
  FAIL  deleted link redirect is 410 (expected 410, got 302)
  FAIL  delete again is still 204 (expected 204, got 401)
```
This is the breaking change from §1.4, exactly and only. "Deleted link redirect is 410" fails
because the anonymous delete was refused, so the link still exists: symptom 3's fix, seen from the
attacker's side. Everything an anonymous visitor does passed: create, redirect, HEAD, validation,
alias conflicts, 404.

**Final run (Docker Engine 29.9.3, JDK 21.0.12)**
```
./mvnw test
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- AbuseIntegrationTest
Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0 -- AnalyticsIntegrationTest
Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- LinkApiIntegrationTest
Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- MigrationUpgradeTest
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- SecurityWebTest
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- LinkOwnershipTest
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0 -- UrlScreenerTest
Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- ApiKeyServiceTest
Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0 -- CreateRateLimiterTest
Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- ClientIpResolverTest
... Phase 1-2 classes (143)
Tests run: 241, Failures: 0, Errors: 0, Skipped: 0
```

### 3.3 Each symptom, reproduced and fixed
From `AbuseIntegrationTest` (real HTTP, real security chain, real PostgreSQL) and the live stack:

| Symptom | Before (Phase 2) | After (Phase 3) | Proof |
|---|---|---|---|
| 3: someone deleted the campaign link | `DELETE` without credentials → `204` | No key → `401`; another key → `404`; owner → `204` with `deleted_by` recorded | `anotherKeyCannotSeeOrDeleteTheCampaignLink` |
| 3: anyone could list every link | `GET /api/v1/links` → everyone's links | Only your own; admin sees all | `listingShowsOnlyYourOwnLinksAndAdminSeesAll` |
| 2: scripted creation | Unlimited | 11th anonymous create in the hour → `429`, `Retry-After: 337`; spoofed `X-Forwarded-For` → still `429`; a key → unaffected | `anonymousScriptsAreRateLimitedAndCantDodgeIt...`; live stack |
| 1: links to phishing pages | Redirect to any `http(s)` URL | Block the domain → existing links `410 LINK_BLOCKED` at once, new ones `400`; owner sees `BLOCKED` | `blockingADomainDisablesExistingLinksImmediately...`; smoke |
| 1: one reported link | No way to remove it | Admin takedown → `410`; the owner can't undo it or see the reason | `adminTakedownOfASingleLinkCantBeUndoneByItsOwner` |
| 1: chained shorteners hide the target | `https://bit.ly/...` accepted | `400 URL_NOT_ALLOWED` | `linksToOtherShortenersAreRefused` |

"Not yours" is indistinguishable from "doesn't exist": for both the link and its stats, the test
compares status, body and header names of the two 404s
(`someoneElsesLinkLooksExactlyLikeAMissingOne`).

### 3.4 Live upgrade, rollback and smoke test
**Upgrade on real data.** The running stack held 22 links and 209,152 click events from Phases 1 and 2.
```
logs:   Current version of schema "public": 2
        Migrating schema "public" to version "3 - api keys ownership blocking"
        Migrating schema "public" to version "4 - validate link key constraints"
        Successfully applied 2 migrations to schema "public", now at version v4 (execution time 00:00.037s)
after:  links with no owner (pre-Phase 3): 22 of 22   (admin-only until assigned, Q7)
        foreign keys validated: true
        api_keys: bootstrap-admin (admin)
        GET /smoke-1795136808 (created in Phase 1) -> 302
```

**Rollback compatibility, verified with the released Phase 2 build.** `MigrationUpgradeTest` shows
that Flyway, given only the V1 and V2 migrations, accepts a V4 database. That covers schema
validation but not the application code. To cover the code as well, I built the committed Phase 2
release (`git archive d0d80e7`) into an image and ran it on port 8081 against the **same upgraded V4
database** the Phase 3 app was using:
```
Successfully validated 4 migrations
Current version of schema "public": 4
Started UrlShortenerApplication in 2.743 seconds
GET /smoke-1795136808 -> 302
```
It then served the side-by-side benchmark in §3.5 (over 100,000 redirects, plus one link created per
run) without errors. A Phase 2 build therefore runs against a database two migrations ahead of it,
so reverting the deployment doesn't require any database changes.

*Scope:* the full Phase 2 test suite was not run against this container, and it ran alongside
Phase 3 rather than replacing it. This is strong evidence of rollback safety, not an exhaustive
proof.

**Smoke test (Phase 3 version): 31 passed, 0 failed**, including anonymous create, 401/403/404 for
each kind of unauthorised call, the shortener rule, and blocking then unblocking a domain while one
of its links is live.

### 3.5 Redirect performance
**How it was measured.** The first post-upgrade benchmark suggested a 15% throughput drop, but it
was taken minutes after a restart and compared with a baseline from a different container. The
final measurement removes both problems. All three releases ran **at the same time, against the same
PostgreSQL database** (already at V4):

- **Phase 1** was built from commit `90df5f3`, **Phase 2** from `d0d80e7`, and **Phase 3** from the
  working tree.
- Each build was warmed up with two runs first.
- Then came 6 rounds of 20,000 redirects at 32 concurrent connections
  (`scripts/RedirectBench.java`). The **run order rotated every round** (all six orderings), so no
  build always ran first or last.
- All 18 runs completed with 0 failed redirects. While idle, each app used under 0.5% CPU, so the
  builds that weren't being benchmarked didn't compete for resources.

| Build | Throughput, median (range) | p50 | p95 | p99 |
|---|---|---|---|---|
| Phase 1 (core only) | **8,913 req/s** (8,458 – 9,463) | 2.09 ms | 5.11 ms | 24.1 ms |
| Phase 2 (+ click analytics) | **8,433 req/s** (8,171 – 8,762) | 2.00 ms | 5.39 ms | 30.3 ms |
| Phase 3 (+ security, blocklist) | **8,142 req/s** (7,653 – 8,303) | 2.58 ms | 6.53 ms | 21.1 ms |

*Medians over 6 rounds.*

**What Phase 3 costs, compared with Phase 2:**
- **About 3.5% throughput** (8,433 → 8,142 req/s).
- **About +0.6 ms at the median** (2.00 → 2.58 ms) and **about +1.1 ms at p95**.
- **p99 shows no ordering between versions.** Phase 3 has the lowest median, and single runs range
  from 14 ms to 64 ms, so p99 is noise at this sample size.
- **Where the cost comes from:** the Spring Security filter chain and the blocklist lookup, which
  both run on every redirect. This benchmark can't separate the two. Exempting the redirect path
  from the chain is the remedy if it ever matters (limitations-tradeoffs.md).
- **Why it's acceptable:** it closes three abuse paths. It's also an upper bound, because the load
  generator, four JVMs and PostgreSQL all share one laptop.

**Lesson recorded:** a before/after measurement taken minutes apart, on different containers,
attributed about four times the real cost to the change. The only reliable comparison was to run
the builds concurrently against the same database, rotating the order.

### 3.6 Revisiting the assumptions
| # | Assumption | Held up? | If it's wrong |
|---|---|---|---|
| Q1 | Anonymous creation stays; teams get keys | Built as assumed | Require a key for create: one rule in `SecurityConfig` (`POST /api/v1/links` → `hasRole("CLIENT")`) |
| Q2 | Nobody needs others' links except admins | Built as assumed | Team-owned keys: `owner_key_id` → `owner_team_id`; the `Caller.canManage` check is the single place to change |
| Q3 | 10/h anonymous, 1,000/h per key, redirects unlimited | Defaults; one office NAT could exhaust 10/h | Change `APP_ANONYMOUS_CREATES_PER_HOUR`; no code |
| Q4 | No external reputation service yet | Blocklist is reactive (ADR-0010) | Add a Safe Browsing `UrlScreener` check next to the blocklist (IMP-003) |
| Q5 | Takedown + redirect-time blocklist handle existing links | Verified live and in tests | — |
| Q6 | No identity provider | Keys stand in for it | Validate OIDC tokens in the same filter; `Caller` stays the same |
| Q7 | Pre-Phase 3 links are admin-only until assigned | All 22 live links became admin-only on upgrade; assignment tested | Bulk assignment endpoint (IMP-025) |
| Q8 | One instance, no proxy | Rate limits and blocklist are per instance | Redis limiter (IMP-002); set `APP_TRUSTED_PROXIES` behind a load balancer |

**Operational follow-up, not code:** after taking down the reported links, request a review in
Google Search Console so Chrome's warning on our domain is lifted. The fix stops new damage;
it doesn't tell Google.
