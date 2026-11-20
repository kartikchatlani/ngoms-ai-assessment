package com.shortener.api;

import com.shortener.abuse.CreateRateLimiter;
import com.shortener.auth.ApiKey;
import com.shortener.config.AppProperties;
import com.shortener.link.Caller;
import com.shortener.link.Link;
import com.shortener.link.LinkService;
import com.shortener.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management API, versioned under /api/v1 (ADR-0005). Since Phase 3, everything except create
 * needs an API key and only covers the caller's own links (ADR-0008).
 */
@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkService service;
    private final CreateRateLimiter rateLimiter;
    private final ClientIpResolver clientIp;
    private final String baseUrl;

    public LinkController(LinkService service, CreateRateLimiter rateLimiter, ClientIpResolver clientIp,
                          AppProperties props) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.clientIp = clientIp;
        this.baseUrl = props.normalizedBaseUrl();
    }

    @PostMapping
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request,
                                               @AuthenticationPrincipal ApiKey key, HttpServletRequest http) {
        Caller caller = Callers.of(key);
        rateLimiter.check(caller.keyId(), caller.admin(), clientIp.resolve(http));
        Link link = service.create(new LinkService.CreateCommand(request.url(), request.alias(), request.expiresAt()), caller);
        return ResponseEntity
                .created(URI.create("/api/v1/links/" + link.code()))
                .body(toResponse(link));
    }

    @GetMapping("/{code}")
    public LinkResponse get(@PathVariable String code, @AuthenticationPrincipal ApiKey key) {
        return toResponse(service.get(code, Callers.of(key)));
    }

    @GetMapping
    public LinkPageResponse list(@RequestParam(required = false) String cursor,
                                 @RequestParam(required = false) Integer limit,
                                 @AuthenticationPrincipal ApiKey key) {
        LinkService.Page page = service.list(cursor, limit, Callers.of(key));
        return new LinkPageResponse(page.items().stream().map(this::toResponse).toList(), page.nextCursor());
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code, @AuthenticationPrincipal ApiKey key) {
        service.delete(code, Callers.of(key));
        return ResponseEntity.noContent().build();
    }

    private LinkResponse toResponse(Link link) {
        return LinkResponse.from(link, service.statusOf(link), baseUrl);
    }
}
