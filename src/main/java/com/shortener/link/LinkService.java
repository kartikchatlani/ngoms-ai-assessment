package com.shortener.link;

import com.shortener.abuse.UrlNotAllowedException;
import com.shortener.abuse.UrlScreener;
import com.shortener.config.AppProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Core use cases: create, resolve (redirect), get, list, delete, and admin takedown. */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    /** Latest accepted expiry; comfortably inside PostgreSQL's timestamptz range. */
    static final Instant MAX_EXPIRY = Instant.parse("9999-12-31T23:59:59Z");

    private final LinkRepository repository;
    private final CodeGenerator codeGenerator;
    private final UrlPolicy urlPolicy;
    private final AliasPolicy aliasPolicy;
    private final UrlScreener screener;
    private final Clock clock;
    private final int maxAttempts;
    private final int maxPageSize;

    public LinkService(LinkRepository repository, CodeGenerator codeGenerator, UrlPolicy urlPolicy,
                       AliasPolicy aliasPolicy, UrlScreener screener, Clock clock, AppProperties props) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
        this.urlPolicy = urlPolicy;
        this.aliasPolicy = aliasPolicy;
        this.screener = screener;
        this.clock = clock;
        this.maxAttempts = props.codeMaxAttempts();
        this.maxPageSize = props.maxPageSize();
    }

    public record CreateCommand(String url, String alias, Instant expiresAt) {
    }

    public record Page(List<Link> items, String nextCursor) {
    }

    /**
     * Creates a link owned by the caller's key (anonymous links have no owner). Not
     * deduplicated: the same URL submitted twice yields two codes, because links carry their
     * own expiry, analytics and owner (ADR-0005).
     */
    public Link create(CreateCommand cmd, Caller caller) {
        String url = urlPolicy.validate(cmd.url());
        screen(url, caller);
        Instant now = clock.instant();
        if (cmd.expiresAt() != null && !cmd.expiresAt().isAfter(now)) {
            throw new InvalidRequestException("expiresAt", "must be in the future");
        }
        if (cmd.expiresAt() != null && cmd.expiresAt().isAfter(MAX_EXPIRY)) {
            // BUG-001: Java Instants go far beyond PostgreSQL's timestamptz range; without this
            // guard such a value reached the INSERT and surfaced as a 500.
            throw new InvalidRequestException("expiresAt", "must be before " + MAX_EXPIRY);
        }

        if (cmd.alias() != null) {
            aliasPolicy.validate(cmd.alias());
            return repository.insertIfCodeAbsent(new NewLink(cmd.alias(), url, true, now, cmd.expiresAt(), caller.keyId()))
                    .orElseThrow(() -> new AliasTakenException(cmd.alias()));
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String code = codeGenerator.generate();
            if (aliasPolicy.isReserved(code)) {
                continue;
            }
            Optional<Link> created = repository.insertIfCodeAbsent(
                    new NewLink(code, url, false, now, cmd.expiresAt(), caller.keyId()));
            if (created.isPresent()) {
                return created.get();
            }
            // P(collision) = stored / 62^7, about 1 in 3.5M creates at 1M stored links.
            // Logged so a rising rate (code space filling up) is visible.
            log.warn("Short code collision on attempt {}/{}", attempt, maxAttempts);
        }
        throw new CodeSpaceExhaustedException(maxAttempts);
    }

    /** Redirect path: only an ACTIVE link on a domain that isn't blocked resolves. */
    public Link resolve(String code) {
        Link link = repository.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        LinkStatus status = statusOf(link);
        if (status != LinkStatus.ACTIVE) {
            throw new LinkGoneException(code, status);
        }
        return link;
    }

    /**
     * Status as the public sees it: the stored state, plus BLOCKED when the destination's domain
     * is on the blocklist. Used by redirects and management responses alike, so an owner never
     * sees ACTIVE for a link that actually returns 410.
     */
    public LinkStatus statusOf(Link link) {
        LinkStatus stored = link.statusAt(clock.instant());
        if (stored == LinkStatus.ACTIVE && screener.isBlocked(hostOf(link.longUrl()))) {
            return LinkStatus.BLOCKED;
        }
        return stored;
    }

    /**
     * Management path. Expired and blocked links stay visible to their owner; deleted links are
     * not. A link the caller can't manage is reported as not found, exactly like a code that
     * doesn't exist, so the response never confirms who owns what (scenario 3 §1.4).
     */
    public Link get(String code, Caller caller) {
        return repository.findByCode(code)
                .filter(link -> !link.isDeleted())
                .filter(caller::canManage)
                .orElseThrow(() -> new LinkNotFoundException(code));
    }

    /** The caller's own live links, newest first; admins see all. */
    public Page list(String cursor, Integer limit, Caller caller) {
        int pageSize = limit == null ? 20 : limit;
        if (pageSize < 1 || pageSize > maxPageSize) {
            throw new InvalidRequestException("limit", "must be between 1 and " + maxPageSize);
        }
        long beforeId = cursor == null ? Long.MAX_VALUE : decodeCursor(cursor);
        Long owner = caller.admin() ? null : requireKey(caller);

        // Fetch one extra row to know whether another page exists without a COUNT query.
        List<Link> rows = repository.findLivePage(owner, beforeId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Link> items = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore ? encodeCursor(items.getLast().id()) : null;
        return new Page(List.copyOf(items), next);
    }

    /**
     * Idempotent for the owner: deleting an already-deleted link succeeds (DELETE semantics per
     * RFC 9110). A code that never existed, or that the caller doesn't own, is a 404.
     */
    public void delete(String code, Caller caller) {
        Link link = repository.findByCode(code)
                .filter(caller::canManage)
                .orElseThrow(() -> new LinkNotFoundException(code));
        if (repository.softDelete(code, clock.instant(), caller.keyId())) {
            log.info("Link deleted code={} by={}", code, caller.keyId());
        } else if (!link.isDeleted()) {
            throw new IllegalStateException("Link " + code + " vanished during delete");
        }
    }

    // ---- admin ----------------------------------------------------------------------------

    /** Takedown: the link returns 410 LINK_BLOCKED, and its owner can't undo it. */
    public void block(String code, String reason) {
        if (!repository.block(code, clock.instant(), reason)) {
            throw new LinkNotFoundException(code);
        }
        log.warn("Link blocked code={} reason={}", code, reason);
    }

    public void unblock(String code) {
        if (!repository.unblock(code)) {
            throw new LinkNotFoundException(code);
        }
        log.warn("Link unblocked code={}", code);
    }

    /** E.g. hand pre-Phase 3 campaign links to the marketing team's key (scenario 3, Q7). */
    public void assignOwner(String code, long ownerKeyId) {
        if (!repository.assignOwner(code, ownerKeyId)) {
            throw new LinkNotFoundException(code);
        }
        log.info("Link owner changed code={} owner={}", code, ownerKeyId);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void screen(String url, Caller caller) {
        UrlScreener.Verdict verdict = screener.screenForCreate(hostOf(url));
        if (verdict != UrlScreener.Verdict.ALLOWED) {
            // The rule is logged for operators but never returned to the client (ADR-0010).
            log.info("Destination refused verdict={} host={} by={}", verdict, hostOf(url), caller.keyId());
            throw new UrlNotAllowedException(verdict);
        }
    }

    private static Long requireKey(Caller caller) {
        if (caller.isAnonymous()) {
            // The web layer already requires a key here; this guards future call sites.
            throw new IllegalStateException("Listing links requires an API key");
        }
        return caller.keyId();
    }

    /** UrlPolicy has already validated stored URLs, so parsing can't fail for them. */
    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Cursor is opaque to clients so the pagination key can change without breaking them.
    static String encodeCursor(long id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(id).getBytes(StandardCharsets.US_ASCII));
    }

    static long decodeCursor(String cursor) {
        try {
            long id = Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (IllegalArgumentException e) { // covers NumberFormatException
            throw new InvalidRequestException("cursor", "is invalid");
        }
    }
}
