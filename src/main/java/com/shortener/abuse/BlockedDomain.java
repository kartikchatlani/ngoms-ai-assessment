package com.shortener.abuse;

import java.time.Instant;

/** A destination domain an admin has blocked; also blocks every subdomain (ADR-0010). */
public record BlockedDomain(String domain, String reason, Instant createdAt, Long createdBy) {
}
