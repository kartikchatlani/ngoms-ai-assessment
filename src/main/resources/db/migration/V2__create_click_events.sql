-- V2: click analytics (Phase 2). See docs/adr/0006 and 0007.
--
-- Expand-only: nothing in `links` changes, so the Phase 1 build keeps working against this
-- schema. That is what makes a rolling deploy and a rollback safe (ADR-0007).

-- Creating the foreign key briefly takes a SHARE ROW EXCLUSIVE lock on `links`. That blocks
-- writes to `links` (not reads, so redirects keep working) and is instant on an empty table.
-- The danger on a busy system is waiting in the lock queue behind a long transaction, with new
-- traffic queueing behind us. Fail the migration fast instead; it is safe to retry.
SET LOCAL lock_timeout = '5s';

-- One row per counted redirect. Privacy by design: no IP address, no raw User-Agent, and only
-- the host of the referrer (paths and query strings can carry tokens or personal data).
CREATE TABLE click_events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    link_id       BIGINT       NOT NULL,
    clicked_at    TIMESTAMPTZ  NOT NULL,
    referrer_host VARCHAR(255) NULL,
    agent_class   VARCHAR(16)  NOT NULL,

    -- Links are never hard-deleted (ADR-0004), so this reference stays valid.
    CONSTRAINT fk_click_events_link FOREIGN KEY (link_id) REFERENCES links (id),
    CONSTRAINT ck_click_events_agent_class CHECK (agent_class IN ('DESKTOP', 'MOBILE', 'BOT', 'UNKNOWN'))
);

-- Every stats query is "clicks for one link, optionally within a time window".
CREATE INDEX ix_click_events_link_time ON click_events (link_id, clicked_at);
