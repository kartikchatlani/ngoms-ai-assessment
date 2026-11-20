package com.shortener.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Click analytics settings (bound from {@code app.analytics.*}, ADR-0006).
 *
 * @param enabled        kill switch: when false, clicks are not recorded (redirects are unaffected;
 *                       existing stats stay readable). Lets the feature ship dark and be turned off
 *                       without a redeploy of different code.
 * @param bufferCapacity clicks held in memory waiting to be written. When full, new clicks are
 *                       dropped (and counted) rather than slowing down redirects.
 * @param batchSize      most clicks written in one database round trip
 */
@Validated
@ConfigurationProperties("app.analytics")
public record AnalyticsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10000") @Min(1) @Max(1_000_000) int bufferCapacity,
        @DefaultValue("500") @Min(1) @Max(10_000) int batchSize) {
}
