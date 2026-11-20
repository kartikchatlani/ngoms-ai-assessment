package com.shortener.link;

import com.shortener.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Core use cases: create, resolve (redirect), get, list, delete. */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    /** Latest accepted expiry; comfortably inside PostgreSQL's timestamptz range. */
    static final Instant MAX_EXPIRY = Instant.parse("9999-12-31T23:59:59Z");

    private final LinkRepository repository;
    private final CodeGenerator codeGenerator;
    private final UrlPolicy urlPolicy;
    private final AliasPolicy aliasPolicy;
    private final Clock clock;
    private final int maxAttempts;
    private final int maxPageSize;

    public LinkService(LinkRepository repository, CodeGenerator codeGenerator, UrlPolicy urlPolicy,
                       AliasPolicy aliasPolicy, Clock clock, AppProperties props) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
        this.urlPolicy = urlPolicy;
        this.aliasPolicy = aliasPolicy;
        this.clock = clock;
        this.maxAttempts = props.codeMaxAttempts();
        this.maxPageSize = props.maxPageSize();
    }

    public record CreateCommand(String url, String alias, Instant expiresAt) {
    }

    public record Page(List<Link> items, String nextCursor) {
    }

    /**
     * Creates a link. Not deduplicated: the same URL submitted twice yields two codes, because
     * links carry their own expiry and (from Phase 2) their own analytics (ADR-0005).
     */
    public Link create(CreateCommand cmd) {
        String url = urlPolicy.validate(cmd.url());
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
            return repository.insertIfCodeAbsent(new NewLink(cmd.alias(), url, true, now, cmd.expiresAt()))
                    .orElseThrow(() -> new AliasTakenException(cmd.alias()));
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String code = codeGenerator.generate();
            if (aliasPolicy.isReserved(code)) {
                continue;
            }
            Optional<Link> created = repository.insertIfCodeAbsent(new NewLink(code, url, false, now, cmd.expiresAt()));
            if (created.isPresent()) {
                return created.get();
            }
            // P(collision) = stored / 62^7, about 1 in 3.5M creates at 1M stored links.
            // Logged so a rising rate (code space filling up) is visible.
            log.warn("Short code collision on attempt {}/{}", attempt, maxAttempts);
        }
        throw new CodeSpaceExhaustedException(maxAttempts);
    }

    /** Redirect path: only an ACTIVE link resolves. */
    public Link resolve(String code) {
        Link link = repository.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        LinkStatus status = link.statusAt(clock.instant());
        if (status != LinkStatus.ACTIVE) {
            throw new LinkGoneException(code, status);
        }
        return link;
    }

    /** Management path: expired links stay visible (owners want to see them); deleted ones do not. */
    public Link get(String code) {
        return repository.findByCode(code)
                .filter(link -> !link.isDeleted())
                .orElseThrow(() -> new LinkNotFoundException(code));
    }

    public Page list(String cursor, Integer limit) {
        int pageSize = limit == null ? 20 : limit;
        if (pageSize < 1 || pageSize > maxPageSize) {
            throw new InvalidRequestException("limit", "must be between 1 and " + maxPageSize);
        }
        long beforeId = cursor == null ? Long.MAX_VALUE : decodeCursor(cursor);

        // Fetch one extra row to know whether another page exists without a COUNT query.
        List<Link> rows = repository.findLivePage(beforeId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Link> items = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore ? encodeCursor(items.getLast().id()) : null;
        return new Page(List.copyOf(items), next);
    }

    /**
     * Idempotent: deleting an already-deleted link succeeds (DELETE semantics per RFC 9110);
     * deleting a code that never existed is a 404 so typos are not silently accepted.
     */
    public void delete(String code) {
        if (repository.softDelete(code, clock.instant())) {
            log.info("Link deleted code={}", code);
            return;
        }
        if (repository.findByCode(code).isEmpty()) {
            throw new LinkNotFoundException(code);
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
