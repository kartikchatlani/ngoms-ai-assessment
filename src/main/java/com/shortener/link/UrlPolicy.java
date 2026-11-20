package com.shortener.link;

import com.shortener.config.AppProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides whether a destination URL is acceptable. Phase 1 rules are purely syntactic;
 * reputation/DNS-based checks are deliberately deferred (see docs/limitations-tradeoffs.md).
 */
@Component
public class UrlPolicy {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final int maxLength;
    private final String ownHost;

    public UrlPolicy(AppProperties props) {
        this.maxLength = props.maxUrlLength();
        this.ownHost = hostOf(props.normalizedBaseUrl());
    }

    /**
     * @return the trimmed URL, unchanged otherwise (we store what the user gave us)
     * @throws InvalidRequestException with a user-facing reason
     */
    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("url", "must not be blank");
        }
        String url = raw.strip();
        if (url.length() > maxLength) {
            throw new InvalidRequestException("url", "must be at most " + maxLength + " characters");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("url", "is not a valid URI (characters such as spaces must be percent-encoded)");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            // Blocks javascript:, data:, file:, ftp: ... which are XSS / local-file vectors.
            throw new InvalidRequestException("url", "must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidRequestException("url", "must include a valid host");
        }
        if (uri.getRawUserInfo() != null) {
            // https://trusted.com@evil.com/ really goes to evil.com: a classic phishing disguise.
            throw new InvalidRequestException("url", "must not contain credentials (user:password@)");
        }
        if (ownHost != null && ownHost.equalsIgnoreCase(uri.getHost())) {
            // Shortening our own short links creates redirect chains/loops.
            throw new InvalidRequestException("url", "must not point at this shortener");
        }
        return url;
    }

    private static String hostOf(String baseUrl) {
        try {
            return URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
