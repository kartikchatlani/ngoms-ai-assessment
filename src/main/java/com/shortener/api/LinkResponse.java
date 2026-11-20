package com.shortener.api;

import com.shortener.link.Link;
import com.shortener.link.LinkStatus;
import java.time.Instant;

/** Public representation of a link. Internal ids are never exposed. */
public record LinkResponse(
        String code,
        String shortUrl,
        String longUrl,
        boolean customAlias,
        LinkStatus status,
        Instant createdAt,
        Instant expiresAt) {

    static LinkResponse from(Link link, String baseUrl, Instant now) {
        return new LinkResponse(
                link.code(),
                baseUrl + "/" + link.code(),
                link.longUrl(),
                link.customAlias(),
                link.statusAt(now),
                link.createdAt(),
                link.expiresAt());
    }
}
