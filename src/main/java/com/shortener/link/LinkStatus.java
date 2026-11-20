package com.shortener.link;

public enum LinkStatus {
    ACTIVE,
    EXPIRED,
    DELETED,
    /** Taken down by an admin, or its destination domain is on the blocklist (Phase 3, ADR-0010). */
    BLOCKED
}
