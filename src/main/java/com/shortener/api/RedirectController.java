package com.shortener.api;

import com.shortener.analytics.ClickRecorder;
import com.shortener.link.Link;
import com.shortener.link.LinkService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
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
    private final ClickRecorder clicks;

    public RedirectController(LinkService service, ClickRecorder clicks) {
        this.service = service;
        this.clicks = clicks;
    }

    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        Link link = service.resolve(code);
        // Count only successful GETs: HEAD comes from link checkers and preview fetchers, not
        // people. record() is a non-blocking enqueue that cannot throw (ADR-0006).
        if (!"HEAD".equals(request.getMethod())) {
            clicks.record(link.id(), request.getHeader(HttpHeaders.REFERER), request.getHeader(HttpHeaders.USER_AGENT));
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.longUrl()))
                // 302 + no-store: browsers/proxies re-ask us every time, so deletes and expiry
                // take effect immediately and every click is counted.
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
