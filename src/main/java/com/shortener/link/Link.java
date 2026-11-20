package com.shortener.link;

import java.time.Instant;

/**
 * A stored short link. Immutable; state changes (delete) go through the repository.
 *
 * @param expiresAt null = never expires
 * @param deletedAt null = live; non-null = soft-deleted (code is never reused, ADR-0004)
 */
public record Link(
        long id,
        String code,
        String longUrl,
        boolean customAlias,
        Instant createdAt,
        Instant expiresAt,
        Instant deletedAt) {

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Expiry is inclusive: a link expiring at T is already expired at T. */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public LinkStatus statusAt(Instant now) {
        if (isDeleted()) {
            return LinkStatus.DELETED;
        }
        return isExpiredAt(now) ? LinkStatus.EXPIRED : LinkStatus.ACTIVE;
    }
}
