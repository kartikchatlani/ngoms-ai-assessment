# ADR-0005: API conventions: versioning, Problem Details, keyset pagination, no dedupe

- **Status:** Accepted
- **Created:** 2026-11-18
- **Accepted:** 2026-11-18
- **Amended by:** [ADR-0008](0008-api-keys-and-ownership.md): management endpoints (get, list,
  delete, stats) now require an API key; list returns only the caller's links; a link the caller
  doesn't own is 404; unmatched paths are 401/403 instead of 404. The conventions themselves
  (versioning, Problem Details, keyset pagination, no dedupe) are unchanged.

## Context
The management API will be consumed by other programs, so its failure modes and evolution
must be predictable.

## Decisions (KC)

### 1. URI versioning: `/api/v1/...`
**Options:** URI prefix, custom media type (`Accept: application/vnd...v1+json`), or a header.
**Chosen:** URI prefix. It is visible in logs, trivially routable at a gateway, and obvious to
humans. The redirect path `/{code}` is deliberately unversioned, because short URLs live forever.

### 2. Errors are RFC 9457 Problem Details with a stable `errorCode`
```json
{ "type": "about:blank", "title": "Alias already taken", "status": 409,
  "detail": "Alias 'promo' is already taken", "errorCode": "ALIAS_TAKEN" }
```
- Clients branch on `errorCode` (stable contract), never on `detail` (human text, may change).
- Validation errors add `errors: [{field, message}]`.
- Unexpected errors are logged with full detail server-side and return a generic message.
  Stack traces and SQL never reach clients.
- Database unreachable gives **503 + `Retry-After`** rather than 500, so clients know it is transient.

### 3. Keyset (cursor) pagination: `GET /api/v1/links?limit=20&cursor=...`
**Options:** offset/limit, page numbers, or keyset.
**Chosen:** keyset on `id DESC`, backed by a partial index on live rows.
- Offset pagination degrades linearly (`OFFSET 100000` scans 100k rows) and skips or duplicates
  rows when links are inserted between page requests. Keyset is O(log n) per page and stable.
- The cursor is **opaque** (base64url). Clients must not parse it, so we can change the key
  later (for example to `(created_at, id)`) without breaking anyone.
- We fetch `limit + 1` rows to know whether a next page exists, with no `COUNT(*)`.

### 4. No deduplication of identical URLs
Submitting the same URL twice returns two different codes.
- Links carry their own expiry, and in Phase 2 their own click stats. Two campaigns that share a
  destination must be measurable separately.
- Dedupe would leak information ("has anyone shortened this URL?").
- Cost: duplicate rows. Mitigation if needed: an `Idempotency-Key` header (IMPROVEMENTS.md),
  which solves the real problem (client retries) without merging distinct links.

### 5. Internal ids are never exposed
Responses identify links by `code` only. The surrogate `id` is an implementation detail.

## Consequences
- Problem Details give a consistent error shape across our own errors and Spring's built-in ones
  (malformed JSON, wrong method, bad query param types).
- Cursor pagination cannot jump to page N. That is acceptable for an API, though a UI might want it.
