# Bug log

Every defect found, including ones caught before release. Format: symptom, root cause, fix, and
the regression test. Entries are never deleted.

| ID | Date found | Phase | Severity | Status |
|---|---|---|---|---|
| [BUG-001](#bug-001) | 2026-11-18 | 1 | Medium | Fixed |

---

## BUG-001
**Far-future `expiresAt` returns 500 instead of 400**

- **Found:** 2026-11-18, code review of the create path (before first release)
- **Severity:** Medium. Any client could trigger a 500 and an ERROR log line on demand, which is noise
  in alerting and could be used for log flooding. No data corruption.
- **Symptom:** `POST /api/v1/links {"url":"https://example.com","expiresAt":"+100000-01-01T00:00:00Z"}`
  returns `500 INTERNAL_ERROR`.
- **Root cause:** `java.time.Instant` supports years up to ±1,000,000,000, but PostgreSQL
  `timestamptz` ends at 294276 AD. Validation checked only that expiry was *in the future*, so the
  value passed validation and failed in the database/driver layer, which the catch-all handler maps
  to 500.
- **Fix:** `LinkService.create` rejects `expiresAt` after `9999-12-31T23:59:59Z`
  (`LinkService.MAX_EXPIRY`) with `400 INVALID_REQUEST` (field `expiresAt`).
- **Regression tests:**
  - `LinkServiceTest.rejectsExpiryBeyondStorableRange_regressionBug001`
  - `LinkServiceTest.acceptsExpiryAtTheUpperBound`
  - `LinkApiIntegrationTest.expiryBoundsAreSafeForPostgres_regressionBug001` (proves the bound itself is
    storable in real Postgres)
- **Lesson:** validate against the *storage* type's range, not only the language type's range.
  The same check is worth doing for every bounded column added later.
