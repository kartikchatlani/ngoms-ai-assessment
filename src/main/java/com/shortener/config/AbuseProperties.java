package com.shortener.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Abuse controls (bound from {@code app.abuse.*}). The numbers are assumptions from scenario 3
 * §1.2 (Q3), kept in configuration so they can change without code.
 *
 * @param anonymousCreatesPerHour links one IP address may create per hour without an API key (ADR-0009)
 * @param keyCreatesPerHour       links one API key may create per hour (admin keys are not limited)
 * @param shortenerDomains        other URL shorteners. Links to them are refused, because a chain of
 *                                short links hides the real destination (ADR-0010)
 * @param blocklistRefreshSeconds how often each instance reloads the admin-managed domain blocklist,
 *                                so a block made on one instance reaches the others
 */
@Validated
@ConfigurationProperties("app.abuse")
public record AbuseProperties(
        @DefaultValue("10") @Min(1) @Max(1_000_000) int anonymousCreatesPerHour,
        @DefaultValue("1000") @Min(1) @Max(10_000_000) int keyCreatesPerHour,
        @DefaultValue({"bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly",
                "rebrand.ly", "cutt.ly", "tiny.cc", "shorturl.at", "rb.gy", "t.ly", "s.id"})
        List<String> shortenerDomains,
        @DefaultValue("30") @Min(1) @Max(3600) int blocklistRefreshSeconds) {
}
