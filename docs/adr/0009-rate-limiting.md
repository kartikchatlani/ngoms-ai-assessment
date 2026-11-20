# ADR-0009: Rate-limit link creation per key and per IP

- **Status:** Accepted
- **Created:** 2026-11-20
- **Accepted:** 2026-11-20

## Context
Scenario 3, symptom 2: the links table grew much faster than traffic, which looks like a script
mass-creating links. Nothing limits `POST /api/v1/links`. The fix must not make the service
"painful for normal people" (the request's main constraint). I assumed (Q3) that a person creates
a handful of links per session while a script creates thousands, and that redirects, which normal
people use most, must not be limited at all.

## Options considered
| Option | For | Against |
|---|---|---|
| **Token bucket per key / per IP, in memory** | Allows a burst, then a steady rate; O(1) per request; no new infrastructure | Each instance has its own counts (N instances = up to N x the limit); lost on restart |
| Fixed window ("10 per clock hour") | Simplest to explain | A script gets 2x the allowance around each window boundary (10 at 12:59, 10 at 13:00) |
| Token bucket in Redis | Correct across instances | New infrastructure; one instance today (Q8) |
| Bucket4j library | Well tested; Redis/JCache backends | A dependency for ~60 lines of logic; the backend choice is the hard part and it's deferred |
| CAPTCHA on create | Stops simple bots | Friction for every normal user; doesn't work for API clients |
| Limit at the load balancer / CDN | Protects everything, including redirects | No such layer in this deployment; can't tell keys apart without app knowledge |

## Decision
- **Token bucket:** capacity = hourly allowance, refilled continuously. Anonymous: **10 per hour
  per client IP**. API key: **1,000 per hour per key**. Admin keys: unlimited. All values are
  configuration (`APP_ANONYMOUS_CREATES_PER_HOUR`, `APP_KEY_CREATES_PER_HOUR`).
- **Only `POST /api/v1/links` is limited.** Redirects are not: limiting them would hurt the people
  clicking campaign links, and redirect floods are a DDoS problem for the edge, not the app.
- **Over the limit:** `429 Too Many Requests`, `Retry-After` in seconds until the next token, and
  `errorCode: RATE_LIMITED`. Counted in `shortener.ratelimit.rejected{tier=anonymous|key}`.
- **Client IP:** the socket address. `X-Forwarded-For` is ignored unless the request comes from a
  configured trusted proxy (`APP_TRUSTED_PROXIES`). Then the right-most hop that isn't one of our
  proxies is used, because everything to its left was written by the client. Trusting the header by
  default would let a script set a new fake IP on every request and never be limited.
- **Memory bound:** a bucket that has refilled completely carries no information, so a sweep every
  5 minutes removes them. Memory is proportional to clients active within the last hour.
- **Behind an interface-shaped seam:** `CreateRateLimiter.check(keyId, admin, ip)` is the only
  call site, so a Redis-backed version replaces one class.

## Consequences
- **Per-instance limits.** With N app instances the effective limit is up to N times higher.
  Correct for one instance; a shared store is required before scaling out (IMP-002 stays open
  for that part).
- **Shared IPs share a limit.** An office or a mobile carrier's NAT puts many people behind one
  address. Ten anonymous links per hour is generous for a person and tight for an office; teams
  that create many links get a key with its own limit (scenario 3 risk register).
- **Docker Desktop:** all local requests reach the container from the same gateway address, so
  locally every anonymous caller shares one bucket. Not an issue in a real deployment.
- **A restart resets every bucket.** Acceptable: a script gains at most one extra allowance per
  restart.
- IPv6 clients can rotate through addresses in their /64 cheaply. Limiting by /64 prefix is the
  usual fix (IMP-023).
