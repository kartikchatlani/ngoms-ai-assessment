package com.shortener.link;

import java.time.Instant;

/** Insert payload: a link that has not been persisted yet. */
public record NewLink(String code, String longUrl, boolean customAlias, Instant createdAt, Instant expiresAt) {
}
