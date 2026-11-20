# ADR-0003: Random base62 codes; uniqueness enforced by the database

- **Status:** Accepted
- **Created:** 2026-11-18
- **Accepted:** 2026-11-18

## Context
Every link needs a short, URL-safe, unique code. The generation scheme decides whether codes
can be guessed, whether multiple instances must coordinate, and what happens on collision.

## Options considered
| Option | How | For | Against |
|---|---|---|---|
| Hash of URL (e.g. first 7 chars of SHA-256, base62) | Deterministic | Same URL gives the same code | Collisions between *different* URLs still need handling; forces dedupe semantics we don't want (ADR-0005); predictable: anyone can test whether a URL has been shortened |
| DB sequence + base62 | Counter | No collisions, shortest codes | **Enumerable:** `/1`, `/2`, ... lets anyone crawl every link (privacy leak); reveals volume; needs a central counter |
| Sequence + bijective scramble (e.g. Feistel) | Counter, obfuscated | No collisions, not obviously sequential | Obfuscation, not security; more code to get right |
| Snowflake-style IDs | Time + worker + seq | Coordination-free | Long (11+ chars); time-ordered, so partially guessable |
| **Random base62 from a CSPRNG + retry on conflict** | `SecureRandom` | Unguessable, stateless, coordination-free across instances | Small, retryable collision probability |

## Decision
- 7 characters from `[0-9A-Za-z]` drawn with `SecureRandom.nextInt(62)` (unbiased).
  62^7 is about **3.52 × 10^12** codes.
- Insert with `INSERT ... ON CONFLICT (code) DO NOTHING RETURNING ...`. An empty result
  means a collision, so we draw again, up to `app.code-max-attempts` (5). If every attempt
  collides we return **503** and log an error: this signals a full code space or a broken generator.
- The **database `UNIQUE` constraint is the only source of truth.** We never "check then insert",
  which would race between instances.
- Generated codes that match a reserved word (`api`, `favicon`, ...) are skipped.
- Custom aliases use the same table and constraint, so an alias can never shadow a generated code
  or the reverse. Alias conflicts return **409** immediately (they are not retried).

## Collision math
The probability that one new code collides is `stored / 62^7`.

| Links stored | P(collision) per create | P(5 consecutive collisions) |
|---|---|---|
| 1 million | 2.8 × 10^-7 | ~10^-33 |
| 1 billion | 2.8 × 10^-4 | ~10^-18 |
| 100 billion | 2.8 × 10^-2 | ~10^-8 |

Up to billions of links, retries are effectively free. Long before the code space fills, the
mitigation is a config change, `APP_CODE_LENGTH=8` (×62 capacity). Existing 7-char codes keep working.

## Consequences
- Codes are case-sensitive (`abc` ≠ `ABC`). Case-folding would cut the space to 36^7 (≈ 7.8 × 10^10).
- Base62 includes visually ambiguous characters (`0/O`, `1/l/I`). This was accepted for density.
  A human-friendly alphabet is listed in IMPROVEMENTS.md.
- A collision costs one extra indexed insert. A `WARN` log line makes the collision rate observable.
- `ON CONFLICT DO NOTHING` also makes concurrent creates of the same alias safe without locks.
  An integration test sends 16 parallel requests and asserts exactly one wins.
