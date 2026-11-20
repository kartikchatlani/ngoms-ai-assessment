package com.shortener.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Authentication and client-identification settings (bound from {@code app.security.*}).
 *
 * @param adminKey       bootstrap admin API key. On startup its hash is stored as an admin key,
 *                       so there is always a way in to issue the first client keys. Blank = no
 *                       bootstrap admin (ADR-0008)
 * @param trustedProxies IP addresses of reverse proxies whose {@code X-Forwarded-For} header we
 *                       believe. Empty (the default) = the header is ignored, because anyone can
 *                       set it to dodge per-IP rate limits (ADR-0009)
 */
@Validated
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @DefaultValue("") String adminKey,
        @DefaultValue({}) List<String> trustedProxies) {
}
