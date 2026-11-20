package com.shortener.api;

import com.shortener.link.Link;
import com.shortener.link.LinkService;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hot path: GET /{code} -> 302 to the destination (ADR-0004).
 *
 * <p>The path pattern mirrors AliasPolicy.CODE_REGEX, so things like /favicon.ico never hit the DB.
 * HEAD is served automatically by Spring for GET mappings.
 */
@RestController
public class RedirectController {

    private final LinkService service;

    public RedirectController(LinkService service) {
        this.service = service;
    }

    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        Link link = service.resolve(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.longUrl()))
                // 302 + no-store: browsers/proxies re-ask us every time, so deletes and expiry
                // take effect immediately and (Phase 2) every click is counted.
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
