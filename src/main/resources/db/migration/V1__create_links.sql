-- V1: core links table (Phase 1, 2026-11-18). See docs/adr/0002 and 0004.
CREATE TABLE links (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Case-sensitive on purpose: base62 codes rely on case (ADR-0003).
    code         VARCHAR(32)  NOT NULL,
    long_url     TEXT         NOT NULL,
    custom_alias BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ  NULL,
    -- Soft delete: row is kept so the code can never be re-issued to someone else (ADR-0004).
    deleted_at   TIMESTAMPTZ  NULL,

    -- The uniqueness guarantee lives in the database, not in application code.
    -- INSERT ... ON CONFLICT (code) relies on this constraint (ADR-0003).
    CONSTRAINT uq_links_code UNIQUE (code),
    CONSTRAINT ck_links_code_format CHECK (code ~ '^[A-Za-z0-9_-]{3,32}$'),
    CONSTRAINT ck_links_expiry_after_creation CHECK (expires_at IS NULL OR expires_at > created_at)
);

-- Keyset pagination for the list endpoint walks live links by id DESC (ADR-0005).
CREATE INDEX ix_links_live_id_desc ON links (id DESC) WHERE deleted_at IS NULL;
