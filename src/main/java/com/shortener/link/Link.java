package com.shortener.link;

import java.time.Instant;

/**
 * A stored short link. Immutable; state changes (delete, block) go through the repository.
 *
 * @param expiresAt     null = never expires
 * @param deletedAt     null = live; non-null = soft-deleted (code is never reused, ADR-0004)
 * @param ownerKeyId    API key that can manage this link; null = admin-only (anonymous or pre-Phase 3)
 * @param blockedAt     non-null = taken down by an admin (ADR-0010)
 * @param blockedReason why it was taken down; internal, never shown to the public
 */
public record Link(
        long id,
        String code,
        String longUrl,
        boolean customAlias,
        Instant createdAt,
        Instant expiresAt,
        Instant deletedAt,
        Long ownerKeyId,
        Instant blockedAt,
        String blockedReason) {

    /** A link with no owner and no takedown: every link before Phase 3 looked like this. */
    public Link(long id, String code, String longUrl, boolean customAlias,
                Instant createdAt, Instant expiresAt, Instant deletedAt) {
        this(id, code, longUrl, customAlias, createdAt, expiresAt, deletedAt, null, null, null);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isBlocked() {
        return blockedAt != null;
    }

    /** Expiry is inclusive: a link expiring at T is already expired at T. */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** Most severe first: a deleted link is DELETED even if it was also blocked or has expired. */
    public LinkStatus statusAt(Instant now) {
        if (isDeleted()) {
            return LinkStatus.DELETED;
        }
        if (isBlocked()) {
            return LinkStatus.BLOCKED;
        }
        return isExpiredAt(now) ? LinkStatus.EXPIRED : LinkStatus.ACTIVE;
    }
}
