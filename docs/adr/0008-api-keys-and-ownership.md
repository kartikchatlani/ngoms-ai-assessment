# ADR-0008: API keys and link ownership

- **Status:** Accepted
- **Created:** 2026-11-20
- **Accepted:** 2026-11-20
- **Amends:** [ADR-0005](0005-api-design.md): adds authentication and ownership to the management
  API it defined. This is an intentional breaking change (see Consequences).

## Context
Scenario 3, symptom 3: a marketing campaign link was deleted, and nobody on the team did it. In
Phases 1 and 2, `DELETE /api/v1/links/{code}` needs no credentials, and `GET /api/v1/links`
lists every link, so anyone can find and delete any link. Nothing records who did either.

The request also says "don't make it painful for normal people". I assumed (scenario 3 §1.2, Q1)
that the anonymous public keeps creating links and following redirects without signing up, and
that known teams such as marketing need links nobody else can touch.

## Options considered
| Option | For | Against |
|---|---|---|
| **Admin-issued API keys + per-key ownership** | Simple for API clients; one key covers create, list, stats and delete; maps onto an identity provider later | Keys must be distributed and kept secret; no self-service sign-up |
| Per-link "manage token" returned on create, no accounts | No registration at all; anonymous users can still delete their own links | A team can't list "our links"; one token per link to store; listing all links stays an open question |
| OAuth 2 / OIDC via an identity provider | Industry standard; SSO for staff | No identity provider exists (Q6); weeks of integration, not days |
| HTTP Basic with user accounts | Familiar | Passwords to store and reset, sign-up flows: a product decision, not a security fix |

**Implementation:** Spring Security with a custom filter, or a hand-written `HandlerInterceptor`.
Spring Security was chosen: it is the standard in Java services, gives default-deny URL rules
in one place, and adds security headers (`X-Content-Type-Options`, `X-Frame-Options`). The cost
is configuration that has to be understood, and some test wiring (every web-slice test now
imports `SecurityConfig`).

## Decision (KC)
- **Keys:** `sk_` + 32 base62 characters from `SecureRandom` (about 190 bits). Sent as
  `Authorization: Bearer <key>`. Issued and revoked by an admin through `/api/v1/admin/keys`. The
  secret appears **once**, in the create response.
- **Storage:** only `SHA-256(key)` and a 9-character display prefix (`sk_Ab3xY9`). A slow password
  hash (bcrypt, Argon2) exists to make brute-forcing *low-entropy* passwords expensive; a 190-bit
  random key cannot be brute-forced either way, and a fast hash keeps per-request authentication
  to one indexed lookup. This is the same approach GitHub and Stripe use for API tokens.
- **Bootstrap admin:** `APP_ADMIN_KEY` from the environment. On startup its hash is stored as an
  admin key, and any *other* bootstrap key is revoked, so rotating the variable really retires the old key.
- **Ownership:** a link created with a key is owned by that key (`owner_key_id`). Get, list, stats
  and delete only cover the caller's own links; admin keys cover all. Anonymous links and links from
  before Phase 3 have no owner and are admin-only; an admin can assign them to a key (Q7).
- **Not yours = not found.** Someone else's link returns `404`, identical to a code that doesn't
  exist (`AbuseIntegrationTest.someoneElsesLinkLooksExactlyLikeAMissingOne` compares the bodies and
  headers). Existence isn't secret: the redirect and `409 ALIAS_TAKEN` reveal it. But ownership and
  a link's details are.
- **Responses:** no key on a protected endpoint gives `401 AUTH_REQUIRED` with
  `WWW-Authenticate: Bearer`. An unknown, revoked or malformed key gives `401 INVALID_API_KEY`
  **even on endpoints that allow anonymous access**: silently falling back to anonymous would hide a
  client's broken configuration behind the much lower anonymous rate limit. A valid non-admin key
  on admin endpoints gives `403 FORBIDDEN`.
- **Audit:** `created_by` (never changes), `owner_key_id` (an admin can reassign it) and
  `deleted_by`. Every log line of an authenticated request carries the key's prefix (MDC `apiKey`).
- **Default deny:** any path not covered by a rule is refused. `/actuator/metrics` became admin-only,
  because metrics reveal traffic and abuse patterns. Health stays public for load balancers.
- **The domain stays framework-free:** `LinkService` takes a `Caller(keyId, admin)`, not a Spring
  Security type. Every management method requires one, so forgetting the ownership check is a
  compile error, not a silent hole.

## Consequences
- **Breaking change, on purpose.** Unauthenticated `DELETE`, list and stats now get `401`. The open
  contract *was* the vulnerability. Rollout: issue the marketing key before the deploy, so they add
  one header on the day. A "log-only" grace period was rejected, because the abuse is happening now.
- Unknown paths such as `/favicon.ico` now return `401` (anonymous) or `403` (with a key) instead of
  `404`. A side effect of default-deny, accepted: the safer failure mode when someone adds an endpoint
  and forgets to write its rule.
- Every authenticated request does one indexed lookup on `api_keys`. An in-memory cache would
  remove it, but then revocation would not be immediate. Not needed at current load.
- **Migration V3 is expand-only**, with the foreign keys added `NOT VALID` and validated by V4
  under a lock that doesn't block reads or writes (scenario 3 §2). The index on `owner_key_id` is a
  plain `CREATE INDEX`, which blocks writes to `links` while it builds: milliseconds at our size. On
  a large table it would be `CREATE INDEX CONCURRENTLY` in its own non-transactional migration.
- No self-service: every key goes through an admin. That fits "a known marketing team" (Q1); public
  developer sign-up would be a product decision and an identity-provider project (IMP-022).
