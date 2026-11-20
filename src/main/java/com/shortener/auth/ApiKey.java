package com.shortener.auth;

import java.time.Instant;

/**
 * A stored API key, without its secret (only the hash is persisted, and it is not part of this
 * type, so it cannot leak through a response or a log line by accident).
 *
 * @param prefix first characters of the key, safe to display, e.g. {@code sk_Ab3xY9}
 */
public record ApiKey(long id, String name, String prefix, boolean admin, Instant createdAt, Instant revokedAt) {

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
