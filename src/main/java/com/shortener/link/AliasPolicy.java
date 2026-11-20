package com.shortener.link;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Rules for codes: user-chosen aliases must look like codes and must not shadow app routes. */
@Component
public class AliasPolicy {

    /** Also enforced by the DB CHECK constraint and the redirect route pattern. Keep in sync. */
    public static final String CODE_REGEX = "[A-Za-z0-9_-]{3,32}";
    private static final Pattern CODE_PATTERN = Pattern.compile(CODE_REGEX);

    /** Compared case-insensitively so "API" or "Admin" cannot impersonate app paths either. */
    private static final Set<String> RESERVED = Set.of(
            "api", "actuator", "health", "admin", "login", "logout", "static",
            "assets", "docs", "favicon", "robots", "sitemap", "well-known");

    public void validate(String alias) {
        if (!CODE_PATTERN.matcher(alias).matches()) {
            throw new InvalidRequestException("alias", "must be 3-32 characters of A-Z, a-z, 0-9, '_' or '-'");
        }
        if (isReserved(alias)) {
            throw new InvalidRequestException("alias", "is reserved");
        }
    }

    public boolean isReserved(String code) {
        return RESERVED.contains(code.toLowerCase(Locale.ROOT));
    }
}
