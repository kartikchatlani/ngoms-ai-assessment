package com.shortener.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated application settings (bound from {@code app.*}).
 * Invalid values fail fast at startup instead of misbehaving at runtime.
 *
 * @param baseUrl         public origin used to build short URLs, e.g. {@code https://sho.rt}
 * @param codeLength      length of generated codes; 7 base62 chars = ~3.5 trillion codes (ADR-0003)
 * @param codeMaxAttempts collision retries before giving up with 503 (ADR-0003)
 * @param maxUrlLength    longest destination URL accepted
 * @param maxPageSize     upper bound for list page size
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(
        @NotBlank String baseUrl,
        @DefaultValue("7") @Min(5) @Max(16) int codeLength,
        @DefaultValue("5") @Min(1) @Max(20) int codeMaxAttempts,
        @DefaultValue("2048") @Min(64) @Max(8192) int maxUrlLength,
        @DefaultValue("100") @Min(1) @Max(1000) int maxPageSize) {

    /** Base URL without a trailing slash, so {@code baseUrl + "/" + code} is always well-formed. */
    public String normalizedBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
