package com.shortener.analytics;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reduces raw request headers to the coarse values we store (ADR-0007). Pure functions, cheap
 * enough to run on the request thread, and never throw: bad input becomes null / UNKNOWN.
 */
public final class ClickClassifier {

    static final int MAX_HOST_LENGTH = 255;

    // Deliberately coarse heuristics. Precise UA parsing needs a maintained database (e.g. ua-parser),
    // which is not worth a dependency for a DESKTOP / MOBILE / BOT split.
    private static final Pattern BOT = Pattern.compile(
            "bot|crawl|spider|slurp|preview|facebookexternalhit|embedly|curl|wget|http-?client|"
                    + "python-requests|okhttp|java/|go-http-client|headless",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MOBILE = Pattern.compile("mobi|android|iphone|ipad|ipod",
            Pattern.CASE_INSENSITIVE);

    private ClickClassifier() {
    }

    /** Host of the Referer header, lower-cased; null if missing, malformed or absurdly long. */
    public static String referrerHost(String referer) {
        if (referer == null || referer.isBlank() || referer.length() > 4096) {
            return null;
        }
        try {
            String host = new URI(referer.strip()).getHost();
            if (host == null || host.isEmpty() || host.length() > MAX_HOST_LENGTH) {
                return null;
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (Exception e) { // URISyntaxException and anything else odd input can cause
            return null;
        }
    }

    public static AgentClass agentClass(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return AgentClass.UNKNOWN;
        }
        // Bound the work done per request regardless of header size.
        String ua = userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent;
        if (BOT.matcher(ua).find()) {
            return AgentClass.BOT; // checked first: many crawlers also claim "Mobile"
        }
        return MOBILE.matcher(ua).find() ? AgentClass.MOBILE : AgentClass.DESKTOP;
    }
}
