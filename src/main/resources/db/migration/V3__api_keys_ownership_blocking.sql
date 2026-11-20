-- V3: API keys, link ownership, takedown and domain blocklist (Phase 3). See docs/adr/0008-0010.
--
-- Expand-only, like V2: new tables plus nullable columns on `links`, no rewrites and no backfill.
-- The Phase 2 build ignores the new columns, so a rollback stays possible.

-- ALTER TABLE ... ADD COLUMN takes an ACCESS EXCLUSIVE lock on `links`, which blocks reads,
-- including redirects, for as long as it is held. Adding a nullable column without a default is
-- a catalog-only change, so the lock is held for milliseconds. The risk is waiting in the lock
-- queue behind a long transaction while redirects queue behind us, so fail fast instead.
SET LOCAL lock_timeout = '5s';

-- Only a SHA-256 hash of each key is stored. Keys are 190-bit random values, so a fast hash is
-- enough: there is no low-entropy password to brute-force (ADR-0008).
CREATE TABLE api_keys (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    key_hash    CHAR(64)     NOT NULL,
    -- First characters of the key, safe to show and log, so a key can be identified without the secret.
    key_prefix  VARCHAR(16)  NOT NULL,
    admin       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ  NULL,

    CONSTRAINT uq_api_keys_hash UNIQUE (key_hash)
);

-- Ownership and audit. All nullable: links created before Phase 3 (and anonymous links) have no
-- owner, and only an admin can manage them (scenario 3, Q7).
--   owner_key_id: who can manage the link now (an admin can reassign it)
--   created_by:   which key created it; never changes
--   deleted_by:   which key deleted it ("who deleted the campaign link?")
ALTER TABLE links
    ADD COLUMN owner_key_id   BIGINT       NULL,
    ADD COLUMN created_by     BIGINT       NULL,
    ADD COLUMN deleted_by     BIGINT       NULL,
    ADD COLUMN blocked_at     TIMESTAMPTZ  NULL,
    ADD COLUMN blocked_reason VARCHAR(200) NULL,
    -- NOT VALID: enforced for new and updated rows immediately, but skips scanning the existing
    -- rows while we hold the lock above. A normal ADD CONSTRAINT would scan all of `links` with
    -- redirects blocked. V4 validates them under a lock that does not block reads or writes.
    ADD CONSTRAINT fk_links_owner_key  FOREIGN KEY (owner_key_id) REFERENCES api_keys (id) NOT VALID,
    ADD CONSTRAINT fk_links_created_by FOREIGN KEY (created_by)   REFERENCES api_keys (id) NOT VALID,
    ADD CONSTRAINT fk_links_deleted_by FOREIGN KEY (deleted_by)   REFERENCES api_keys (id) NOT VALID;

-- "My links" page: an owner's live links, newest first (same keyset pattern as ix_links_live_id_desc).
-- A plain CREATE INDEX blocks writes to `links` (not reads) while it builds. That is milliseconds
-- at our size. On a large table this would be CREATE INDEX CONCURRENTLY in its own
-- non-transactional migration (ADR-0008, consequences).
CREATE INDEX ix_links_owner_live_id_desc ON links (owner_key_id, id DESC) WHERE deleted_at IS NULL;

-- Domains blocked by an admin. Checked on create and on every redirect, from an in-memory copy.
-- Stored lower-case; a blocked domain also blocks all of its subdomains.
CREATE TABLE blocked_domains (
    domain      VARCHAR(253) PRIMARY KEY,
    reason      VARCHAR(200) NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  BIGINT       NULL REFERENCES api_keys (id),

    CONSTRAINT ck_blocked_domains_lower CHECK (domain = lower(domain))
);
