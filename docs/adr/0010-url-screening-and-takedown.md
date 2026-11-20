# ADR-0010: URL screening and admin takedown

- **Status:** Accepted
- **Created:** 2026-11-20
- **Accepted:** 2026-11-20

## Context
Scenario 3, symptom 1: Chrome showed a "deceptive site ahead" warning on one of our short links,
and users reported links to fake login pages. `UrlPolicy` checks only the *shape* of a URL
(scheme, host, no credentials, not our own host), not its *reputation*. Two things are needed:
remove the phishing links that already exist, and make new ones harder to create. Both protect
our domain's reputation: browsers and mail filters judge a shortener by where its links go.

I assumed (Q4) there is no approved external reputation service or budget yet, and that sending
every submitted URL to a third party is a privacy decision for someone else to make.

## Options considered
| Option | For | Against |
|---|---|---|
| **Admin-managed domain blocklist, checked on create *and* on redirect** | Instant effect on existing links; no external dependency; covers subdomains | Reactive: a domain must be reported before it's blocked |
| **Refuse destinations on other URL shorteners** | Closes a common phishing trick: a chain of short links hides the real target from users and from our screening | Some legitimate users shorten a `bit.ly` link; they can use the original URL |
| **Admin takedown of a single link** | Handles one reported link without blocking a whole domain (e.g. a shared host) | Manual |
| Google Safe Browsing / Web Risk API | Proactive; large, maintained threat list | API key, cost, and every user URL sent to Google (Q4); deferred, same interface |
| Interstitial warning page before redirecting | Users see the destination first | Friction on every click for everyone: violates the request's main constraint |
| Fetch the destination and inspect it | Can catch fresh phishing pages | SSRF risk, slow, easy to cloak; a project of its own |

## Decision
- **`UrlScreener`** (in-memory, refreshed from the `blocked_domains` table):
  - **Blocked domains:** a blocked domain blocks itself and every subdomain (`evil.example` blocks
    `login.evil.example`) but not lookalikes (`notevil.example`). Matching is by DNS label, after
    lower-casing and removing a trailing dot. A blocked domain must contain a dot, so a whole TLD
    can't be blocked by a typo.
  - **Checked on create** (400 `URL_NOT_ALLOWED`) **and on every redirect** (410 `LINK_BLOCKED`).
    Blocking a domain therefore disables every existing link to it at once, which is what the
    Chrome warning needs.
  - **Known URL shorteners** (configurable list: `bit.ly`, `t.co`, `tinyurl.com`, ...): refused on
    create only. Existing links to them keep working; they are not malicious in themselves.
- **Admin API:** `POST/DELETE /api/v1/admin/blocked-domains` takes effect immediately on the
  instance that handled it, and on others within the refresh interval (30 s). Takedown of a single
  link: `PUT/DELETE /api/v1/admin/links/{code}/block` with a reason. The owner sees status
  `BLOCKED` but not the reason, and can't undo it.
- **Same response for every screening rule.** The client gets one generic `URL_NOT_ALLOWED`; the
  specific rule is logged. Distinct messages would let abusers map out which domains are listed.
- **The status agrees everywhere:** `LinkService.statusOf` applies the blocklist too, so an owner
  never sees `ACTIVE` for a link that actually returns 410.
- **Fail safe:** if a blocklist reload fails (DB blip), the previous list is kept. An empty list on
  error would silently unblock every phishing domain.

## Consequences
- **Reactive, not proactive.** A new phishing domain works until someone reports it. Safe Browsing
  (IMP-003, still open) is the proactive layer; it would plug in next to the blocklist.
- **Redirect cost:** parsing the stored URL's host plus a few hash lookups, with no I/O. Measured
  with the Phase 2 benchmark ([scenario 3 §3](../scenarios/03-ambiguous.md)).
- **Multi-instance lag:** up to 30 s before another instance sees a new block. A pub/sub
  notification would make it instant if that ever matters.
- **Chrome's warning won't lift by itself.** After takedown, someone must request a review in Google
  Search Console. That's an operational step, documented in the scenario.
- No user-facing way to *report* a link yet (IMP-024): reports still arrive by email to support.
