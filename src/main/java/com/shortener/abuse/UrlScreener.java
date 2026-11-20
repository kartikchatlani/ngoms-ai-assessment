package com.shortener.abuse;

import com.shortener.config.AbuseProperties;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reputation checks on destinations (ADR-0010), on top of UrlPolicy's syntax checks.
 *
 * <ul>
 *   <li><b>Blocked domains</b> (admin-managed, in the database): refused on create <em>and</em>
 *       on every redirect, so blocking a domain disables all existing links to it at once.</li>
 *   <li><b>Other URL shorteners</b> (configuration): refused on create only, because a chain of
 *       short links hides the real destination from users and from our own screening.</li>
 * </ul>
 *
 * <p>The blocklist is held in memory as an immutable set, so the redirect-time check is a few
 * hash lookups with no I/O. Each instance reloads it periodically; a failed reload keeps the
 * previous list (a database blip must never silently unblock everything).
 */
@Component
public class UrlScreener {

    private static final Logger log = LoggerFactory.getLogger(UrlScreener.class);

    /** Lower-case DNS name or IPv4 address, with at least one dot (so a whole TLD can't be blocked by mistake). */
    private static final Pattern DOMAIN = Pattern.compile("(?=.{3,253}$)[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    public enum Verdict { ALLOWED, BLOCKED_DOMAIN, URL_SHORTENER }

    private final BlockedDomainRepository repository;
    private final Clock clock;
    private final Set<String> shorteners;
    private volatile Set<String> blocked = Set.of();

    public UrlScreener(BlockedDomainRepository repository, Clock clock, AbuseProperties props) {
        this.repository = repository;
        this.clock = clock;
        this.shorteners = props.shortenerDomains().stream().map(UrlScreener::normalize).collect(Collectors.toUnmodifiableSet());
    }

    /** Checks for a new link. */
    public Verdict screenForCreate(String host) {
        String h = normalize(host);
        if (matches(h, blocked)) {
            return Verdict.BLOCKED_DOMAIN;
        }
        return matches(h, shorteners) ? Verdict.URL_SHORTENER : Verdict.ALLOWED;
    }

    /** Checks on the redirect path: blocklist only, in memory. */
    public boolean isBlocked(String host) {
        return host != null && matches(normalize(host), blocked);
    }

    // ---- admin operations ------------------------------------------------------------------

    public List<BlockedDomain> list() {
        return repository.findAll();
    }

    /** @return the normalised domain that was blocked */
    public String block(String domain, String reason, Long byKeyId) {
        String d = validDomain(domain);
        repository.insertIfAbsent(d, reason, clock.instant(), byKeyId);
        refresh(); // effective immediately on this instance; others within the refresh interval
        log.warn("Domain blocked domain={} by={} reason={}", d, byKeyId, reason);
        return d;
    }

    /** @return false if the domain wasn't blocked */
    public boolean unblock(String domain) {
        String d = validDomain(domain);
        boolean removed = repository.delete(d);
        refresh();
        if (removed) {
            log.warn("Domain unblocked domain={}", d);
        }
        return removed;
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${app.abuse.blocklist-refresh-seconds:30}", initialDelayString = "${app.abuse.blocklist-refresh-seconds:30}", timeUnit = TimeUnit.SECONDS)
    public void refresh() {
        try {
            blocked = repository.findAll().stream().map(BlockedDomain::domain).collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException e) {
            log.warn("Blocklist refresh failed; keeping the previous {} entries ({})", blocked.size(), e.toString());
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** True if the host or any parent domain is in the set: blocking evil.example also blocks a.b.evil.example. */
    static boolean matches(String host, Set<String> domains) {
        if (host == null || domains.isEmpty()) {
            return false;
        }
        String h = host;
        while (true) {
            if (domains.contains(h)) {
                return true;
            }
            int dot = h.indexOf('.');
            if (dot < 0) {
                return false;
            }
            h = h.substring(dot + 1);
        }
    }

    /** Lower-case, without the trailing dot of a fully-qualified name ("evil.example." is the same host). */
    static String normalize(String host) {
        if (host == null) {
            return null;
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    static String validDomain(String raw) {
        String d = normalize(raw);
        if (d == null || !DOMAIN.matcher(d).matches()) {
            throw new InvalidDomainException();
        }
        return d;
    }
}
