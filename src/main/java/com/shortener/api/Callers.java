package com.shortener.api;

import com.shortener.auth.ApiKey;
import com.shortener.link.Caller;

/** Converts the authenticated principal (or its absence) into the domain's {@link Caller}. */
final class Callers {

    private Callers() {
    }

    /** @param key from {@code @AuthenticationPrincipal}; null for anonymous requests */
    static Caller of(ApiKey key) {
        return key == null ? Caller.ANONYMOUS : Caller.key(key.id(), key.admin());
    }
}
