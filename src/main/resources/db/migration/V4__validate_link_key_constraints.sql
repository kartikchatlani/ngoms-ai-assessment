-- V4: validate the foreign keys V3 added as NOT VALID (Phase 3, ADR-0008).
--
-- A separate migration means a separate transaction, so V3's ACCESS EXCLUSIVE lock is already
-- released. VALIDATE CONSTRAINT takes only SHARE UPDATE EXCLUSIVE on `links`, which does not
-- block reads or writes, so redirects and creates continue while existing rows are checked.
-- (Every existing row has NULL in these columns, so validation cannot fail.)
ALTER TABLE links VALIDATE CONSTRAINT fk_links_owner_key;
ALTER TABLE links VALIDATE CONSTRAINT fk_links_created_by;
ALTER TABLE links VALIDATE CONSTRAINT fk_links_deleted_by;
