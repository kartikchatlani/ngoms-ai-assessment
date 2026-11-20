package com.shortener.link;

import java.time.Instant;

/**
 * Insert payload: a link that has not been persisted yet.
 *
 * @param ownerKeyId creating API key, which becomes the owner; null = anonymous (admin-only)
 */
public record NewLink(String code, String longUrl, boolean customAlias, Instant createdAt, Instant expiresAt,
                      Long ownerKeyId) {

    /** Anonymous link. */
    public NewLink(String code, String longUrl, boolean customAlias, Instant createdAt, Instant expiresAt) {
        this(code, longUrl, customAlias, createdAt, expiresAt, null);
    }
}
