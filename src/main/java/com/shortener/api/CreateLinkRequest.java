package com.shortener.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Body of POST /api/v1/links. Bean Validation here catches shape errors cheaply; the business
 * rules (scheme, host, reserved aliases, ...) live in the domain policies.
 *
 * @param url       destination, required
 * @param alias     optional custom code
 * @param expiresAt optional ISO-8601 instant, e.g. "2026-12-31T23:59:59Z"
 */
public record CreateLinkRequest(
        @NotBlank @Size(max = 8192) String url,
        @Size(max = 64) String alias,
        Instant expiresAt) {
}
