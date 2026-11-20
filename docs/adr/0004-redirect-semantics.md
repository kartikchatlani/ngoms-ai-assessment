# ADR-0004: 302 redirects, 404 vs 410, soft delete without code reuse

- **Status:** Accepted
- **Created:** 2026-11-18
- **Accepted:** 2026-11-18

## Context
`GET /{code}` is the hot path and the core functionality of the service. The status code we choose controls
caching, whether deletes and expiry actually take effect, and whether we can count clicks.

## Options considered
| Status | Behaviour | Verdict |
|---|---|---|
| 301 Moved Permanently | Browsers cache **indefinitely**, so later clicks never reach us | Rejected: deleting or expiring a link would not work for anyone who clicked before, and analytics (Phase 2) would undercount |
| **302 Found** | Not cached by default; every click hits us | **Chosen** |
| 307 / 308 | Preserve the method (POST stays POST) | Unnecessary: link clicks are GETs; 302 has the widest compatibility |

## Decision
- **302 + `Cache-Control: no-store`.** Correctness (deletes and expiry take effect immediately) and
  observability outweigh the extra traffic. Server-side caching (a later phase) absorbs the load instead.
- **Unknown code gives 404; expired or deleted code gives 410 Gone.** 410 tells crawlers and clients the link
  existed and is permanently gone, so they stop retrying. The `errorCode` (`LINK_EXPIRED` /
  `LINK_DELETED`) says why.
- **Soft delete (`deleted_at`) and codes are never reused.** A code may already be printed on a
  flyer or posted in a chat. If it were reissued, old traffic would go to a new owner's destination,
  which is a hijacking/phishing vector. The integration test `deletedAliasCannotBeReclaimed`
  asserts this.
- **Expiry is inclusive**: a link with `expiresAt = T` is expired at instant T.
- The route only matches `[A-Za-z0-9_-]{3,32}`, so requests such as `/favicon.ico` never touch the DB.

## Consequences
- Every click reaches the service, so it must be fast and highly available (caching is planned).
- Deleted rows stay in the table forever. At about 200 bytes per row this is negligible. A
  purge job could hard-delete the URL text but keep a tombstone for the code.
- Errors on the redirect path return JSON Problem Details. A browser user sees JSON instead of a
  friendly page (noted in IMPROVEMENTS.md).
