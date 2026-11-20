package com.shortener.api;

import com.shortener.link.Link;
import com.shortener.link.LinkStatus;
import java.time.Instant;

/**
 * Public representation of a link. Internal ids, the owner key and the takedown reason are
 * never exposed.
 */
public record LinkResponse(
        String code,
        String shortUrl,
        String longUrl,
        boolean customAlias,
        LinkStatus status,
        Instant createdAt,
        Instant expiresAt) {

    /** @param status from LinkService.statusOf, which also accounts for blocked domains */
    static LinkResponse from(Link link, LinkStatus status, String baseUrl) {
        return new LinkResponse(
                link.code(),
                baseUrl + "/" + link.code(),
                link.longUrl(),
                link.customAlias(),
                status,
                link.createdAt(),
                link.expiresAt());
    }
}
