# Testing approach

> Current as of **Phase 1**.

## Philosophy
- **Test behaviour, not implementation.** Tests assert outcomes (status codes, stored state, error
  codes) so that refactoring does not break them.
- **Each level answers a different question.** We don't re-test the same thing three times.
- **Real dependencies where they matter.** SQL, constraints and concurrency are tested against real
  PostgreSQL (Testcontainers), not H2. H2 has no compatible `ON CONFLICT`, so it would test
  something other than production.
- **Deterministic.** Time comes from an injected `Clock`, there are no `sleep`s, and statistical tests use
  bounds about 10 standard deviations wide.
- **Every bug gets a regression test**, named `..._regressionBugNNN` and cross-referenced in
  [BUGS.md](BUGS.md).

## The pyramid

| Level | Question answered | Speed | Location |
|---|---|---|---|
| **Unit** | Are the business rules right? | ms | `src/test/java/com/shortener/link/*Test.java` |
| **Web slice** | Is the HTTP contract right (status, headers, JSON, error shape)? | ~2s total | `src/test/java/com/shortener/api/ApiWebTest.java` |
| **Integration** | Does it really work with Postgres, Flyway and HTTP together, including under concurrency? | ~10s + container start | `src/test/java/com/shortener/LinkApiIntegrationTest.java` |
| **Smoke** | Does the deployed artifact work? | seconds | `scripts/smoke-test.sh` |

## How to run

```bash
./mvnw test                      # unit + web + integration (integration auto-skips without Docker)
./mvnw test -Dtest=LinkServiceTest   # a single class
docker compose up --build -d && ./scripts/smoke-test.sh   # end-to-end against the container
```
**Windows:** use `mvnw.cmd` in PowerShell, and run the smoke script with `bash ./scripts/smoke-test.sh`.
PowerShell can't execute `.sh` files directly; it opens them in the associated editor instead.

## Coverage of risk (not just lines)
The risk register in [scenario 1 §1.2](scenarios/01-greenfield.md) maps each identified risk to the test
that guards it. The key behaviours are summarised in §3.4 of that document.

## Known gaps
- Integration tests **auto-skip when Docker isn't running**, so a green build without Docker is
  not a full pass. Check that the `LinkApiIntegrationTest` line reports `Skipped: 0`. (Last full run:
  99/99 passed.) CI (IMP-004) would make this impossible to miss.
- No load/performance test yet. It is planned for when caching is introduced, so there is a baseline to
  compare against.
- No CI pipeline yet (IMPROVEMENTS.md). Tests run locally.
- No mutation testing or coverage gate.
